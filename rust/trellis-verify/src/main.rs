use std::collections::{BTreeMap, BTreeSet};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

const F0_ROOT: &str = "6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd";
const CLOSURE_REPORT_ID: &str = "01386829dbf602510b5fa8cc09943ad3e150a87469b8da214984ecfe735807ae";

const FOUNDATIONS: [Foundation; 11] = [
    Foundation { name: "F1", change: "45c76c2d537927e4f0506696b278d86023bf5b694b0401135a93130b59c56fb4", root: "b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45" },
    Foundation { name: "F2", change: "36a8f04e97463c76b74f176c574aa5910099b6d9c562a22e56785bd822488de1", root: "09cc9ba6664b4e8dd84e4937a2b0cd63ea0863e9c2fdbcdeea27284f89da9496" },
    Foundation { name: "F3", change: "12abc3e2f986d514d59d76d93b77fd1ba5221b3dfadd121c04134321f53ed5eb", root: "c565d28a992289608c45fac5ace462d1b2e05059ae83bfb5507c25bad311cc1c" },
    Foundation { name: "F4", change: "678d58fddf41d20375e3485fb19a0c0d13b904ab1a317936d32ac0c4f5d52d7a", root: "616a960470e389c665ab94280b70bb5c7e203ba3b78cdf0b373948a0adf60847" },
    Foundation { name: "F5", change: "d6fb1fb29f9864cbd8062af1b066270883aa0efcbe8dc405dfd17935fd091368", root: "3516c065b71cce1667a5075625deea2ee88f0e58365ccc21d215e86127b3aab1" },
    Foundation { name: "F6", change: "1200106d29fc3cb9ce27647803db8339b3ca66cfdca83abf95756833713ebc20", root: "478974e6ac4c8767a64fecb00835b0505368c6140f1eac22f9cc618a3666bba1" },
    Foundation { name: "F7", change: "b1e91c7e639bd57a1e968927a901e3f694749d1f4d67cf16a5c19c57be72bff9", root: "efcbbe6b6f335ebfcf67a1894d51aef35869d54e94da77b26b4700c68660750b" },
    Foundation { name: "F8", change: "357603f917a830c5ff785c1bbc78e961d2389e9b1bc80e9a2af7a861e7cc69a2", root: "7a49a1579c84b4a2c1d6613de4d8d14a8eff180d55e85108f7a7ffc13d5136d1" },
    Foundation { name: "F9", change: "ccc41dcdba399dfa2c2fb016dff47e4762ccd72b619c4cf2f6e4e85b94dcd8ac", root: "f572b5243f38cfefd2eff2eb82c2cdd75173ee3fd900642451c50cf51c7dcce0" },
    Foundation { name: "F10", change: "3e0c7f3ecf831457b2dba30d6b4ed21702ef9363943d2b976b479ae365895ace", root: "ecc80f1c146e1065f62f99811e897bc12c012b4a95e0b10ca02bbd5010fe69dc" },
    Foundation { name: "F11", change: "6aa7aabb086c5b6c02574b05495a2dcc67d6fb32cbaf5048ca728926a85e78c5", root: "73782cc5c18c8deb5aa55861f04e87a3cdc9b54dfd114e00fe8aad793d5f4e55" },
];

