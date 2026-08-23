//! Discovers every `*.delta` file under a resources directory, links them
//! into the linear chain implied by their `dependencies` field, and replays
//! that chain from F0 to produce one [`Layer`] per step (plus F0 itself as
//! layer 0). Nothing here is cached: call [`build`] fresh on every request.

use crate::codec::{self, Change, Node, Op};
use crate::graph::{self, Graph};
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

pub struct UnparsedFile {
    pub relative_path: String,
    pub error: String,
}

pub struct UnlinkedChange {
    pub relative_path: String,
    pub change_id: String,
    pub message: String,
    pub dependencies: Vec<String>,
    pub reason: String,
}

pub enum LayerStatus {
    Ok,
    /// The change decoded, but couldn't be applied on top of the previous
    /// layer (dependency mismatch, missing referenced node, or a structural
    /// invariant violation). `graph` on this layer is the previous layer's
    /// graph, unchanged, so the timeline doesn't just go blank.
    Broken(String),
}

pub struct EntitySummary {
    pub entity: String,
    pub content_id: String,
    pub kind: String,
    pub attrs: Vec<(String, String)>,
    pub ports: Vec<PortSummary>,
}

pub struct PortSummary {
    pub name: String,
    pub direction: &'static str,
    pub ty: String,
}

pub struct EdgeSummary {
    pub content_id: String,
    pub from_node: String,
    pub from_entities: Vec<String>,
    pub from_port: String,
    pub to_node: String,
    pub to_entities: Vec<String>,
    pub to_port: String,
    pub role: String,
}

pub struct RootSummary {
    pub name: String,
    pub content_id: String,
    pub entities: Vec<String>,
}

pub struct GraphSnapshot {
    pub node_count: usize,
    pub edge_count: usize,
    pub entities: Vec<EntitySummary>,
    pub edges: Vec<EdgeSummary>,
    pub roots: Vec<RootSummary>,
}

#[derive(Default)]
pub struct Diff {
    pub added_entities: Vec<String>,
    pub removed_entities: Vec<String>,
    pub replaced_entities: Vec<(String, String, String)>, // entity, before, after
    pub added_roots: Vec<String>,
    pub removed_roots: Vec<String>,
}

pub struct OpSummary {
    pub op: &'static str,
    pub node: Option<Node>,
    pub node_content_id: Option<String>,
    pub entity: Option<String>,
    pub name: Option<String>,
    pub content_ref: Option<String>,
    pub edge: Option<crate::codec::Edge>,
    pub edge_content_id: Option<String>,
}

pub struct Layer {
    pub index: usize,
    pub relative_path: Option<String>,
    pub change_id: Option<String>,
    pub message: Option<String>,
    pub author: Option<String>,
    pub dependencies: Vec<String>,
    pub status: LayerStatus,
    pub ops: Vec<OpSummary>,
    pub graph: GraphSnapshot,
    pub diff: Diff,
}

pub struct ChainResult {
    pub layers: Vec<Layer>,
    pub unparsed: Vec<UnparsedFile>,
    pub unlinked: Vec<UnlinkedChange>,
}

fn collect_delta_files(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    let mut entries: Vec<_> = entries.flatten().collect();
    entries.sort_by_key(|e| e.file_name());
    for entry in entries {
        let path = entry.path();
        if path.is_dir() {
            collect_delta_files(&path, out);
        } else if path.extension().and_then(|e| e.to_str()) == Some("delta") {
            out.push(path);
        }
    }
}

