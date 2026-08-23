//! The materialized `Graph` and its apply/replay semantics, mirroring
//! `trellis.Core.Graph` and `trellis.Delta.applyOp`. Also builds the F0
//! bootstrap graph (`trellis.Bootstrap`), the fixed starting point every
//! `.delta` chain replays from.

use crate::codec::{
    edge_id, encode_edge, encode_node, node_id, record, record_owned, sha256_hex, Change, Edge,
    Node, Op, Port, Ty,
};
use std::collections::BTreeMap;

pub const F0_ROOT: &str = "6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd";

#[derive(Clone, Debug, Default)]
pub struct Graph {
    pub nodes: BTreeMap<String, Node>,
    pub edges: BTreeMap<String, Edge>,
    pub entities: BTreeMap<String, String>,
    pub roots: BTreeMap<String, String>,
}

impl Graph {
    pub fn entity(&self, id: &str) -> Option<&Node> {
        self.entities.get(id).and_then(|n| self.nodes.get(n))
    }
}

pub fn node_attr<'a>(n: &'a Node, key: &str) -> Option<&'a str> {
    n.attrs.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
}

pub fn encode_graph(g: &Graph) -> String {
    let nodes: Vec<String> = g
        .nodes
        .iter()
        .map(|(id, n)| record("n", [id.as_str(), encode_node(n).as_str()]))
        .collect();
    let edges: Vec<String> = g
        .edges
        .iter()
        .map(|(id, e)| record("e", [id.as_str(), encode_edge(e).as_str()]))
        .collect();
    let entities: Vec<String> = g
        .entities
        .iter()
        .map(|(e, n)| record("entity", [e.as_str(), n.as_str()]))
        .collect();
    let roots: Vec<String> = g
        .roots
        .iter()
        .map(|(name, id)| record("root", [name.as_str(), id.as_str()]))
        .collect();
    let ns = record_owned("nodes", &nodes);
    let es = record_owned("edges", &edges);
    let ents = record_owned("entities", &entities);
    let rs = record_owned("roots", &roots);
    record("graph", [ns.as_str(), es.as_str(), ents.as_str(), rs.as_str()])
}

pub fn graph_id(g: &Graph) -> String {
    sha256_hex(encode_graph(g).as_bytes())
}

fn add_node(g: &mut Graph, n: Node) -> String {
    let id = node_id(&n);
    g.nodes.insert(id.clone(), n);
    id
}
fn add_edge(g: &mut Graph, e: Edge) -> String {
    let id = edge_id(&e);
    g.edges.insert(id.clone(), e);
    id
}

fn node_referenced(g: &Graph, id: &str) -> bool {
    g.entities.values().any(|x| x == id)
        || g.roots.values().any(|x| x == id)
        || g.edges.values().any(|e| e.from.node == id || e.to.node == id)
}

pub fn apply_change(mut g: Graph, c: &Change) -> Result<Graph, String> {
    for op in &c.operations {
        g = apply_op(g, op)?;
    }
    Ok(g)
}

