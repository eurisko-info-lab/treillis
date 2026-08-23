//! JSON serialization of a [`chain::ChainResult`] for the `/api/chain`
//! endpoint. Manual (no serde), matching the rest of this dependency-free
//! tool family.

use crate::chain::{ChainResult, Diff, EdgeSummary, EntitySummary, Layer, LayerStatus, OpSummary, RootSummary};
use crate::codec::Node;

fn json_string(s: &str, out: &mut String) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
}

fn json_opt_string(s: &Option<String>, out: &mut String) {
    match s {
        None => out.push_str("null"),
        Some(v) => json_string(v, out),
    }
}

fn json_string_array<'a, I: IntoIterator<Item = &'a String>>(items: I, out: &mut String) {
    out.push('[');
    for (i, s) in items.into_iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        json_string(s, out);
    }
    out.push(']');
}

fn json_node(n: &Node, content_id: &str, out: &mut String) {
    out.push_str("{\"contentId\":");
    json_string(content_id, out);
    out.push_str(",\"kind\":");
    json_string(&n.kind, out);
    out.push_str(",\"ports\":[");
    for (i, p) in n.ports.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str("{\"name\":");
        json_string(&p.name, out);
        out.push_str(",\"direction\":");
        json_string(p.direction.text(), out);
        out.push_str(",\"ty\":");
        json_string(&crate::codec::render_ty(&p.ty), out);
        out.push('}');
    }
    out.push_str("],\"attrs\":[");
    for (i, (k, v)) in n.attrs.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push('[');
        json_string(k, out);
        out.push(',');
        json_string(v, out);
        out.push(']');
    }
    out.push_str("]}");
}

fn json_op(op: &OpSummary, out: &mut String) {
    out.push_str("{\"op\":");
    json_string(op.op, out);
    if let Some(entity) = &op.entity {
        out.push_str(",\"entity\":");
        json_string(entity, out);
    }
    if let Some(name) = &op.name {
        out.push_str(",\"name\":");
        json_string(name, out);
    }
    if let Some(r) = &op.content_ref {
        out.push_str(",\"ref\":");
        json_string(r, out);
    }
    if let (Some(node), Some(cid)) = (&op.node, &op.node_content_id) {
        out.push_str(",\"node\":");
        json_node(node, cid, out);
    }
    if let (Some(edge), Some(cid)) = (&op.edge, &op.edge_content_id) {
        out.push_str(",\"edge\":{\"contentId\":");
        json_string(cid, out);
        out.push_str(",\"from\":{\"node\":");
        json_string(&edge.from.node, out);
        out.push_str(",\"port\":");
        json_string(&edge.from.port, out);
        out.push_str("},\"to\":{\"node\":");
        json_string(&edge.to.node, out);
        out.push_str(",\"port\":");
        json_string(&edge.to.port, out);
        out.push_str("},\"role\":");
        json_string(&edge.role, out);
        out.push('}');
    }
    out.push('}');
}

fn json_entity(e: &EntitySummary, out: &mut String) {
    out.push_str("{\"entity\":");
    json_string(&e.entity, out);
    out.push_str(",\"contentId\":");
    json_string(&e.content_id, out);
    out.push_str(",\"kind\":");
    json_string(&e.kind, out);
    out.push_str(",\"attrs\":[");
    for (i, (k, v)) in e.attrs.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push('[');
        json_string(k, out);
        out.push(',');
        json_string(v, out);
        out.push(']');
    }
    out.push_str("],\"ports\":[");
    for (i, p) in e.ports.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str("{\"name\":");
        json_string(&p.name, out);
        out.push_str(",\"direction\":");
        json_string(p.direction, out);
        out.push_str(",\"ty\":");
        json_string(&p.ty, out);
        out.push('}');
    }
    out.push_str("]}");
}

fn json_edge(e: &EdgeSummary, out: &mut String) {
    out.push_str("{\"contentId\":");
    json_string(&e.content_id, out);
    out.push_str(",\"fromNode\":");
    json_string(&e.from_node, out);
    out.push_str(",\"fromEntities\":");
    json_string_array(&e.from_entities, out);
    out.push_str(",\"fromPort\":");
    json_string(&e.from_port, out);
    out.push_str(",\"toNode\":");
    json_string(&e.to_node, out);
    out.push_str(",\"toEntities\":");
    json_string_array(&e.to_entities, out);
    out.push_str(",\"toPort\":");
    json_string(&e.to_port, out);
    out.push_str(",\"role\":");
    json_string(&e.role, out);
    out.push('}');
}