fn expand_delta_file(path: &Path) -> Result<Vec<(String, Vec<u8>)>, String> {
    let bytes = fs::read(path).map_err(|e| format!("cannot read: {e}"))?;
    if !bytes.starts_with(b"delta-set ") && !bytes.starts_with(b"delta-package ") {
        return Ok(vec![(path.to_string_lossy().into_owned(), bytes)]);
    }
    let source = std::str::from_utf8(&bytes).map_err(|e| format!("delta-set is not UTF-8: {e}"))?;
    let mut expanded = Vec::new();
    let mut has_inline_change = false;
    for (index, line) in source.lines().enumerate().skip(1) {
        let trimmed = line.trim();
        if trimmed.is_empty()
            || trimmed.starts_with("purpose ")
            || trimmed.starts_with("post-action ")
            || trimmed.starts_with("provides ")
            || trimmed.starts_with("requires ")
            || trimmed.starts_with("imports ")
            || trimmed.starts_with("conflicts ")
            || trimmed.starts_with("entity ")
            || trimmed.starts_with("attr ")
        { continue; }
        if trimmed.starts_with("change ") { has_inline_change = true; continue; }
        let include = trimmed.strip_prefix("include ").ok_or_else(|| format!("line {}: expected purpose, include, or post-action", index + 1))?;
        if include.is_empty() || include.starts_with('/') || include.split('/').any(|part| part == "..") {
            return Err(format!("line {}: unsafe include {include}", index + 1));
        }
        let included = path.parent().unwrap_or(Path::new(".")).join(include);
        let payload = fs::read(&included).map_err(|e| format!("line {}: cannot read {include}: {e}", index + 1))?;
        expanded.push((format!("{}#{include}", path.to_string_lossy()), payload));
    }
    if expanded.is_empty() && !has_inline_change { Err("delta package contains neither includes nor inline changes".into()) } else { Ok(expanded) }
}

fn snapshot(g: &Graph) -> GraphSnapshot {
    let mut reverse: BTreeMap<String, Vec<String>> = BTreeMap::new();
    for (entity, content_id) in &g.entities {
        reverse.entry(content_id.clone()).or_default().push(entity.clone());
    }
    let entities = g
        .entities
        .iter()
        .filter_map(|(entity, content_id)| {
            g.nodes.get(content_id).map(|node| EntitySummary {
                entity: entity.clone(),
                content_id: content_id.clone(),
                kind: node.kind.clone(),
                attrs: node.attrs.clone(),
                ports: node
                    .ports
                    .iter()
                    .map(|p| PortSummary {
                        name: p.name.clone(),
                        direction: p.direction.text(),
                        ty: codec::render_ty(&p.ty),
                    })
                    .collect(),
            })
        })
        .collect();
    let edges = g
        .edges
        .iter()
        .map(|(id, e)| EdgeSummary {
            content_id: id.clone(),
            from_node: e.from.node.clone(),
            from_entities: reverse.get(&e.from.node).cloned().unwrap_or_default(),
            from_port: e.from.port.clone(),
            to_node: e.to.node.clone(),
            to_entities: reverse.get(&e.to.node).cloned().unwrap_or_default(),
            to_port: e.to.port.clone(),
            role: e.role.clone(),
        })
        .collect();
    let roots = g
        .roots
        .iter()
        .map(|(name, content_id)| RootSummary {
            name: name.clone(),
            content_id: content_id.clone(),
            entities: reverse.get(content_id).cloned().unwrap_or_default(),
        })
        .collect();
    GraphSnapshot {
        node_count: g.nodes.len(),
        edge_count: g.edges.len(),
        entities,
        edges,
        roots,
    }
}

fn diff_graphs(prev: &Graph, cur: &Graph) -> Diff {
    let mut d = Diff::default();
    for (entity, id) in &cur.entities {
        match prev.entities.get(entity) {
            None => d.added_entities.push(entity.clone()),
            Some(old) if old != id => {
                d.replaced_entities.push((entity.clone(), old.clone(), id.clone()))
            }
            _ => {}
        }
    }
    for entity in prev.entities.keys() {
        if !cur.entities.contains_key(entity) {
            d.removed_entities.push(entity.clone());
        }
    }
    for name in cur.roots.keys() {
        if !prev.roots.contains_key(name) {
            d.added_roots.push(name.clone());
        }
    }
    for name in prev.roots.keys() {
        if !cur.roots.contains_key(name) {
            d.removed_roots.push(name.clone());
        }
    }
    d.added_entities.sort();
    d.removed_entities.sort();
    d.replaced_entities.sort();
    d
}