#[derive(Clone, Copy)]
struct Foundation {
    name: &'static str,
    change: &'static str,
    root: &'static str,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum Mode { Unrestricted, Affine, Linear }

impl Mode {
    fn text(&self) -> &'static str {
        match self { Self::Unrestricted => "unrestricted", Self::Affine => "affine", Self::Linear => "linear" }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum Direction { In, Out }

impl Direction {
    fn text(&self) -> &'static str { match self { Self::In => "in", Self::Out => "out" } }
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum Capability { Pure, Own, Read, Write, Suspended, Send, Recv, Session, Region, Effect, Process, Meta }

impl Capability {
    fn text(&self) -> &'static str {
        match self {
            Self::Pure => "pure", Self::Own => "own", Self::Read => "read", Self::Write => "write",
            Self::Suspended => "suspended", Self::Send => "send", Self::Recv => "recv", Self::Session => "session",
            Self::Region => "region", Self::Effect => "effect", Self::Process => "process", Self::Meta => "meta",
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum Ty {
    Atom(String),
    Tuple(Vec<Ty>),
    Cap(Capability, Mode, Box<Ty>, Option<String>),
}

impl Ty {
    fn mode(&self) -> Mode {
        match self {
            Self::Atom(_) => Mode::Unrestricted,
            Self::Tuple(items) => {
                if items.iter().any(|x| x.mode() == Mode::Linear) { Mode::Linear }
                else if items.iter().any(|x| x.mode() == Mode::Affine) { Mode::Affine }
                else { Mode::Unrestricted }
            }
            Self::Cap(_, mode, _, _) => mode.clone(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Port { name: String, direction: Direction, ty: Ty }

#[derive(Clone, Debug, PartialEq, Eq)]
struct Node { kind: String, ports: Vec<Port>, attrs: BTreeMap<String, String> }

impl Node {
    fn port(&self, name: &str) -> Option<&Port> { self.ports.iter().find(|p| p.name == name) }
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct PortRef { node: String, port: String }

#[derive(Clone, Debug, PartialEq, Eq)]
struct Edge { from: PortRef, to: PortRef, role: String }

#[derive(Clone, Debug, PartialEq, Eq, Default)]
struct Graph {
    nodes: BTreeMap<String, Node>,
    edges: BTreeMap<String, Edge>,
    entities: BTreeMap<String, String>,
    roots: BTreeMap<String, String>,
}

impl Graph {
    fn entity(&self, id: &str) -> Option<&Node> { self.entities.get(id).and_then(|n| self.nodes.get(n)) }
    fn incoming_count(&self, node: &str, port: &str) -> usize {
        self.edges.values().filter(|e| e.to.node == node && e.to.port == port).count()
    }
    fn outgoing_count(&self, node: &str, port: &str) -> usize {
        self.edges.values().filter(|e| e.from.node == node && e.from.port == port).count()
    }
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

#[derive(Clone, Debug, PartialEq, Eq)]
struct Change { dependencies: BTreeSet<String>, operations: Vec<Op>, message: String, author: String }

#[derive(Clone, Debug, PartialEq, Eq)]
struct ManifestPolicy {
    start: String,
    end: String,
    step_count: usize,
    ordering: String,
    reproduction: String,
    delta_decoding: String,
    dependency: String,
    validation: String,
    snapshot: String,
    failure: String,
    report: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct ManifestStep {
    entity: String,
    ordinal: usize,
    foundation: String,
    predecessor: String,
    predecessor_root: String,
    delta_id: String,
    dependency: Option<String>,
    successor_root: String,
    resource: String,
    snapshot: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct ClosureStep { foundation: String, predecessor_root: String, delta_id: String, successor_root: String }

#[derive(Clone, Debug, PartialEq, Eq)]
struct ClosureReport { start: String, end: String, steps: Vec<ClosureStep>, final_root: String }

fn atom(s: &str) -> String { format!("{}:{}", s.as_bytes().len(), s) }

fn record<'a, I>(tag: &str, fields: I) -> String
where I: IntoIterator<Item = &'a str> {
    let mut out = atom(tag);
    for f in fields { out.push_str(&atom(f)); }
    out
}

fn record_owned(tag: &str, fields: &[String]) -> String {
    record(tag, fields.iter().map(String::as_str))
}

fn split_atoms(bytes: &[u8]) -> Result<Vec<String>, String> {
    let mut out = Vec::new();
    let mut offset = 0usize;
    while offset < bytes.len() {
        let start = offset;
        while offset < bytes.len() && bytes[offset].is_ascii_digit() { offset += 1; }
        if start == offset { return Err(format!("expected atom length at byte {offset}")); }
        if offset >= bytes.len() || bytes[offset] != b':' { return Err(format!("expected ':' after atom length at byte {offset}")); }
        let length_text = std::str::from_utf8(&bytes[start..offset]).map_err(|e| format!("invalid atom length UTF-8: {e}"))?;
        if length_text.len() > 1 && length_text.starts_with('0') { return Err(format!("non-canonical atom length: {length_text}")); }
        let length: usize = length_text.parse().map_err(|_| format!("invalid atom length: {length_text}"))?;
        offset += 1;
        if length > bytes.len().saturating_sub(offset) { return Err(format!("atom length {length} exceeds remaining input at byte {offset}")); }
        let payload = &bytes[offset..offset + length];
        let value = std::str::from_utf8(payload).map_err(|e| format!("invalid UTF-8: {e}"))?;
        out.push(value.to_owned());
        offset += length;
    }
    Ok(out)
}

fn tag_and_fields(encoded: &str) -> Result<(String, Vec<String>), String> {
    let values = split_atoms(encoded.as_bytes())?;
    if values.is_empty() { Err("empty canonical record".into()) } else { Ok((values[0].clone(), values[1..].to_vec())) }
}

fn fields(encoded: &str, expected: &str) -> Result<Vec<String>, String> {
    let (tag, values) = tag_and_fields(encoded)?;
    if tag == expected { Ok(values) } else { Err(format!("expected {expected} record, found {tag}")) }
}

fn fixed(encoded: &str, expected: &str, arity: usize) -> Result<Vec<String>, String> {
    let values = fields(encoded, expected)?;
    if values.len() == arity { Ok(values) } else { Err(format!("{expected} record has {} fields; expected {arity}", values.len())) }
}

fn is_hash(s: &str) -> bool { s.len() == 64 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) }
fn require_hash(s: &str, label: &str) -> Result<(), String> { if is_hash(s) { Ok(()) } else { Err(format!("malformed {label}: {s}")) } }
fn require_nonempty(s: &str, label: &str) -> Result<(), String> { if s.is_empty() { Err(format!("empty {label}")) } else { Ok(()) } }

fn encode_ty(t: &Ty) -> String {
    match t {
        Ty::Atom(name) => record("atom", [name.as_str()]),
        Ty::Tuple(items) => {
            let vals: Vec<String> = items.iter().map(encode_ty).collect();
            record_owned("tuple", &vals)
        }
        Ty::Cap(kind, mode, inner, state) => {
            let state = match state { None => record("none", std::iter::empty::<&str>()), Some(v) => record("some", [v.as_str()]) };
            let inner = encode_ty(inner);
            record("cap", [kind.text(), mode.text(), inner.as_str(), state.as_str()])
        }
    }
}

fn decode_ty(encoded: &str) -> Result<Ty, String> {
    let (tag, vals) = tag_and_fields(encoded)?;
    match (tag.as_str(), vals.as_slice()) {
        ("atom", [name]) => { require_nonempty(name, "atom type name")?; Ok(Ty::Atom(name.clone())) }
        ("tuple", items) => Ok(Ty::Tuple(items.iter().map(|x| decode_ty(x)).collect::<Result<_,_>>()?)),
        ("cap", [kind, mode, inner, state]) => Ok(Ty::Cap(decode_capability(kind)?, decode_mode(mode)?, Box::new(decode_ty(inner)?), decode_option(state)?)),
        _ => Err(format!("invalid type record: {tag}")),
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

fn decode_mode(s: &str) -> Result<Mode, String> {
    match s { "unrestricted" => Ok(Mode::Unrestricted), "affine" => Ok(Mode::Affine), "linear" => Ok(Mode::Linear), _ => Err(format!("unknown mode: {s}")) }
}
fn decode_direction(s: &str) -> Result<Direction, String> {
    match s { "in" => Ok(Direction::In), "out" => Ok(Direction::Out), _ => Err(format!("unknown direction: {s}")) }
}
fn decode_capability(s: &str) -> Result<Capability, String> {
    match s {
        "pure" => Ok(Capability::Pure), "own" => Ok(Capability::Own), "read" => Ok(Capability::Read), "write" => Ok(Capability::Write),
        "suspended" => Ok(Capability::Suspended), "send" => Ok(Capability::Send), "recv" => Ok(Capability::Recv), "session" => Ok(Capability::Session),
        "region" => Ok(Capability::Region), "effect" => Ok(Capability::Effect), "process" => Ok(Capability::Process), "meta" => Ok(Capability::Meta),
        _ => Err(format!("unknown capability: {s}")),
    }
}

fn encode_port(p: &Port) -> String {
    let ty = encode_ty(&p.ty);
    record("port", [p.name.as_str(), p.direction.text(), ty.as_str()])
}

fn decode_port(encoded: &str) -> Result<Port, String> {
    let p = fixed(encoded, "port", 3)?;
    require_nonempty(&p[0], "port name")?;
    Ok(Port { name: p[0].clone(), direction: decode_direction(&p[1])?, ty: decode_ty(&p[2])? })
}

fn encode_node(n: &Node) -> String {
    let ports: Vec<String> = n.ports.iter().map(encode_port).collect();
    let attrs: Vec<String> = n.attrs.iter().map(|(k,v)| record("attr", [k.as_str(), v.as_str()])).collect();
    let ports = record_owned("ports", &ports);
    let attrs = record_owned("attrs", &attrs);
    record("node", [n.kind.as_str(), ports.as_str(), attrs.as_str()])
}

fn decode_node(encoded: &str) -> Result<Node, String> {
    let p = fixed(encoded, "node", 3)?;
    require_nonempty(&p[0], "node kind")?;
    let port_texts = fields(&p[1], "ports")?;
    let ports: Vec<Port> = port_texts.iter().map(|x| decode_port(x)).collect::<Result<_,_>>()?;
    let mut seen_ports = BTreeSet::new();
    for port in &ports { if !seen_ports.insert(port.name.clone()) { return Err(format!("duplicate port name: {}", port.name)); } }
    let attr_texts = fields(&p[2], "attrs")?;
    let mut attrs = BTreeMap::new();
    let mut last: Option<String> = None;
    for item in attr_texts {
        let a = fixed(&item, "attr", 2)?;
        require_nonempty(&a[0], "attribute key")?;
        if attrs.contains_key(&a[0]) { return Err(format!("duplicate attributes key: {}", a[0])); }
        if let Some(prev) = &last { if a[0].as_str() < prev.as_str() { return Err("attributes are not in canonical key order".into()); } }
        last = Some(a[0].clone());
        attrs.insert(a[0].clone(), a[1].clone());
    }
    Ok(Node { kind: p[0].clone(), ports, attrs })
}

fn encode_port_ref(r: &PortRef) -> String { record("ref", [r.node.as_str(), r.port.as_str()]) }
fn decode_port_ref(encoded: &str) -> Result<PortRef, String> {
    let p = fixed(encoded, "ref", 2)?;
    require_hash(&p[0], "port reference ContentId")?;
    require_nonempty(&p[1], "port reference name")?;
    Ok(PortRef { node: p[0].clone(), port: p[1].clone() })
}
fn encode_edge(e: &Edge) -> String {
    let from = encode_port_ref(&e.from); let to = encode_port_ref(&e.to);
    record("edge", [from.as_str(), to.as_str(), e.role.as_str()])
}
fn decode_edge(encoded: &str) -> Result<Edge, String> {
    let p = fixed(encoded, "edge", 3)?;
    require_nonempty(&p[2], "edge role")?;
    Ok(Edge { from: decode_port_ref(&p[0])?, to: decode_port_ref(&p[1])?, role: p[2].clone() })
}

fn encode_graph(g: &Graph) -> String {
    let nodes: Vec<String> = g.nodes.iter().map(|(id,n)| {
        let n = encode_node(n); record("n", [id.as_str(), n.as_str()])
    }).collect();
    let edges: Vec<String> = g.edges.iter().map(|(id,e)| {
        let e = encode_edge(e); record("e", [id.as_str(), e.as_str()])
    }).collect();
    let entities: Vec<String> = g.entities.iter().map(|(e,n)| record("entity", [e.as_str(), n.as_str()])).collect();
    let roots: Vec<String> = g.roots.iter().map(|(name,id)| record("root", [name.as_str(), id.as_str()])).collect();
    let ns = record_owned("nodes", &nodes); let es = record_owned("edges", &edges);
    let ents = record_owned("entities", &entities); let rs = record_owned("roots", &roots);
    record("graph", [ns.as_str(), es.as_str(), ents.as_str(), rs.as_str()])
}

#[cfg(test)]
fn decode_graph_bytes(bytes: &[u8]) -> Result<Graph, String> {
    let text = std::str::from_utf8(bytes).map_err(|e| format!("invalid UTF-8: {e}"))?;
    let g = decode_graph_unchecked(text)?;
    if encode_graph(&g).as_bytes() == bytes { Ok(g) } else { Err("non-canonical graph bytes".into()) }
}

#[cfg(test)]
fn decode_graph_unchecked(text: &str) -> Result<Graph, String> {
    let p = fixed(text, "graph", 4)?;
    let mut g = Graph::default();
    let mut last: Option<String> = None;
    for item in fields(&p[0], "nodes")? {
        let x = fixed(&item, "n", 2)?; require_hash(&x[0], "node ContentId")?;
        if let Some(prev) = &last { if x[0].as_str() < prev.as_str() { return Err("nodes are not in canonical key order".into()); } }
        if g.nodes.contains_key(&x[0]) { return Err(format!("duplicate nodes key: {}", x[0])); }
        let node = decode_node(&x[1])?;
        if node_id(&node) != x[0].as_str() { return Err(format!("node {} does not match its content hash", x[0])); }
        last = Some(x[0].clone()); g.nodes.insert(x[0].clone(), node);
    }
    last = None;
    for item in fields(&p[1], "edges")? {
        let x = fixed(&item, "e", 2)?; require_hash(&x[0], "edge ContentId")?;
        if let Some(prev) = &last { if x[0].as_str() < prev.as_str() { return Err("edges are not in canonical key order".into()); } }
        if g.edges.contains_key(&x[0]) { return Err(format!("duplicate edges key: {}", x[0])); }
        let edge = decode_edge(&x[1])?;
        if edge_id(&edge) != x[0].as_str() { return Err(format!("edge {} does not match its content hash", x[0])); }
        last = Some(x[0].clone()); g.edges.insert(x[0].clone(), edge);
    }
    last = None;
    for item in fields(&p[2], "entities")? {
        let x = fixed(&item, "entity", 2)?; require_nonempty(&x[0], "entity id")?; require_hash(&x[1], "entity ContentId")?;
        if let Some(prev) = &last { if x[0].as_str() < prev.as_str() { return Err("entities are not in canonical key order".into()); } }
        if g.entities.insert(x[0].clone(), x[1].clone()).is_some() { return Err(format!("duplicate entities key: {}", x[0])); }
        last = Some(x[0].clone());
    }
    last = None;
    for item in fields(&p[3], "roots")? {
        let x = fixed(&item, "root", 2)?; require_nonempty(&x[0], "root name")?; require_hash(&x[1], "root ContentId")?;
        if let Some(prev) = &last { if x[0].as_str() < prev.as_str() { return Err("roots are not in canonical key order".into()); } }
        if g.roots.insert(x[0].clone(), x[1].clone()).is_some() { return Err(format!("duplicate roots key: {}", x[0])); }
        last = Some(x[0].clone());
    }
    validate_references(&g)?;
    Ok(g)
}

fn encode_op(op: &Op) -> String {
    match op {
        Op::AddNode(n) => { let n = encode_node(n); record("op.add-node", [n.as_str()]) }
        Op::BindEntity(e,n) => record("op.bind-entity", [e.as_str(), n.as_str()]),
        Op::ReplaceEntity(e,n) => { let n = encode_node(n); record("op.replace-entity", [e.as_str(), n.as_str()]) }
        Op::RemoveEntity(e) => record("op.remove-entity", [e.as_str()]),
        Op::Connect(e) => { let e = encode_edge(e); record("op.connect", [e.as_str()]) }
        Op::Disconnect(e) => record("op.disconnect", [e.as_str()]),
        Op::AddRoot(name,node) => record("op.add-root", [name.as_str(), node.as_str()]),
        Op::RemoveRoot(name) => record("op.remove-root", [name.as_str()]),
        Op::RefineHole(e,n) => { let n = encode_node(n); record("op.refine-hole", [e.as_str(), n.as_str()]) }
    }
}

fn encode_change(c: &Change) -> String {
    let deps: Vec<String> = c.dependencies.iter().cloned().collect();
    let ops: Vec<String> = c.operations.iter().map(encode_op).collect();
    let deps = record_owned("dependencies", &deps); let ops = record_owned("operations", &ops);
    record("change", [deps.as_str(), ops.as_str(), c.message.as_str(), c.author.as_str()])
}

fn decode_change_bytes(bytes: &[u8]) -> Result<Change, String> {
    let text = std::str::from_utf8(bytes).map_err(|e| format!("invalid UTF-8: {e}"))?;
    let c = decode_change_unchecked(text)?;
    if encode_change(&c).as_bytes() == bytes { Ok(c) } else { Err("non-canonical DeltaTrellis change bytes".into()) }
}

fn decode_change_unchecked(text: &str) -> Result<Change, String> {
    let p = fixed(text, "change", 4)?;
    require_nonempty(&p[2], "change message")?; require_nonempty(&p[3], "change author")?;
    let dep_texts = fields(&p[0], "dependencies")?;
    let mut deps = BTreeSet::new();
    let mut last: Option<String> = None;
    for dep in dep_texts {
        require_hash(&dep, "change dependency")?;
        if let Some(prev) = &last { if dep.as_str() < prev.as_str() { return Err("change dependencies are not in canonical order".into()); } }
        if !deps.insert(dep.clone()) { return Err(format!("duplicate change dependency: {dep}")); }
        last = Some(dep);
    }
    let operations = fields(&p[1], "operations")?.iter().map(|x| decode_op(x)).collect::<Result<_,_>>()?;
    Ok(Change { dependencies: deps, operations, message: p[2].clone(), author: p[3].clone() })
}

fn decode_op(encoded: &str) -> Result<Op, String> {
    let (tag, v) = tag_and_fields(encoded)?;
    match (tag.as_str(), v.as_slice()) {
        ("op.add-node", [n]) => Ok(Op::AddNode(decode_node(n)?)),
        ("op.bind-entity", [e,n]) => { require_nonempty(e,"entity id")?; require_hash(n,"bound node ContentId")?; Ok(Op::BindEntity(e.clone(),n.clone())) }
        ("op.replace-entity", [e,n]) => { require_nonempty(e,"entity id")?; Ok(Op::ReplaceEntity(e.clone(),decode_node(n)?)) }
        ("op.remove-entity", [e]) => { require_nonempty(e,"entity id")?; Ok(Op::RemoveEntity(e.clone())) }
        ("op.connect", [e]) => Ok(Op::Connect(decode_edge(e)?)),
        ("op.disconnect", [e]) => { require_hash(e,"disconnected edge ContentId")?; Ok(Op::Disconnect(e.clone())) }
        ("op.add-root", [name,node]) => { require_nonempty(name,"root name")?; require_hash(node,"root node ContentId")?; Ok(Op::AddRoot(name.clone(),node.clone())) }
        ("op.remove-root", [name]) => { require_nonempty(name,"root name")?; Ok(Op::RemoveRoot(name.clone())) }
        ("op.refine-hole", [e,n]) => { require_nonempty(e,"hole entity id")?; Ok(Op::RefineHole(e.clone(),decode_node(n)?)) }
        _ => Err(format!("invalid DeltaTrellis operation record: {tag}")),
    }
}

fn node_id(n: &Node) -> String { sha256_hex(encode_node(n).as_bytes()) }
fn edge_id(e: &Edge) -> String { sha256_hex(encode_edge(e).as_bytes()) }
fn graph_id(g: &Graph) -> String { sha256_hex(encode_graph(g).as_bytes()) }
fn change_id(c: &Change) -> String { sha256_hex(encode_change(c).as_bytes()) }

fn add_node(g: &mut Graph, n: Node) -> String { let id = node_id(&n); g.nodes.insert(id.clone(), n); id }
fn add_edge(g: &mut Graph, e: Edge) -> String { let id = edge_id(&e); g.edges.insert(id.clone(), e); id }

fn node_referenced(g: &Graph, id: &str) -> bool {
    g.entities.values().any(|x| x == id) || g.roots.values().any(|x| x == id) ||
        g.edges.values().any(|e| e.from.node == id || e.to.node == id)
}

fn apply_change(mut g: Graph, c: &Change) -> Result<Graph, String> {
    for op in &c.operations { g = apply_op(g, op)?; }
    Ok(g)
}

fn apply_op(mut g: Graph, op: &Op) -> Result<Graph, String> {
    match op {
        Op::AddNode(n) => { add_node(&mut g, n.clone()); }
        Op::BindEntity(e,n) => {
            if !g.nodes.contains_key(n) { return Err(format!("cannot bind {e}: missing node {n}")); }
            g.entities.insert(e.clone(), n.clone());
        }
        Op::ReplaceEntity(e,n) => {
            let old = g.entities.get(e).cloned(); let id = add_node(&mut g, n.clone()); g.entities.insert(e.clone(), id.clone());
            if let Some(old) = old { if old != id && !node_referenced(&g, &old) { g.nodes.remove(&old); } }
        }
        Op::RemoveEntity(e) => {
            if let Some(old) = g.entities.remove(e) { if !node_referenced(&g, &old) { g.nodes.remove(&old); } }
        }
        Op::Connect(e) => {
            if !g.nodes.contains_key(&e.from.node) || !g.nodes.contains_key(&e.to.node) { return Err("cannot connect edge: endpoint node missing".into()); }
            add_edge(&mut g, e.clone());
        }
        Op::Disconnect(e) => { g.edges.remove(e); }
        Op::AddRoot(name,node) => {
            if !g.nodes.contains_key(node) { return Err(format!("cannot add root {name}: missing node {node}")); }
            g.roots.insert(name.clone(), node.clone());
        }
        Op::RemoveRoot(name) => { g.roots.remove(name); }
        Op::RefineHole(e,n) => {
            match g.entity(e) {
                Some(old) if old.kind == "core.hole" => return apply_op(g, &Op::ReplaceEntity(e.clone(), n.clone())),
                Some(_) => return Err(format!("entity {e} is not a hole")),
                None => return Err(format!("unknown hole entity {e}")),
            }
        }
    }
    Ok(g)
}

fn validate_references(g: &Graph) -> Result<(), String> {
    for (entity,node) in &g.entities { if !g.nodes.contains_key(node) { return Err(format!("entity {entity} references missing node {node}")); } }
    for (name,node) in &g.roots { if !g.nodes.contains_key(node) { return Err(format!("root {name} references missing node {node}")); } }
    for (id,e) in &g.edges {
        let from = g.nodes.get(&e.from.node).ok_or_else(|| format!("edge {id} references missing source node {}",e.from.node))?;
        let to = g.nodes.get(&e.to.node).ok_or_else(|| format!("edge {id} references missing target node {}",e.to.node))?;
        if from.port(&e.from.port).is_none() { return Err(format!("edge {id} references missing source port {}",e.from.port)); }
        if to.port(&e.to.port).is_none() { return Err(format!("edge {id} references missing target port {}",e.to.port)); }
    }
    Ok(())
}

fn structural_policy_duplicate(g: &Graph, mode: &Mode) -> bool {
    let entity = format!("resource.mode.{}", mode.text());
    if let Some(node) = g.entity(&entity) {
        if node.kind == "resource.mode" { return node.attrs.get("duplicate").map(String::as_str).unwrap_or(match mode { Mode::Unrestricted=>"allow", _=>"forbid" }) == "allow"; }
    }
    mode == &Mode::Unrestricted
}

fn validate_graph(g: &Graph) -> Result<(), String> {
    validate_references(g)?;
    let mut errors = Vec::new();
    for (id,e) in &g.edges {
        let from = g.nodes.get(&e.from.node).unwrap(); let to = g.nodes.get(&e.to.node).unwrap();
        let fp = from.port(&e.from.port).unwrap(); let tp = to.port(&e.to.port).unwrap();
        if fp.direction != Direction::Out { errors.push(format!("edge {} source port is not output", &id[..8])); }
        if tp.direction != Direction::In { errors.push(format!("edge {} target port is not input", &id[..8])); }
        if fp.ty != tp.ty { errors.push(format!("edge {} type mismatch", &id[..8])); }
    }
    for (id,node) in &g.nodes {
        for p in node.ports.iter().filter(|p| p.direction == Direction::In) {
            if g.incoming_count(id, &p.name) > 1 { errors.push(format!("multiple producers for {}.{}", &id[..8], p.name)); }
        }
        for p in node.ports.iter().filter(|p| p.direction == Direction::Out) {
            if g.outgoing_count(id, &p.name) > 1 && !structural_policy_duplicate(g, &p.ty.mode()) {
                errors.push(format!("illegal duplication of {} capability at {}.{}", p.ty.mode().text(), &id[..8], p.name));
            }
        }
        if node.kind == "core.hole" && !node.attrs.contains_key("expected") { errors.push(format!("hole {} lacks expected boundary description", &id[..8])); }
    }
    if errors.is_empty() { Ok(()) } else { Err(errors.join("; ")) }
}

fn meta(kind: &str, description: &str) -> Node {
    let mut attrs = BTreeMap::new(); attrs.insert("description".into(), description.into()); attrs.insert("name".into(), kind.into());
    Node { kind: "meta.node-kind".into(), ports: vec![], attrs }
}

fn build_f0() -> Graph {
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
        let id = add_node(&mut g, meta(entity, description)); g.entities.insert(entity.into(), id);
    }
    let mut attrs = BTreeMap::new(); attrs.insert("name".into(), "trellis-bootstrap".into()); attrs.insert("version".into(), "0.2".into());
    let root = Node { kind: "repo.root".into(), ports: vec![], attrs };
    let root_id = add_node(&mut g, root); g.roots.insert("bootstrap".into(), root_id.clone()); g.entities.insert("trellis.bootstrap".into(), root_id);
    g
}

fn parse_policy(g: &Graph) -> Result<ManifestPolicy,String> {
    let n = g.entity("bootstrap.policy.closure").ok_or("missing bootstrap.policy.closure")?;
    if n.kind != "bootstrap.closure-policy" { return Err(format!("bootstrap.policy.closure is {}, not bootstrap.closure-policy",n.kind)); }
    let req = |k:&str| n.attrs.get(k).filter(|x| !x.is_empty()).cloned().ok_or_else(|| format!("bootstrap closure node lacks {k}"));
    let step_count: usize = req("step-count")?.parse().map_err(|_| "bootstrap closure step-count must be positive".to_string())?;
    if step_count == 0 { return Err("bootstrap closure step-count must be positive".into()); }
    Ok(ManifestPolicy { start:req("start")?, end:req("end")?, step_count, ordering:req("ordering")?, reproduction:req("reproduction")?, delta_decoding:req("delta-decoding")?, dependency:req("dependency")?, validation:req("validation")?, snapshot:req("snapshot")?, failure:req("failure")?, report:req("report")? })
}

fn parse_steps(g: &Graph) -> Result<Vec<ManifestStep>,String> {
    let mut out = Vec::new();
    for (entity,node_id) in &g.entities {
        let n = g.nodes.get(node_id).unwrap();
        if n.kind != "bootstrap.derivation-step" { continue; }
        let req = |k:&str| n.attrs.get(k).filter(|x| !x.is_empty()).cloned().ok_or_else(|| format!("{entity} lacks {k}"));
        let ordinal: usize = req("ordinal")?.parse().map_err(|_| format!("{entity} lacks positive ordinal"))?;
        if ordinal == 0 { return Err(format!("{entity} lacks positive ordinal")); }
        let dep = req("dependency")?; let dependency = if dep == "none" { None } else { require_hash(&dep,"bootstrap dependency")?; Some(dep) };
        out.push(ManifestStep { entity:entity.clone(), ordinal, foundation:req("foundation")?, predecessor:req("predecessor")?, predecessor_root:req("predecessor-root")?, delta_id:req("delta-id")?, dependency, successor_root:req("successor-root")?, resource:req("resource")?, snapshot:req("snapshot")? });
    }
    out.sort_by_key(|x| x.ordinal);
    Ok(out)
}

fn validate_manifest(g: &Graph) -> Result<(ManifestPolicy,Vec<ManifestStep>),String> {
    let p = parse_policy(g)?; let steps = parse_steps(g)?;
    let expected = [
        ("start",p.start.as_str(),"F0"),("end",p.end.as_str(),"F10"),("ordering",p.ordering.as_str(),"ordinal"),
        ("reproduction",p.reproduction.as_str(),"predecessor-plus-delta"),("delta-decoding",p.delta_decoding.as_str(),"strict-canonical"),
        ("dependency",p.dependency.as_str(),"exact-predecessor-change"),("validation",p.validation.as_str(),"full"),
        ("snapshot",p.snapshot.as_str(),"successor-forbidden"),("failure",p.failure.as_str(),"fail-closed"),("report",p.report.as_str(),"canonical-v1")
    ];
    for (label,actual,want) in expected { if actual != want { return Err(format!("bootstrap closure {label} must be {want}, found {actual}")); } }
    if p.step_count != 10 || steps.len() != 10 { return Err(format!("bootstrap closure must contain 10 steps, policy={}, actual={}",p.step_count,steps.len())); }
    for (i,s) in steps.iter().enumerate() {
        let ord = i+1; if s.ordinal != ord { return Err("bootstrap closure ordinals are not exactly 1..step-count".into()); }
        let foundation = format!("F{ord}"); let predecessor = if i==0 { "F0".to_string() } else { format!("F{i}") };
        if s.foundation != foundation || s.predecessor != predecessor { return Err(format!("bootstrap step {ord} name/predecessor mismatch")); }
        if s.resource != format!("/trellis/foundations/{}.delta",s.foundation) { return Err(format!("bootstrap step {ord} has noncanonical resource {}",s.resource)); }
        if s.snapshot != "forbidden" { return Err(format!("bootstrap step {ord} permits a successor snapshot")); }
        require_hash(&s.predecessor_root,"predecessor root")?; require_hash(&s.delta_id,"delta id")?; require_hash(&s.successor_root,"successor root")?;
        if i==0 {
            if s.dependency.is_some() { return Err("bootstrap F1 step must have no delta dependency".into()); }
        } else {
            let prev=&steps[i-1];
            if s.predecessor_root != prev.successor_root || s.dependency.as_deref()!=Some(prev.delta_id.as_str()) { return Err(format!("bootstrap step {ord} does not chain to previous step")); }
        }
        let manifest_id = g.entities.get("bootstrap.manifest").ok_or("missing bootstrap.manifest")?;
        let step_node = g.entities.get(&s.entity).ok_or_else(|| format!("missing manifest step entity {}",s.entity))?;
        let port = format!("step{ord:02}");
        let incoming:Vec<&Edge> = g.edges.values().filter(|e| e.to.node==*manifest_id && e.to.port==port).collect();
        if incoming.len()!=1 || incoming[0].from.node.as_str() != step_node.as_str() { return Err(format!("bootstrap manifest does not bind {} at {port}",s.foundation)); }
    }
    Ok((p,steps))
}

fn encode_closure_report(r:&ClosureReport)->String {
    let steps:Vec<String>=r.steps.iter().map(|s| record("closure-step",[s.foundation.as_str(),s.predecessor_root.as_str(),s.delta_id.as_str(),s.successor_root.as_str()])).collect();
    let steps=record_owned("steps",&steps);
    record("closure-report",[r.start.as_str(),r.end.as_str(),steps.as_str(),r.final_root.as_str()])
}

#[derive(Clone)]
struct Verification { graphs:Vec<(String,String)>, changes:Vec<(String,String)>, f11:Graph, report:ClosureReport }

fn verify_all(resources:&Path)->Result<Verification,String> {
    let mut g=build_f0();
    let f0=graph_id(&g); if f0!=F0_ROOT { return Err(format!("Rust F0 root mismatch: {f0} != {F0_ROOT}")); }
    validate_graph(&g)?;
    let mut graphs=vec![("F0".into(),f0)]; let mut changes=Vec::new();
    let mut previous_change:Option<String>=None;
    for f in FOUNDATIONS {
        let path=resources.join(format!("{}.delta",f.name)); let bytes=fs::read(&path).map_err(|e|format!("cannot read {}: {e}",path.display()))?;
        let c=decode_change_bytes(&bytes).map_err(|e|format!("invalid {}.delta: {e}",f.name))?;
        let cid=change_id(&c); if cid!=f.change { return Err(format!("{} delta id mismatch: {cid} != {}",f.name,f.change)); }
        let expected:BTreeSet<String>=previous_change.iter().cloned().collect();
        if c.dependencies!=expected { return Err(format!("{} dependency mismatch: {:?} != {:?}",f.name,c.dependencies,expected)); }
        g=apply_change(g,&c)?; validate_graph(&g)?; let gid=graph_id(&g);
        if gid!=f.root { return Err(format!("{} root mismatch: {gid} != {}",f.name,f.root)); }
        changes.push((f.name.into(),cid.clone())); graphs.push((f.name.into(),gid)); previous_change=Some(cid);
    }
    let f11=g.clone(); let (policy,steps)=validate_manifest(&f11)?;
    let report=clean_room_from_manifest(resources,&policy,&steps)?;
    let rid=sha256_hex(encode_closure_report(&report).as_bytes());
    if rid!=CLOSURE_REPORT_ID { return Err(format!("closure report id mismatch: {rid} != {CLOSURE_REPORT_ID}")); }
    Ok(Verification{graphs,changes,f11,report})
}

fn clean_room_from_manifest(resources:&Path,p:&ManifestPolicy,steps:&[ManifestStep])->Result<ClosureReport,String>{
    let mut current=build_f0(); let mut reports=Vec::new();
    if graph_id(&current)!=steps[0].predecessor_root { return Err("F0 root disagrees with closure manifest".into()); }
    for s in steps {
        let current_root=graph_id(&current); if current_root!=s.predecessor_root { return Err(format!("{} predecessor root mismatch",s.foundation)); }
        let filename=Path::new(s.resource.as_str()).file_name().and_then(|x|x.to_str()).ok_or("invalid manifest resource")?;
        let bytes=fs::read(resources.join(filename)).map_err(|e|format!("cannot read {filename}: {e}"))?;
        let c=decode_change_bytes(&bytes)?; let cid=change_id(&c); if cid!=s.delta_id { return Err(format!("{} delta id mismatch",s.foundation)); }
        let expected:BTreeSet<String>=s.dependency.iter().cloned().collect(); if c.dependencies!=expected { return Err(format!("{} dependency mismatch",s.foundation)); }
        let next=apply_change(current,&c)?; validate_graph(&next)?; let next_root=graph_id(&next); if next_root!=s.successor_root { return Err(format!("{} successor root mismatch",s.foundation)); }
        reports.push(ClosureStep{foundation:s.foundation.clone(),predecessor_root:current_root,delta_id:cid,successor_root:next_root}); current=next;
    }
    if reports.len()!=p.step_count || reports.last().map(|x|x.foundation.as_str())!=Some(p.end.as_str()) { return Err("closure did not finish at declared endpoint".into()); }
    Ok(ClosureReport{start:p.start.clone(),end:p.end.clone(),final_root:graph_id(&current),steps:reports})
}

fn resources_from_args(args:&mut Vec<String>)->PathBuf{
    if let Some(pos)=args.iter().position(|x|x=="--resources"){
        if pos+1>=args.len(){eprintln!("--resources requires a path");std::process::exit(2)}
        let p=PathBuf::from(args.remove(pos+1));args.remove(pos);return p;
    }
    if let Ok(p)=env::var("TRELLIS_FOUNDATIONS"){return PathBuf::from(p)}
    let local=PathBuf::from("src/main/resources/trellis/foundations");if local.is_dir(){return local}
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../src/main/resources/trellis/foundations")
}

fn print_roots(v:&Verification){
    println!("F0 root:         {}", &v.graphs[0].1);
    for i in 1..v.graphs.len(){
        let (name, root) = &v.graphs[i];
        let (_, delta) = &v.changes[i-1];
        println!("{name} delta:       {delta}");
        println!("{name} root:        {root}");
    }
}

fn main(){
    let mut args:Vec<String>=env::args().skip(1).collect();let resources=resources_from_args(&mut args);let cmd=args.first().map(String::as_str).unwrap_or("verify");
    let v=verify_all(&resources).unwrap_or_else(|e|{eprintln!("verification failed: {e}");std::process::exit(1)});
    match cmd{
        "verify"=>{
            println!("[ok] Rust reconstructed F0 through F11");
            println!("[ok] 11 canonical delta ids match Scala");
            println!("[ok] 12 foundation roots match Scala");
            println!("[ok] F11 manifest replays F0 through F10 fail-closed");
            println!("[ok] closure report {} -> {}",&CLOSURE_REPORT_ID[..12],&v.report.final_root[..12]);
            println!("[ok] F11 graph: {} nodes, {} edges, {} entities",v.f11.nodes.len(),v.f11.edges.len(),v.f11.entities.len());
        }
        "closure"=>println!("{}",encode_closure_report(&v.report)),
        "roots"=>print_roots(&v),
        other=>{eprintln!("unknown command: {other}; use verify|closure|roots [--resources PATH]");std::process::exit(2)}
    }
}

// Minimal zero-dependency SHA-256. Kept here deliberately: the independent
// verifier must not outsource constitutional content addressing to a crate.
fn sha256_hex(data:&[u8])->String{
    const K:[u32;64]=[
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
    let mut h=[0x6a09e667u32,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
    let bit_len=(data.len() as u64)*8;let mut msg=data.to_vec();msg.push(0x80);while msg.len()%64!=56{msg.push(0)}msg.extend_from_slice(&bit_len.to_be_bytes());
    for chunk in msg.chunks_exact(64){let mut w=[0u32;64];for i in 0..16{w[i]=u32::from_be_bytes(chunk[i*4..i*4+4].try_into().unwrap());}for i in 16..64{let s0=w[i-15].rotate_right(7)^w[i-15].rotate_right(18)^(w[i-15]>>3);let s1=w[i-2].rotate_right(17)^w[i-2].rotate_right(19)^(w[i-2]>>10);w[i]=w[i-16].wrapping_add(s0).wrapping_add(w[i-7]).wrapping_add(s1);}let(mut a,mut b,mut c,mut d,mut e,mut f,mut g,mut hh)=(h[0],h[1],h[2],h[3],h[4],h[5],h[6],h[7]);for i in 0..64{let s1=e.rotate_right(6)^e.rotate_right(11)^e.rotate_right(25);let ch=(e&f)^((!e)&g);let t1=hh.wrapping_add(s1).wrapping_add(ch).wrapping_add(K[i]).wrapping_add(w[i]);let s0=a.rotate_right(2)^a.rotate_right(13)^a.rotate_right(22);let maj=(a&b)^(a&c)^(b&c);let t2=s0.wrapping_add(maj);hh=g;g=f;f=e;e=d.wrapping_add(t1);d=c;c=b;b=a;a=t1.wrapping_add(t2);}h[0]=h[0].wrapping_add(a);h[1]=h[1].wrapping_add(b);h[2]=h[2].wrapping_add(c);h[3]=h[3].wrapping_add(d);h[4]=h[4].wrapping_add(e);h[5]=h[5].wrapping_add(f);h[6]=h[6].wrapping_add(g);h[7]=h[7].wrapping_add(hh);}
    h.iter().map(|x|format!("{x:08x}")).collect()
}

#[cfg(test)]
mod tests{
    use super::*;
    fn resources()->PathBuf{PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../src/main/resources/trellis/foundations")}
    fn adversarial()->PathBuf{PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../src/test/resources/trellis/canon/adversarial")}
    #[test]fn sha256_known_vector(){assert_eq!(sha256_hex(b"abc"),"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");}
    #[test]fn f0_is_byte_stable(){let g=build_f0();assert_eq!(graph_id(&g),F0_ROOT);assert_eq!(g.nodes.len(),19);assert_eq!(g.entities.len(),19);}
    #[test]fn strict_atoms_reject_nonminimal_lengths(){assert!(split_atoms(b"01:a").is_err());}
    #[test]fn strict_atoms_reject_malformed_utf8(){assert!(split_atoms(&[b'1',b':',0xff]).is_err());}
    #[test]fn strict_change_rejects_trailing_record_field(){let bytes=fs::read(resources().join("F1.delta")).unwrap();let mut text=String::from_utf8(bytes).unwrap();text.push_str("0:");assert!(decode_change_bytes(text.as_bytes()).is_err());}
    #[test]fn strict_change_rejects_unsorted_dependencies(){let a="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";let b="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";let deps=record("dependencies",[b,a]);let ops=record("operations",std::iter::empty::<&str>());let c=record("change",[deps.as_str(),ops.as_str(),"x","ai"]);assert!(decode_change_bytes(c.as_bytes()).is_err());}
    #[test]fn graph_round_trip_is_exact(){let g=build_f0();let bytes=encode_graph(&g).into_bytes();assert_eq!(decode_graph_bytes(&bytes).unwrap(),g);}
    #[test]fn shared_adversarial_canonical_fixtures_are_rejected(){for name in ["malformed-utf8.bin","nonminimal-atom.bin","trailing-data.bin","duplicate-node-key.bin","unordered-nodes.bin","missing-reference.bin"]{let bytes=fs::read(adversarial().join(name)).unwrap();assert!(decode_graph_bytes(&bytes).is_err(),"fixture unexpectedly accepted: {name}");}}
    #[test]fn all_frozen_deltas_and_roots_reproduce(){let v=verify_all(&resources()).unwrap();assert_eq!(v.graphs.len(),12);assert_eq!(v.changes.len(),11);assert_eq!(v.graphs.last().unwrap().1.as_str(),FOUNDATIONS[10].root);}
    #[test]fn closure_report_matches_scala_bytes(){let v=verify_all(&resources()).unwrap();let encoded=encode_closure_report(&v.report);assert_eq!(sha256_hex(encoded.as_bytes()),CLOSURE_REPORT_ID);assert_eq!(v.report.final_root.as_str(),FOUNDATIONS[9].root);}
    #[test]fn manifest_is_fail_closed(){let v=verify_all(&resources()).unwrap();let(mut p,mut steps)=validate_manifest(&v.f11).unwrap();steps[4].successor_root="0".repeat(64);assert!(clean_room_from_manifest(&resources(),&p,&steps).is_err());p.failure="skip".into();let mut bad=v.f11.clone();let id=bad.entities["bootstrap.policy.closure"].clone();bad.nodes.get_mut(&id).unwrap().attrs.insert("failure".into(),"skip".into());assert!(validate_manifest(&bad).is_err());}
}