fn json_root(r: &RootSummary, out: &mut String) {
    out.push_str("{\"name\":");
    json_string(&r.name, out);
    out.push_str(",\"contentId\":");
    json_string(&r.content_id, out);
    out.push_str(",\"entities\":");
    json_string_array(&r.entities, out);
    out.push('}');
}

fn json_diff(d: &Diff, out: &mut String) {
    out.push_str("{\"addedEntities\":");
    json_string_array(&d.added_entities, out);
    out.push_str(",\"removedEntities\":");
    json_string_array(&d.removed_entities, out);
    out.push_str(",\"replacedEntities\":[");
    for (i, (entity, before, after)) in d.replaced_entities.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push('{');
        out.push_str("\"entity\":");
        json_string(entity, out);
        out.push_str(",\"before\":");
        json_string(before, out);
        out.push_str(",\"after\":");
        json_string(after, out);
        out.push('}');
    }
    out.push_str("],\"addedRoots\":");
    json_string_array(&d.added_roots, out);
    out.push_str(",\"removedRoots\":");
    json_string_array(&d.removed_roots, out);
    out.push('}');
}

fn json_layer(layer: &Layer, out: &mut String) {
    out.push_str("{\"index\":");
    out.push_str(&layer.index.to_string());
    out.push_str(",\"relativePath\":");
    json_opt_string(&layer.relative_path, out);
    out.push_str(",\"changeId\":");
    json_opt_string(&layer.change_id, out);
    out.push_str(",\"message\":");
    json_opt_string(&layer.message, out);
    out.push_str(",\"author\":");
    json_opt_string(&layer.author, out);
    out.push_str(",\"dependencies\":");
    json_string_array(&layer.dependencies, out);
    match &layer.status {
        LayerStatus::Ok => out.push_str(",\"status\":\"ok\",\"error\":null"),
        LayerStatus::Broken(e) => {
            out.push_str(",\"status\":\"broken\",\"error\":");
            json_string(e, out);
        }
    }
    out.push_str(",\"ops\":[");
    for (i, op) in layer.ops.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        json_op(op, out);
    }
    out.push_str("],\"graph\":{\"nodeCount\":");
    out.push_str(&layer.graph.node_count.to_string());
    out.push_str(",\"edgeCount\":");
    out.push_str(&layer.graph.edge_count.to_string());
    out.push_str(",\"entities\":[");
    for (i, e) in layer.graph.entities.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        json_entity(e, out);
    }
    out.push_str("],\"edges\":[");
    for (i, e) in layer.graph.edges.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        json_edge(e, out);
    }
    out.push_str("],\"roots\":[");
    for (i, r) in layer.graph.roots.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        json_root(r, out);
    }
    out.push_str("]},\"diff\":");
    json_diff(&layer.diff, out);
    out.push('}');
}

pub fn chain_json(result: &ChainResult) -> String {
    let mut out = String::new();
    out.push_str("{\"layers\":[");
    for (i, layer) in result.layers.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        json_layer(layer, &mut out);
    }
    out.push_str("],\"unparsed\":[");
    for (i, u) in result.unparsed.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str("{\"relativePath\":");
        json_string(&u.relative_path, &mut out);
        out.push_str(",\"error\":");
        json_string(&u.error, &mut out);
        out.push('}');
    }
    out.push_str("],\"unlinked\":[");
    for (i, u) in result.unlinked.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str("{\"relativePath\":");
        json_string(&u.relative_path, &mut out);
        out.push_str(",\"changeId\":");
        json_string(&u.change_id, &mut out);
        out.push_str(",\"message\":");
        json_string(&u.message, &mut out);
        out.push_str(",\"dependencies\":");
        json_string_array(&u.dependencies, &mut out);
        out.push_str(",\"reason\":");
        json_string(&u.reason, &mut out);
        out.push('}');
    }
    out.push_str("]}");
    out
}