fn summarize_ops(change: &Change) -> Vec<OpSummary> {
    change
        .operations
        .iter()
        .map(|op| match op {
            Op::AddNode(n) => OpSummary {
                op: "add-node",
                node_content_id: Some(codec::node_id(n)),
                node: Some(n.clone()),
                entity: None,
                name: None,
                content_ref: None,
                edge: None,
                edge_content_id: None,
            },
            Op::BindEntity(e, n) => OpSummary {
                op: "bind-entity",
                node_content_id: None,
                node: None,
                entity: Some(e.clone()),
                name: None,
                content_ref: Some(n.clone()),
                edge: None,
                edge_content_id: None,
            },
            Op::ReplaceEntity(e, n) => OpSummary {
                op: "replace-entity",
                node_content_id: Some(codec::node_id(n)),
                node: Some(n.clone()),
                entity: Some(e.clone()),
                name: None,
                content_ref: None,
                edge: None,
                edge_content_id: None,
            },
            Op::RemoveEntity(e) => OpSummary {
                op: "remove-entity",
                node_content_id: None,
                node: None,
                entity: Some(e.clone()),
                name: None,
                content_ref: None,
                edge: None,
                edge_content_id: None,
            },
            Op::Connect(edge) => OpSummary {
                op: "connect",
                node_content_id: None,
                node: None,
                entity: None,
                name: None,
                content_ref: None,
                edge_content_id: Some(codec::edge_id(edge)),
                edge: Some(edge.clone()),
            },
            Op::Disconnect(id) => OpSummary {
                op: "disconnect",
                node_content_id: None,
                node: None,
                entity: None,
                name: None,
                content_ref: Some(id.clone()),
                edge: None,
                edge_content_id: None,
            },
            Op::AddRoot(name, node) => OpSummary {
                op: "add-root",
                node_content_id: None,
                node: None,
                entity: None,
                name: Some(name.clone()),
                content_ref: Some(node.clone()),
                edge: None,
                edge_content_id: None,
            },
            Op::RemoveRoot(name) => OpSummary {
                op: "remove-root",
                node_content_id: None,
                node: None,
                entity: None,
                name: Some(name.clone()),
                content_ref: None,
                edge: None,
                edge_content_id: None,
            },
            Op::RefineHole(e, n) => OpSummary {
                op: "refine-hole",
                node_content_id: Some(codec::node_id(n)),
                node: Some(n.clone()),
                entity: Some(e.clone()),
                name: None,
                content_ref: None,
                edge: None,
                edge_content_id: None,
            },
        })
        .collect()
}

struct Decoded {
    relative_path: String,
    change: Change,
    change_id: String,
}

