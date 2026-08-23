//! delta-inspect: a standalone reader/decipherer for trellis `*.delta` files.
//!
//! `*.delta` files hold a DeltaTrellis `Change` record encoded in the same
//! zero-delimiter canonical format used across trellis-bootstrap (see
//! `src/main/scala/trellis/Canon.scala` and `Delta.scala`): every field is
//! `<utf8-byte-length>:<payload>`, and nested records are payloads that are
//! themselves atom sequences. This tool re-implements that codec from
//! scratch (no shared crate, no dependencies -- matching the spirit of the
//! existing `trellis-verify` independent verifier) and adds a human-readable
//! pretty printer, a JSON printer, and cross-referencing between operations
//! (so a `op.bind-entity` hash is annotated with the `op.add-node` that
//! produced it, when that node is defined earlier in the same file).

use std::collections::BTreeMap;
use std::env;
use std::fs;
use std::io::Read;
use std::path::PathBuf;
use std::process::ExitCode;

// ---------------------------------------------------------------------
// Canonical atom framing (mirrors trellis.Canon in the Scala sources).
// ---------------------------------------------------------------------

fn atom(s: &str) -> String {
    format!("{}:{}", s.as_bytes().len(), s)
}

fn record<'a, I: IntoIterator<Item = &'a str>>(tag: &str, fields: I) -> String {
    let mut out = atom(tag);
    for f in fields {
        out.push_str(&atom(f));
    }
    out
}

fn record_owned(tag: &str, fields: &[String]) -> String {
    record(tag, fields.iter().map(String::as_str))
}

/// Splits a byte string into the atoms it is made of. Fails on anything that
/// is not an exact, minimal-length-prefixed, valid-UTF-8 atom sequence --
/// this mirrors `Canon.splitAtoms`'s strictness exactly.
fn split_atoms(bytes: &[u8]) -> Result<Vec<String>, String> {
    let mut out = Vec::new();
    let mut offset = 0usize;
    while offset < bytes.len() {
        let start = offset;
        while offset < bytes.len() && bytes[offset].is_ascii_digit() {
            offset += 1;
        }
        if start == offset {
            return Err(format!("expected atom length at byte {offset}"));
        }
        if offset >= bytes.len() || bytes[offset] != b':' {
            return Err(format!("expected ':' after atom length at byte {offset}"));
        }
        let length_text = std::str::from_utf8(&bytes[start..offset])
            .map_err(|e| format!("invalid atom length UTF-8: {e}"))?;
        if length_text.len() > 1 && length_text.starts_with('0') {
            return Err(format!("non-canonical atom length: {length_text}"));
        }
        let length: usize = length_text
            .parse()
            .map_err(|_| format!("invalid atom length: {length_text}"))?;
        offset += 1;
        if length > bytes.len().saturating_sub(offset) {
            return Err(format!(
                "atom length {length} exceeds remaining input at byte {offset}"
            ));
        }
        let payload = &bytes[offset..offset + length];
        let value =
            std::str::from_utf8(payload).map_err(|e| format!("invalid UTF-8: {e}"))?;
        out.push(value.to_owned());
        offset += length;
    }
    Ok(out)
}

fn tag_and_fields(encoded: &str) -> Result<(String, Vec<String>), String> {
    let values = split_atoms(encoded.as_bytes())?;
    if values.is_empty() {
        Err("empty canonical record".into())
    } else {
        Ok((values[0].clone(), values[1..].to_vec()))
    }
}

fn fields(encoded: &str, expected: &str) -> Result<Vec<String>, String> {
    let (tag, values) = tag_and_fields(encoded)?;
    if tag == expected {
        Ok(values)
    } else {
        Err(format!("expected {expected} record, found {tag}"))
    }
}

fn fixed(encoded: &str, expected: &str, arity: usize) -> Result<Vec<String>, String> {
    let values = fields(encoded, expected)?;
    if values.len() == arity {
        Ok(values)
    } else {
        Err(format!(
            "{expected} record has {} fields; expected {arity}",
            values.len()
        ))
    }
}

fn is_hash(s: &str) -> bool {
    s.len() == 64 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}
fn require_hash(s: &str, label: &str) -> Result<(), String> {
    if is_hash(s) {
        Ok(())
    } else {
        Err(format!("malformed {label}: {s}"))
    }
}
fn require_nonempty(s: &str, label: &str) -> Result<(), String> {
    if s.is_empty() {
        Err(format!("empty {label}"))
    } else {
        Ok(())
    }
}