pub fn apply_op(mut g: Graph, op: &Op) -> Result<Graph, String> {
    match op {
        Op::AddNode(n) => {
            add_node(&mut g, n.clone());
        }
        Op::BindEntity(e, n) => {
            if !g.nodes.contains_key(n) {
                return Err(format!("cannot bind {e}: missing node {n}"));
            }
            g.entities.insert(e.clone(), n.clone());
        }
        Op::ReplaceEntity(e, n) => {
            let old = g.entities.get(e).cloned();
            let id = add_node(&mut g, n.clone());
            g.entities.insert(e.clone(), id.clone());
            if let Some(old) = old {
                if old != id && !node_referenced(&g, &old) {
                    g.nodes.remove(&old);
                }
            }
        }
        Op::RemoveEntity(e) => {
            if let Some(old) = g.entities.remove(e) {
                if !node_referenced(&g, &old) {
                    g.nodes.remove(&old);
                }
            }
        }
        Op::Connect(e) => {
            if !g.nodes.contains_key(&e.from.node) || !g.nodes.contains_key(&e.to.node) {
                return Err("cannot connect edge: endpoint node missing".into());
            }
            add_edge(&mut g, e.clone());
        }
        Op::Disconnect(e) => {
            g.edges.remove(e);
        }
        Op::AddRoot(name, node) => {
            if !g.nodes.contains_key(node) {
                return Err(format!("cannot add root {name}: missing node {node}"));
            }
            g.roots.insert(name.clone(), node.clone());
        }
        Op::RemoveRoot(name) => {
            g.roots.remove(name);
        }
        Op::RefineHole(e, n) => match g.entity(e) {
            Some(old) if old.kind == "core.hole" => {
                return apply_op(g, &Op::ReplaceEntity(e.clone(), n.clone()))
            }
            Some(_) => return Err(format!("entity {e} is not a hole")),
            None => return Err(format!("unknown hole entity {e}")),
        },
    }
    Ok(g)
}

pub fn validate_references(g: &Graph) -> Result<(), String> {
    for (entity, node) in &g.entities {
        if !g.nodes.contains_key(node) {
            return Err(format!("entity {entity} references missing node {node}"));
        }
    }
    for (name, node) in &g.roots {
        if !g.nodes.contains_key(node) {
            return Err(format!("root {name} references missing node {node}"));
        }
    }
    for (id, e) in &g.edges {
        let from = g
            .nodes
            .get(&e.from.node)
            .ok_or_else(|| format!("edge {id} references missing source node {}", e.from.node))?;
        let to = g
            .nodes
            .get(&e.to.node)
            .ok_or_else(|| format!("edge {id} references missing target node {}", e.to.node))?;
        if from.ports.iter().find(|p| p.name == e.from.port).is_none() {
            return Err(format!("edge {id} references missing source port {}", e.from.port));
        }
        if to.ports.iter().find(|p| p.name == e.to.port).is_none() {
            return Err(format!("edge {id} references missing target port {}", e.to.port));
        }
    }
    Ok(())
}

fn structural_policy_duplicate(g: &Graph, mode: &crate::codec::Mode) -> bool {
    use crate::codec::Mode;
    let entity = format!("resource.mode.{}", mode.text());
    if let Some(node) = g.entity(&entity) {
        if node.kind == "resource.mode" {
            return node_attr(node, "duplicate").unwrap_or(match mode {
                Mode::Unrestricted => "allow",
                _ => "forbid",
            }) == "allow";
        }
    }
    matches!(mode, Mode::Unrestricted)
}

/// Structural invariants, mirroring `trellis.Check`'s port/mode checks.
/// Not exhaustive (Check.scala covers more), but enough to flag the common
/// ways a hand-authored delta breaks the graph.
pub fn validate_graph(g: &Graph) -> Result<(), String> {
    use crate::codec::Direction;
    validate_references(g)?;
    let mut errors = Vec::new();
    for (id, e) in &g.edges {
        let from = g.nodes.get(&e.from.node).unwrap();
        let to = g.nodes.get(&e.to.node).unwrap();
        let fp = from.ports.iter().find(|p| p.name == e.from.port).unwrap();
        let tp = to.ports.iter().find(|p| p.name == e.to.port).unwrap();
        if fp.direction != Direction::Out {
            errors.push(format!("edge {} source port is not output", &id[..8]));
        }
        if tp.direction != Direction::In {
            errors.push(format!("edge {} target port is not input", &id[..8]));
        }
        if fp.ty != tp.ty {
            errors.push(format!("edge {} type mismatch", &id[..8]));
        }
    }
    for (id, node) in &g.nodes {
        for p in node.ports.iter().filter(|p| p.direction == Direction::In) {
            let count = g
                .edges
                .values()
                .filter(|e| e.to.node == *id && e.to.port == p.name)
                .count();
            if count > 1 {
                errors.push(format!("multiple producers for {}.{}", &id[..8], p.name));
            }
        }
        for p in node.ports.iter().filter(|p| p.direction == Direction::Out) {
            let count = g
                .edges
                .values()
                .filter(|e| e.from.node == *id && e.from.port == p.name)
                .count();
            if count > 1 && !structural_policy_duplicate(g, &p.ty_mode()) {
                errors.push(format!(
                    "illegal duplication of {} capability at {}.{}",
                    p.ty_mode().text(),
                    &id[..8],
                    p.name
                ));
            }
        }
        if node.kind == "core.hole" && node_attr(node, "expected").is_none() {
            errors.push(format!("hole {} lacks expected boundary description", &id[..8]));
        }
    }
    if errors.is_empty() {
        Ok(())
    } else {
        Err(errors.join("; "))
    }
}