pub fn build(resources: &Path) -> ChainResult {
    let mut files = Vec::new();
    collect_delta_files(resources, &mut files);

    let mut decoded = Vec::new();
    let mut unparsed = Vec::new();
    for path in &files {
        let relative_path = path
            .strip_prefix(resources.parent().unwrap_or(resources))
            .unwrap_or(path)
            .to_string_lossy()
            .replace('\\', "/");
        match expand_delta_file(path) {
            Ok(entries) => for (expanded_path, bytes) in entries { match codec::decode_change_bytes(&bytes) {
                Ok(change) => {
                    let cid = codec::change_id(&change);
                    let suffix = expanded_path.split_once('#').map(|(_, value)| format!("#{value}")).unwrap_or_default();
                    decoded.push(Decoded { relative_path: format!("{relative_path}{suffix}"), change, change_id: cid });
                }
                Err(e) => unparsed.push(UnparsedFile { relative_path: expanded_path, error: e }),
            }},
            Err(e) => unparsed.push(UnparsedFile { relative_path, error: format!("cannot read: {e}") }),
        }
    }

    let by_id: BTreeMap<String, usize> = decoded
        .iter()
        .enumerate()
        .map(|(i, d)| (d.change_id.clone(), i))
        .collect();
    // Map from a change id to the change(s) that declare it as their sole dependency.
    let mut children: BTreeMap<String, Vec<usize>> = BTreeMap::new();
    let mut roots = Vec::new();
    for (i, d) in decoded.iter().enumerate() {
        match d.change.dependencies.as_slice() {
            [] => roots.push(i),
            [only] => children.entry(only.clone()).or_default().push(i),
            _ => {
                // Multiple dependencies (a merge) can't be placed in a single
                // linear chain; surface it as unlinked rather than guessing.
            }
        }
    }

    let mut chain_indices = Vec::new();
    let mut used = vec![false; decoded.len()];
    if roots.len() == 1 {
        let mut current = roots[0];
        used[current] = true;
        chain_indices.push(current);
        loop {
            let cid = decoded[current].change_id.clone();
            match children.get(&cid).map(|v| v.as_slice()) {
                Some([only]) => {
                    used[*only] = true;
                    chain_indices.push(*only);
                    current = *only;
                }
                _ => break, // no child, or a branch (multiple children) -- stop the linear chain here
            }
        }
    }

    let mut unlinked = Vec::new();
    for (i, d) in decoded.iter().enumerate() {
        if used[i] {
            continue;
        }
        let reason = if d.change.dependencies.is_empty() {
            "another file also has no dependency (multiple chain roots)".to_string()
        } else if d.change.dependencies.len() > 1 {
            "change has more than one dependency (merge); not supported by the linear chain view".to_string()
        } else {
            let dep = &d.change.dependencies[0];
            if by_id.contains_key(dep) {
                "branches off a change that already has a different successor in the main chain".to_string()
            } else {
                format!("depends on {dep}, which was not found among the decoded .delta files")
            }
        };
        unlinked.push(UnlinkedChange {
            relative_path: d.relative_path.clone(),
            change_id: d.change_id.clone(),
            message: d.change.message.clone(),
            dependencies: d.change.dependencies.clone(),
            reason,
        });
    }

    let mut layers = Vec::new();
    let mut current_graph = graph::build_f0();
    layers.push(Layer {
        index: 0,
        relative_path: None,
        change_id: None,
        message: None,
        author: None,
        dependencies: vec![],
        status: LayerStatus::Ok,
        ops: vec![],
        graph: snapshot(&current_graph),
        diff: Diff::default(),
    });

    let mut prev_change_id: Option<String> = None;
    let mut broken = false;
    for (step, &i) in chain_indices.iter().enumerate() {
        let d = &decoded[i];
        let ops = summarize_ops(&d.change);
        if broken {
            layers.push(Layer {
                index: step + 1,
                relative_path: Some(d.relative_path.clone()),
                change_id: Some(d.change_id.clone()),
                message: Some(d.change.message.clone()),
                author: Some(d.change.author.clone()),
                dependencies: d.change.dependencies.clone(),
                status: LayerStatus::Broken("not replayed: an earlier layer in the chain is broken".into()),
                ops,
                graph: snapshot(&current_graph),
                diff: Diff::default(),
            });
            continue;
        }

        let expected: Vec<String> = prev_change_id.iter().cloned().collect();
        if d.change.dependencies != expected {
            broken = true;
            layers.push(Layer {
                index: step + 1,
                relative_path: Some(d.relative_path.clone()),
                change_id: Some(d.change_id.clone()),
                message: Some(d.change.message.clone()),
                author: Some(d.change.author.clone()),
                dependencies: d.change.dependencies.clone(),
                status: LayerStatus::Broken(format!(
                    "dependency mismatch: expected {:?}, found {:?}",
                    expected, d.change.dependencies
                )),
                ops,
                graph: snapshot(&current_graph),
                diff: Diff::default(),
            });
            continue;
        }

        match graph::apply_change(current_graph.clone(), &d.change)
            .and_then(|g| graph::validate_graph(&g).map(|_| g))
        {
            Ok(next_graph) => {
                let diff = diff_graphs(&current_graph, &next_graph);
                current_graph = next_graph;
                prev_change_id = Some(d.change_id.clone());
                layers.push(Layer {
                    index: step + 1,
                    relative_path: Some(d.relative_path.clone()),
                    change_id: Some(d.change_id.clone()),
                    message: Some(d.change.message.clone()),
                    author: Some(d.change.author.clone()),
                    dependencies: d.change.dependencies.clone(),
                    status: LayerStatus::Ok,
                    ops,
                    graph: snapshot(&current_graph),
                    diff,
                });
            }
            Err(e) => {
                broken = true;
                layers.push(Layer {
                    index: step + 1,
                    relative_path: Some(d.relative_path.clone()),
                    change_id: Some(d.change_id.clone()),
                    message: Some(d.change.message.clone()),
                    author: Some(d.change.author.clone()),
                    dependencies: d.change.dependencies.clone(),
                    status: LayerStatus::Broken(e),
                    ops,
                    graph: snapshot(&current_graph),
                    diff: Diff::default(),
                });
            }
        }
    }

    ChainResult { layers, unparsed, unlinked }
}