// ---------------------------------------------------------------------
// Model (mirrors trellis.Core / trellis.Delta).
// ---------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq)]
enum Mode {
    Unrestricted,
    Affine,
    Linear,
}
impl Mode {
    fn text(&self) -> &'static str {
        match self {
            Self::Unrestricted => "unrestricted",
            Self::Affine => "affine",
            Self::Linear => "linear",
        }
    }
}
fn decode_mode(s: &str) -> Result<Mode, String> {
    match s {
        "unrestricted" => Ok(Mode::Unrestricted),
        "affine" => Ok(Mode::Affine),
        "linear" => Ok(Mode::Linear),
        other => Err(format!("unknown mode: {other}")),
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum Direction {
    In,
    Out,
}
impl Direction {
    fn text(&self) -> &'static str {
        match self {
            Self::In => "in",
            Self::Out => "out",
        }
    }
}
fn decode_direction(s: &str) -> Result<Direction, String> {
    match s {
        "in" => Ok(Direction::In),
        "out" => Ok(Direction::Out),
        other => Err(format!("unknown direction: {other}")),
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum Capability {
    Pure,
    Own,
    Read,
    Write,
    Suspended,
    Send,
    Recv,
    Session,
    Region,
    Effect,
    Process,
    Meta,
}
impl Capability {
    fn text(&self) -> &'static str {
        match self {
            Self::Pure => "pure",
            Self::Own => "own",
            Self::Read => "read",
            Self::Write => "write",
            Self::Suspended => "suspended",
            Self::Send => "send",
            Self::Recv => "recv",
            Self::Session => "session",
            Self::Region => "region",
            Self::Effect => "effect",
            Self::Process => "process",
            Self::Meta => "meta",
        }
    }
}
fn decode_capability(s: &str) -> Result<Capability, String> {
    match s {
        "pure" => Ok(Capability::Pure),
        "own" => Ok(Capability::Own),
        "read" => Ok(Capability::Read),
        "write" => Ok(Capability::Write),
        "suspended" => Ok(Capability::Suspended),
        "send" => Ok(Capability::Send),
        "recv" => Ok(Capability::Recv),
        "session" => Ok(Capability::Session),
        "region" => Ok(Capability::Region),
        "effect" => Ok(Capability::Effect),
        "process" => Ok(Capability::Process),
        "meta" => Ok(Capability::Meta),
        other => Err(format!("unknown capability: {other}")),
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum Ty {
    Atom(String),
    Tuple(Vec<Ty>),
    Cap(Capability, Mode, Box<Ty>, Option<String>),
}

fn encode_ty(t: &Ty) -> String {
    match t {
        Ty::Atom(name) => record("atom", [name.as_str()]),
        Ty::Tuple(items) => {
            let vals: Vec<String> = items.iter().map(encode_ty).collect();
            record_owned("tuple", &vals)
        }
        Ty::Cap(kind, mode, inner, state) => {
            let state = match state {
                None => record("none", std::iter::empty::<&str>()),
                Some(v) => record("some", [v.as_str()]),
            };
            let inner = encode_ty(inner);
            record("cap", [kind.text(), mode.text(), inner.as_str(), state.as_str()])
        }
    }
}

fn decode_option(encoded: &str) -> Result<Option<String>, String> {
    let (tag, vals) = tag_and_fields(encoded)?;
    match (tag.as_str(), vals.as_slice()) {
        ("none", []) => Ok(None),
        ("some", [v]) => Ok(Some(v.clone())),
        _ => Err(format!("invalid option record: {tag}")),
    }
}

fn decode_ty(encoded: &str) -> Result<Ty, String> {
    let (tag, vals) = tag_and_fields(encoded)?;
    match (tag.as_str(), vals.as_slice()) {
        ("atom", [name]) => {
            require_nonempty(name, "atom type name")?;
            Ok(Ty::Atom(name.clone()))
        }
        ("tuple", items) => Ok(Ty::Tuple(
            items.iter().map(|x| decode_ty(x)).collect::<Result<_, _>>()?,
        )),
        ("cap", [kind, mode, inner, state]) => Ok(Ty::Cap(
            decode_capability(kind)?,
            decode_mode(mode)?,
            Box::new(decode_ty(inner)?),
            decode_option(state)?,
        )),
        _ => Err(format!("invalid type record: {tag}")),
    }
}

/// Compact, readable rendering of a `Ty` -- not part of the wire format,
/// just how this tool displays one on a single line.
fn render_ty(t: &Ty) -> String {
    match t {
        Ty::Atom(name) => name.clone(),
        Ty::Tuple(items) => format!(
            "({})",
            items.iter().map(render_ty).collect::<Vec<_>>().join(", ")
        ),
        Ty::Cap(kind, mode, inner, state) => {
            let base = format!("{} {} {}", kind.text(), mode.text(), render_ty(inner));
            match state {
                None => base,
                Some(s) => format!("{base} @{s}"),
            }
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Port {
    name: String,
    direction: Direction,
    ty: Ty,
}
fn encode_port(p: &Port) -> String {
    let ty = encode_ty(&p.ty);
    record("port", [p.name.as_str(), p.direction.text(), ty.as_str()])
}
fn decode_port(encoded: &str) -> Result<Port, String> {
    let p = fixed(encoded, "port", 3)?;
    require_nonempty(&p[0], "port name")?;
    Ok(Port {
        name: p[0].clone(),
        direction: decode_direction(&p[1])?,
        ty: decode_ty(&p[2])?,
    })
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Node {
    kind: String,
    ports: Vec<Port>,
    attrs: Vec<(String, String)>,
}
fn encode_node(n: &Node) -> String {
    let ports: Vec<String> = n.ports.iter().map(encode_port).collect();
    let attrs: Vec<String> = n
        .attrs
        .iter()
        .map(|(k, v)| record("attr", [k.as_str(), v.as_str()]))
        .collect();
    let ports = record_owned("ports", &ports);
    let attrs = record_owned("attrs", &attrs);
    record("node", [n.kind.as_str(), ports.as_str(), attrs.as_str()])
}
fn decode_node(encoded: &str) -> Result<Node, String> {
    let p = fixed(encoded, "node", 3)?;
    require_nonempty(&p[0], "node kind")?;
    let port_texts = fields(&p[1], "ports")?;
    let ports: Vec<Port> = port_texts
        .iter()
        .map(|x| decode_port(x))
        .collect::<Result<_, _>>()?;
    let mut seen_ports = std::collections::BTreeSet::new();
    for port in &ports {
        if !seen_ports.insert(port.name.clone()) {
            return Err(format!("duplicate port name: {}", port.name));
        }
    }
    let attr_texts = fields(&p[2], "attrs")?;
    let mut attrs = Vec::new();
    let mut seen_keys = std::collections::BTreeSet::new();
    let mut last: Option<String> = None;
    for item in attr_texts {
        let a = fixed(&item, "attr", 2)?;
        require_nonempty(&a[0], "attribute key")?;
        if !seen_keys.insert(a[0].clone()) {
            return Err(format!("duplicate attributes key: {}", a[0]));
        }
        if let Some(prev) = &last {
            if a[0].as_str() < prev.as_str() {
                return Err("attributes are not in canonical key order".into());
            }
        }
        last = Some(a[0].clone());
        attrs.push((a[0].clone(), a[1].clone()));
    }
    Ok(Node {
        kind: p[0].clone(),
        ports,
        attrs,
    })
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct PortRef {
    node: String,
    port: String,
}
fn encode_port_ref(r: &PortRef) -> String {
    record("ref", [r.node.as_str(), r.port.as_str()])
}
fn decode_port_ref(encoded: &str) -> Result<PortRef, String> {
    let p = fixed(encoded, "ref", 2)?;
    require_hash(&p[0], "port reference ContentId")?;
    require_nonempty(&p[1], "port reference name")?;
    Ok(PortRef {
        node: p[0].clone(),
        port: p[1].clone(),
    })
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Edge {
    from: PortRef,
    to: PortRef,
    role: String,
}
fn encode_edge(e: &Edge) -> String {
    let from = encode_port_ref(&e.from);
    let to = encode_port_ref(&e.to);
    record("edge", [from.as_str(), to.as_str(), e.role.as_str()])
}
fn decode_edge(encoded: &str) -> Result<Edge, String> {
    let p = fixed(encoded, "edge", 3)?;
    require_nonempty(&p[2], "edge role")?;
    Ok(Edge {
        from: decode_port_ref(&p[0])?,
        to: decode_port_ref(&p[1])?,
        role: p[2].clone(),
    })
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum Op {
    AddNode(Node),
    BindEntity(String, String),
    ReplaceEntity(String, Node),
    RemoveEntity(String),
    Connect(Edge),
    Disconnect(String),
    AddRoot(String, String),
    RemoveRoot(String),
    RefineHole(String, Node),
}
fn decode_op(encoded: &str) -> Result<Op, String> {
    let (tag, v) = tag_and_fields(encoded)?;
    match (tag.as_str(), v.as_slice()) {
        ("op.add-node", [n]) => Ok(Op::AddNode(decode_node(n)?)),
        ("op.bind-entity", [e, n]) => {
            require_nonempty(e, "entity id")?;
            require_hash(n, "bound node ContentId")?;
            Ok(Op::BindEntity(e.clone(), n.clone()))
        }
        ("op.replace-entity", [e, n]) => {
            require_nonempty(e, "entity id")?;
            Ok(Op::ReplaceEntity(e.clone(), decode_node(n)?))
        }
        ("op.remove-entity", [e]) => {
            require_nonempty(e, "entity id")?;
            Ok(Op::RemoveEntity(e.clone()))
        }
        ("op.connect", [e]) => Ok(Op::Connect(decode_edge(e)?)),
        ("op.disconnect", [e]) => {
            require_hash(e, "disconnected edge ContentId")?;
            Ok(Op::Disconnect(e.clone()))
        }
        ("op.add-root", [name, node]) => {
            require_nonempty(name, "root name")?;
            require_hash(node, "root node ContentId")?;
            Ok(Op::AddRoot(name.clone(), node.clone()))
        }
        ("op.remove-root", [name]) => {
            require_nonempty(name, "root name")?;
            Ok(Op::RemoveRoot(name.clone()))
        }
        ("op.refine-hole", [e, n]) => {
            require_nonempty(e, "hole entity id")?;
            Ok(Op::RefineHole(e.clone(), decode_node(n)?))
        }
        _ => Err(format!("invalid DeltaTrellis operation record: {tag}")),
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Change {
    dependencies: Vec<String>,
    operations: Vec<Op>,
    message: String,
    author: String,
}
fn encode_op(op: &Op) -> String {
    match op {
        Op::AddNode(n) => {
            let n = encode_node(n);
            record("op.add-node", [n.as_str()])
        }
        Op::BindEntity(e, n) => record("op.bind-entity", [e.as_str(), n.as_str()]),
        Op::ReplaceEntity(e, n) => {
            let n = encode_node(n);
            record("op.replace-entity", [e.as_str(), n.as_str()])
        }
        Op::RemoveEntity(e) => record("op.remove-entity", [e.as_str()]),
        Op::Connect(e) => {
            let e = encode_edge(e);
            record("op.connect", [e.as_str()])
        }
        Op::Disconnect(e) => record("op.disconnect", [e.as_str()]),
        Op::AddRoot(name, node) => record("op.add-root", [name.as_str(), node.as_str()]),
        Op::RemoveRoot(name) => record("op.remove-root", [name.as_str()]),
        Op::RefineHole(e, n) => {
            let n = encode_node(n);
            record("op.refine-hole", [e.as_str(), n.as_str()])
        }
    }
}
fn encode_change(c: &Change) -> String {
    let ops: Vec<String> = c.operations.iter().map(encode_op).collect();
    let deps = record_owned("dependencies", &c.dependencies);
    let ops = record_owned("operations", &ops);
    record("change", [deps.as_str(), ops.as_str(), c.message.as_str(), c.author.as_str()])
}

fn decode_change_unchecked(text: &str) -> Result<Change, String> {
    let p = fixed(text, "change", 4)?;
    require_nonempty(&p[2], "change message")?;
    require_nonempty(&p[3], "change author")?;
    let dep_texts = fields(&p[0], "dependencies")?;
    let mut deps = Vec::new();
    let mut seen = std::collections::BTreeSet::new();
    let mut last: Option<String> = None;
    for dep in dep_texts {
        require_hash(&dep, "change dependency")?;
        if let Some(prev) = &last {
            if dep.as_str() < prev.as_str() {
                return Err("change dependencies are not in canonical order".into());
            }
        }
        if !seen.insert(dep.clone()) {
            return Err(format!("duplicate change dependency: {dep}"));
        }
        last = Some(dep.clone());
        deps.push(dep);
    }
    let operations = fields(&p[1], "operations")?
        .iter()
        .map(|x| decode_op(x))
        .collect::<Result<_, _>>()?;
    Ok(Change {
        dependencies: deps,
        operations,
        message: p[2].clone(),
        author: p[3].clone(),
    })
}

/// Strict decode: also requires the input to be the exact canonical encoding
/// of the decoded value (round-trips byte-for-byte), matching `Delta.decodeChange`.
fn decode_change_bytes(bytes: &[u8], strict: bool) -> Result<Change, String> {
    let text = std::str::from_utf8(bytes).map_err(|e| format!("invalid UTF-8: {e}"))?;
    let c = decode_change_unchecked(text)?;
    if !strict || encode_change(&c).as_bytes() == bytes {
        Ok(c)
    } else {
        Err("decoded change does not round-trip to identical canonical bytes (non-canonical encoding)".into())
    }
}

fn node_id(n: &Node) -> String {
    sha256_hex(encode_node(n).as_bytes())
}
fn edge_id(e: &Edge) -> String {
    sha256_hex(encode_edge(e).as_bytes())
}
fn change_id(c: &Change) -> String {
    sha256_hex(encode_change(c).as_bytes())
}

// ---------------------------------------------------------------------
// Pretty printing.
// ---------------------------------------------------------------------

struct Origin {
    label: String,
}

struct Origins {
    nodes: BTreeMap<String, Origin>,
    edges: BTreeMap<String, Origin>,
}

fn compute_origins(change: &Change) -> Origins {
    let mut nodes = BTreeMap::new();
    let mut edges = BTreeMap::new();
    for (i, op) in change.operations.iter().enumerate() {
        let op_index = i + 1;
        match op {
            Op::AddNode(n) => {
                nodes.insert(
                    node_id(n),
                    Origin { label: format!("op {op_index} add-node ({})", n.kind) },
                );
            }
            Op::ReplaceEntity(entity, n) => {
                nodes.insert(
                    node_id(n),
                    Origin {
                        label: format!("op {op_index} replace-entity {entity} ({})", n.kind),
                    },
                );
            }
            Op::RefineHole(entity, n) => {
                nodes.insert(
                    node_id(n),
                    Origin {
                        label: format!("op {op_index} refine-hole {entity} ({})", n.kind),
                    },
                );
            }
            Op::Connect(e) => {
                edges.insert(
                    edge_id(e),
                    Origin { label: format!("op {op_index} connect") },
                );
            }
            _ => {}
        }
    }
    Origins { nodes, edges }
}

fn short_hash(h: &str, full: bool) -> String {
    if full || h.len() <= 12 {
        h.to_string()
    } else {
        format!("{}\u{2026}", &h[..12])
    }
}

fn annotate_node(hash: &str, origins: &Origins, full: bool) -> String {
    match origins.nodes.get(hash) {
        Some(o) => format!("{}  [{}]", short_hash(hash, full), o.label),
        None => short_hash(hash, full),
    }
}

fn annotate_edge(hash: &str, origins: &Origins, full: bool) -> String {
    match origins.edges.get(hash) {
        Some(o) => format!("{}  [{}]", short_hash(hash, full), o.label),
        None => short_hash(hash, full),
    }
}

fn print_node(n: &Node, indent: &str, out: &mut String) {
    out.push_str(&format!("{indent}node: {}\n", n.kind));
    if n.ports.is_empty() {
        out.push_str(&format!("{indent}  ports: (none)\n"));
    } else {
        out.push_str(&format!("{indent}  ports:\n"));
        for p in &n.ports {
            out.push_str(&format!(
                "{indent}    {} {} : {}\n",
                p.name,
                p.direction.text(),
                render_ty(&p.ty)
            ));
        }
    }
    if n.attrs.is_empty() {
        out.push_str(&format!("{indent}  attrs: (none)\n"));
    } else {
        out.push_str(&format!("{indent}  attrs:\n"));
        for (k, v) in &n.attrs {
            out.push_str(&format!("{indent}    {k} = {v}\n"));
        }
    }
}

fn print_change(change: &Change, cid: &str, canonical: bool, full_hashes: bool) -> String {
    let origins = compute_origins(change);
    let mut out = String::new();
    out.push_str(&format!(
        "change id: {}{}\n",
        cid,
        if canonical { " (canonical encoding)" } else { " (WARNING: non-canonical encoding)" }
    ));
    out.push_str(&format!("author:  {}\n", change.author));
    out.push_str(&format!("message: {}\n", change.message));
    if change.dependencies.is_empty() {
        out.push_str("dependencies: (none)\n");
    } else {
        out.push_str(&format!("dependencies ({}):\n", change.dependencies.len()));
        for d in &change.dependencies {
            out.push_str(&format!("  - {}\n", short_hash(d, full_hashes)));
        }
    }
    out.push_str(&format!("operations ({}):\n", change.operations.len()));
    for (i, op) in change.operations.iter().enumerate() {
        let n = i + 1;
        match op {
            Op::AddNode(node) => {
                let id = node_id(node);
                out.push_str(&format!("  {n}. add-node -> {}\n", short_hash(&id, full_hashes)));
                print_node(node, "       ", &mut out);
            }
            Op::BindEntity(entity, node) => {
                out.push_str(&format!(
                    "  {n}. bind-entity {entity} -> {}\n",
                    annotate_node(node, &origins, full_hashes)
                ));
            }
            Op::ReplaceEntity(entity, node) => {
                let id = node_id(node);
                out.push_str(&format!(
                    "  {n}. replace-entity {entity} -> {}\n",
                    short_hash(&id, full_hashes)
                ));
                print_node(node, "       ", &mut out);
            }
            Op::RemoveEntity(entity) => {
                out.push_str(&format!("  {n}. remove-entity {entity}\n"));
            }
            Op::Connect(edge) => {
                let id = edge_id(edge);
                out.push_str(&format!("  {n}. connect -> {}\n", short_hash(&id, full_hashes)));
                out.push_str(&format!(
                    "       from: {}.{}\n",
                    annotate_node(&edge.from.node, &origins, full_hashes),
                    edge.from.port
                ));
                out.push_str(&format!(
                    "       to:   {}.{}\n",
                    annotate_node(&edge.to.node, &origins, full_hashes),
                    edge.to.port
                ));
                out.push_str(&format!("       role: {}\n", edge.role));
            }
            Op::Disconnect(edge) => {
                out.push_str(&format!(
                    "  {n}. disconnect {}\n",
                    annotate_edge(edge, &origins, full_hashes)
                ));
            }
            Op::AddRoot(name, node) => {
                out.push_str(&format!(
                    "  {n}. add-root {name} -> {}\n",
                    annotate_node(node, &origins, full_hashes)
                ));
            }
            Op::RemoveRoot(name) => {
                out.push_str(&format!("  {n}. remove-root {name}\n"));
            }
            Op::RefineHole(entity, node) => {
                let id = node_id(node);
                out.push_str(&format!(
                    "  {n}. refine-hole {entity} -> {}\n",
                    short_hash(&id, full_hashes)
                ));
                print_node(node, "       ", &mut out);
            }
        }
    }
    out
}

// ---------------------------------------------------------------------
// JSON printing (manual -- keeping this tool dependency-free).
// ---------------------------------------------------------------------

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

fn json_ty(t: &Ty, out: &mut String) {
    match t {
        Ty::Atom(name) => {
            out.push_str("{\"kind\":\"atom\",\"name\":");
            json_string(name, out);
            out.push('}');
        }
        Ty::Tuple(items) => {
            out.push_str("{\"kind\":\"tuple\",\"items\":[");
            for (i, it) in items.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                json_ty(it, out);
            }
            out.push_str("]}");
        }
        Ty::Cap(kind, mode, inner, state) => {
            out.push_str("{\"kind\":\"cap\",\"capability\":");
            json_string(kind.text(), out);
            out.push_str(",\"mode\":");
            json_string(mode.text(), out);
            out.push_str(",\"inner\":");
            json_ty(inner, out);
            out.push_str(",\"state\":");
            match state {
                None => out.push_str("null"),
                Some(s) => json_string(s, out),
            }
            out.push('}');
        }
    }
}

fn json_node(n: &Node, out: &mut String) {
    out.push_str("{\"kind\":");
    json_string(&n.kind, out);
    out.push_str(",\"id\":");
    json_string(&node_id(n), out);
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
        json_ty(&p.ty, out);
        out.push('}');
    }
    out.push_str("],\"attrs\":{");
    for (i, (k, v)) in n.attrs.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        json_string(k, out);
        out.push(':');
        json_string(v, out);
    }
    out.push_str("}}");
}

fn json_port_ref(r: &PortRef, out: &mut String) {
    out.push_str("{\"node\":");
    json_string(&r.node, out);
    out.push_str(",\"port\":");
    json_string(&r.port, out);
    out.push('}');
}

fn json_edge(e: &Edge, out: &mut String) {
    out.push_str("{\"id\":");
    json_string(&edge_id(e), out);
    out.push_str(",\"from\":");
    json_port_ref(&e.from, out);
    out.push_str(",\"to\":");
    json_port_ref(&e.to, out);
    out.push_str(",\"role\":");
    json_string(&e.role, out);
    out.push('}');
}

fn json_op(op: &Op, out: &mut String) {
    match op {
        Op::AddNode(n) => {
            out.push_str("{\"op\":\"add-node\",\"node\":");
            json_node(n, out);
            out.push('}');
        }
        Op::BindEntity(e, n) => {
            out.push_str("{\"op\":\"bind-entity\",\"entity\":");
            json_string(e, out);
            out.push_str(",\"node\":");
            json_string(n, out);
            out.push('}');
        }
        Op::ReplaceEntity(e, n) => {
            out.push_str("{\"op\":\"replace-entity\",\"entity\":");
            json_string(e, out);
            out.push_str(",\"node\":");
            json_node(n, out);
            out.push('}');
        }
        Op::RemoveEntity(e) => {
            out.push_str("{\"op\":\"remove-entity\",\"entity\":");
            json_string(e, out);
            out.push('}');
        }
        Op::Connect(e) => {
            out.push_str("{\"op\":\"connect\",\"edge\":");
            json_edge(e, out);
            out.push('}');
        }
        Op::Disconnect(e) => {
            out.push_str("{\"op\":\"disconnect\",\"edge\":");
            json_string(e, out);
            out.push('}');
        }
        Op::AddRoot(name, node) => {
            out.push_str("{\"op\":\"add-root\",\"name\":");
            json_string(name, out);
            out.push_str(",\"node\":");
            json_string(node, out);
            out.push('}');
        }
        Op::RemoveRoot(name) => {
            out.push_str("{\"op\":\"remove-root\",\"name\":");
            json_string(name, out);
            out.push('}');
        }
        Op::RefineHole(e, n) => {
            out.push_str("{\"op\":\"refine-hole\",\"entity\":");
            json_string(e, out);
            out.push_str(",\"node\":");
            json_node(n, out);
            out.push('}');
        }
    }
}

fn json_change(change: &Change, cid: &str, canonical: bool) -> String {
    let mut out = String::new();
    out.push_str("{\"changeId\":");
    json_string(cid, &mut out);
    out.push_str(",\"canonical\":");
    out.push_str(if canonical { "true" } else { "false" });
    out.push_str(",\"author\":");
    json_string(&change.author, &mut out);
    out.push_str(",\"message\":");
    json_string(&change.message, &mut out);
    out.push_str(",\"dependencies\":[");
    for (i, d) in change.dependencies.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        json_string(d, &mut out);
    }
    out.push_str("],\"operations\":[");
    for (i, op) in change.operations.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        json_op(op, &mut out);
    }
    out.push_str("]}");
    out
}

// ---------------------------------------------------------------------
// Raw / heuristic dump for files that don't decode as a Change record
// (e.g. other Canon-framed content such as an encoded Graph snapshot).
// ---------------------------------------------------------------------

fn raw_dump_value(value: &str, indent: usize, out: &mut String) {
    let pad = "  ".repeat(indent);
    match split_atoms(value.as_bytes()) {
        Ok(items) if !items.is_empty() => {
            let tag = &items[0];
            out.push_str(&format!("{pad}{tag}\n"));
            for field in &items[1..] {
                raw_dump_value(field, indent + 1, out);
            }
        }
        _ => {
            out.push_str(&format!("{pad}{value:?}\n"));
        }
    }
}

fn raw_dump(bytes: &[u8]) -> Result<String, String> {
    let text = std::str::from_utf8(bytes).map_err(|e| format!("invalid UTF-8: {e}"))?;
    let mut out = String::new();
    raw_dump_value(text, 0, &mut out);
    Ok(out)
}

// ---------------------------------------------------------------------
// sha256 (self-contained, no crate -- this tool must not trust an
// out-of-band hashing implementation any more than the codec itself).
// ---------------------------------------------------------------------

fn sha256_hex(data: &[u8]) -> String {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];
    let mut h = [
        0x6a09e667u32,
        0xbb67ae85,
        0x3c6ef372,
        0xa54ff53a,
        0x510e527f,
        0x9b05688c,
        0x1f83d9ab,
        0x5be0cd19,
    ];
    let bit_len = (data.len() as u64) * 8;
    let mut msg = data.to_vec();
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bit_len.to_be_bytes());
    for chunk in msg.chunks_exact(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes(chunk[i * 4..i * 4 + 4].try_into().unwrap());
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let (mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut hh) =
            (h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7]);
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let t1 = hh
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            hh = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }
        h[0] = h[0].wrapping_add(a);
        h[1] = h[1].wrapping_add(b);
        h[2] = h[2].wrapping_add(c);
        h[3] = h[3].wrapping_add(d);
        h[4] = h[4].wrapping_add(e);
        h[5] = h[5].wrapping_add(f);
        h[6] = h[6].wrapping_add(g);
        h[7] = h[7].wrapping_add(hh);
    }
    h.iter().map(|x| format!("{x:08x}")).collect()
}

// ---------------------------------------------------------------------
// CLI.
// ---------------------------------------------------------------------

struct Options {
    files: Vec<String>,
    raw: bool,
    json: bool,
    strict: bool,
    id_only: bool,
    full_hashes: bool,
}

fn print_help() {
    eprintln!(
        "delta-inspect: read and decipher trellis *.delta files\n\n\
         USAGE:\n    delta-inspect [OPTIONS] <FILE>...\n\n\
         Pass '-' for a file to read a single change from stdin.\n\n\
         OPTIONS:\n\
         \x20   --raw            Dump the raw canonical record tree instead of the\n\
         \x20                    typed Change decoder. Works on any Canon-framed file\n\
         \x20                    (e.g. an encoded Graph snapshot), not just changes.\n\
         \x20                    Uses a best-effort heuristic to tell nested records\n\
         \x20                    apart from leaf strings, so treat it as a diagnostic.\n\
         \x20   --json           Emit JSON instead of the human-readable report.\n\
         \x20   --no-strict      Accept a decodable-but-non-canonical encoding instead\n\
         \x20                    of rejecting it (matches Delta.decodeChangeUnchecked\n\
         \x20                    rather than Delta.decodeChange).\n\
         \x20   --id-only        Print just the computed change id per file.\n\
         \x20   --full-hashes    Show full 64-hex-char hashes instead of a 12-char\n\
         \x20                    short form.\n\
         \x20   -h, --help       Show this help.\n"
    );
}

fn parse_args() -> Result<Options, String> {
    let mut files = Vec::new();
    let mut raw = false;
    let mut json = false;
    let mut strict = true;
    let mut id_only = false;
    let mut full_hashes = false;
    for arg in env::args().skip(1) {
        match arg.as_str() {
            "--raw" => raw = true,
            "--json" => json = true,
            "--no-strict" => strict = false,
            "--id-only" => id_only = true,
            "--full-hashes" => full_hashes = true,
            "-h" | "--help" => {
                print_help();
                std::process::exit(0);
            }
            other => files.push(other.to_string()),
        }
    }
    if files.is_empty() {
        return Err("no input files given (pass a *.delta path, or '-' for stdin)".into());
    }
    Ok(Options { files, raw, json, strict, id_only, full_hashes })
}

fn read_input(path: &str) -> Result<Vec<u8>, String> {
    if path == "-" {
        let mut buf = Vec::new();
        std::io::stdin()
            .read_to_end(&mut buf)
            .map_err(|e| format!("cannot read stdin: {e}"))?;
        Ok(buf)
    } else {
        fs::read(PathBuf::from(path)).map_err(|e| format!("cannot read {path}: {e}"))
    }
}

fn run() -> bool {
    let opts = match parse_args() {
        Ok(o) => o,
        Err(e) => {
            eprintln!("error: {e}\n");
            print_help();
            std::process::exit(2);
        }
    };

    let mut all_ok = true;
    for (i, path) in opts.files.iter().enumerate() {
        if i > 0 && !opts.json && !opts.id_only {
            println!();
        }
        if opts.files.len() > 1 && !opts.id_only {
            println!("=== {path} ===");
        }

        let bytes = match read_input(path) {
            Ok(b) => b,
            Err(e) => {
                eprintln!("{path}: {e}");
                all_ok = false;
                continue;
            }
        };

        if bytes.starts_with(b"delta-set ") || bytes.starts_with(b"delta-package ") {
            match std::str::from_utf8(&bytes) {
                Ok(source) if source.lines().skip(1).all(|line| {
                    let line = line.trim();
                    line.is_empty()
                        || line.starts_with("purpose ")
                        || line.starts_with("include ")
                        || line.starts_with("post-action ")
                        || line.starts_with("provides ")
                        || line.starts_with("requires ")
                        || line.starts_with("imports ")
                        || line.starts_with("conflicts ")
                        || line.starts_with("change ")
                        || line.starts_with("entity ")
                        || line.starts_with("attr ")
                }) => {
                    if opts.json {
                        let escaped = source.replace('\\', "\\\\").replace('"', "\\\"").replace('\n', "\\n");
                        println!("{{\"kind\":\"delta-set\",\"source\":\"{escaped}\"}}");
                    } else if opts.id_only {
                        println!("delta-set  {path}");
                    } else {
                        print!("{source}");
                    }
                }
                Ok(_) => { eprintln!("{path}: malformed delta-set directive"); all_ok = false; }
                Err(e) => { eprintln!("{path}: delta-set is not UTF-8: {e}"); all_ok = false; }
            }
            continue;
        }

        if opts.raw {
            match raw_dump(&bytes) {
                Ok(text) => print!("{text}"),
                Err(e) => {
                    eprintln!("{path}: {e}");
                    all_ok = false;
                }
            }
            continue;
        }

        let strict_result = decode_change_bytes(&bytes, true);
        let (change, canonical) = match strict_result {
            Ok(c) => (Some(c), true),
            Err(strict_err) => {
                if opts.strict {
                    eprintln!("{path}: {strict_err}");
                    eprintln!("{path}: hint: re-run with --raw to see the raw record tree, or --no-strict to accept non-canonical encodings");
                    all_ok = false;
                    (None, false)
                } else {
                    match std::str::from_utf8(&bytes)
                        .map_err(|e| format!("invalid UTF-8: {e}"))
                        .and_then(|text| decode_change_unchecked(text))
                    {
                        Ok(c) => (Some(c), false),
                        Err(e) => {
                            eprintln!("{path}: {e}");
                            all_ok = false;
                            (None, false)
                        }
                    }
                }
            }
        };

        let Some(change) = change else { continue };
        let cid = change_id(&change);

        if opts.id_only {
            println!("{}  {}", cid, path);
            continue;
        }

        if opts.json {
            println!("{}", json_change(&change, &cid, canonical));
        } else {
            print!("{}", print_change(&change, &cid, canonical, opts.full_hashes));
        }
    }
    all_ok
}

/// Rust sets SIGPIPE to SIG_IGN on startup, which turns "downstream closed
/// the pipe" (e.g. piping into `head`) into a panic from `println!` instead
/// of the usual quiet process exit. Restore the default disposition so this
/// behaves like a normal Unix CLI tool when piped.
#[cfg(unix)]
fn restore_default_sigpipe() {
    extern "C" {
        fn signal(signum: i32, handler: usize) -> usize;
    }
    const SIGPIPE: i32 = 13;
    const SIG_DFL: usize = 0;
    unsafe {
        signal(SIGPIPE, SIG_DFL);
    }
}
#[cfg(not(unix))]
fn restore_default_sigpipe() {}

fn main() -> ExitCode {
    restore_default_sigpipe();
    if run() {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}