trait TyMode {
    fn ty_mode(&self) -> crate::codec::Mode;
}
impl TyMode for Port {
    fn ty_mode(&self) -> crate::codec::Mode {
        ty_mode(&self.ty)
    }
}
fn ty_mode(t: &Ty) -> crate::codec::Mode {
    use crate::codec::Mode;
    match t {
        Ty::Atom(_) => Mode::Unrestricted,
        Ty::Tuple(items) => {
            if items.iter().any(|x| ty_mode(x) == Mode::Linear) {
                Mode::Linear
            } else if items.iter().any(|x| ty_mode(x) == Mode::Affine) {
                Mode::Affine
            } else {
                Mode::Unrestricted
            }
        }
        Ty::Cap(_, mode, _, _) => mode.clone(),
    }
}

fn meta(kind: &str, description: &str) -> Node {
    Node {
        kind: "meta.node-kind".into(),
        ports: vec![],
        attrs: vec![
            ("description".into(), description.into()),
            ("name".into(), kind.into()),
        ],
    }
}

/// Verbatim port of `trellis-verify`'s `build_f0`, which itself mirrors
/// `Bootstrap.scala`'s initial semantic universe. `main.rs` asserts this
/// still hashes to [`F0_ROOT`] on every startup.
pub fn build_f0() -> Graph {
    let defs = [
        ("meta.node", "describes semantic node kinds"),
        ("meta.port", "describes typed ports"),
        ("meta.edge", "describes semantic edges"),
        ("meta.entity", "describes stable semantic entity lineage"),
        ("meta.mode", "describes unrestricted, affine, and linear structural modes"),
        ("core.move", "transfers an affine or linear capability"),
        ("core.borrow.shared", "derives a temporary read capability"),
        ("core.borrow.mut", "derives a temporary exclusive write capability"),
        ("core.drop", "deterministically consumes an affine resource"),
        ("core.replicate", "explicit contraction for unrestricted capabilities"),
        ("core.erase", "explicit weakening; affine values lower to drop"),
        ("core.hole", "typed incomplete subgraph boundary"),
        ("repo.change", "an immutable DeltaTrellis change"),
        ("repo.branch", "a materialized basis plus a local change frontier"),
        ("repo.frontier", "the maximal set of included changes defining a branch head"),
        ("machine.ceskr", "reference resource/process semantics"),
        ("projection.svg", "interactive graph projection"),
        ("projection.typst", "formal/document projection"),
    ];
    let mut g = Graph::default();
    for &(entity, description) in &defs {
        let id = add_node(&mut g, meta(entity, description));
        g.entities.insert(entity.into(), id);
    }
    let root = Node {
        kind: "repo.root".into(),
        ports: vec![],
        attrs: vec![
            ("name".into(), "trellis-bootstrap".into()),
            ("version".into(), "0.2".into()),
        ],
    };
    let root_id = add_node(&mut g, root);
    g.roots.insert("bootstrap".into(), root_id.clone());
    g.entities.insert("trellis.bootstrap".into(), root_id);
    g
}
