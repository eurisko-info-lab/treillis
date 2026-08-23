# Trellis Bootstrap Specification 0.1

## Constitutional equation

```text
Trellis = SemanticGraph + ΔTrellis + ChangeGraph + PublicationGraph
```

Derived views and machines include SVG, Typst, Code View, CESK-R, an e-graph optimizer, and DeltaNet execution.

## Graph

A graph contains immutable content-addressed nodes and edges, evolving entity bindings, and named roots.

`ContentId` identifies immutable content. `EntityId` identifies semantic lineage.

## Canonical encoding

`Canon` uses deterministic, length-prefixed structural encoding. Map entries are sorted before encoding. SHA-256 of the canonical bytes supplies content IDs.

Required law:

```text
same semantic graph => byte-identical canonical encoding => same hash
```

Scala and the independent Rust verifier implement strict decoders and share
adversarial canonical fixtures.

## Change language

`Delta.Op` is the initial free change vocabulary:

```text
AddNode
BindEntity
ReplaceEntity
RemoveEntity
Connect
Disconnect
AddRoot
RemoveRoot
RefineHole
```

A `Change` has dependency IDs, operations, a message, and author metadata. Its identity is derived from canonical change content.

## Change DAG and branches

A branch is:

```text
Branch = basis graph + frontier set + optional upstream provenance
```

Materialization computes the dependency closure, rejects concurrent footprint collisions, chooses a deterministic topological order, applies the changes, and validates the graph.

Independent legal changes are required to commute semantically.

## Resource model

Ports carry `Ty`, and types expose a structural `Mode`.

The trusted checker enforces:

- at most one producer per input port;
- exact endpoint type compatibility;
- non-unrestricted outputs cannot fan out implicitly;
- `core.replicate` requires unrestricted input;
- `core.erase` cannot erase linear input;
- borrow nodes must expose compatible ownership/loan ports;
- holes must state an expected boundary.

## Reference resource machine

The bootstrap machine models:

```text
Alloc
Move
BorrowShared
BorrowMut
EndBorrow
Drop
NewChannel
Send
Recv
```

Resources have a mode, owner, and active loans. A channel can be an owner while a message is in flight.

## Projections

All projections return content plus semantic mappings. SVG elements embed graph IDs. Typst and Code View are noncanonical explanatory views.

## Packages, profiles, and assemblies

A package provides capabilities and may require/import other capabilities. A
profile requests a reusable capability closure. Resolution chooses providers,
rejects ambiguity/conflicts, topologically orders fragments, and emits a
canonical selection lock.

An assembly names the foundation and base profile, adds or omits capabilities,
declares exposed consumer entry points, verification names, and emitted forms.
Assembly compilation reissues selected changes over a deterministic frontier;
the resulting root and graph size belong to that assembly result, not to any
individual feature delta.

## Live workspace

```text
Workspace = assembled basis + closed local frontier + open delta
```

The open delta is mutable and local. Commit validates and seals it as one
immutable change. Publish is separate, requires a clean workspace, and is
admitted by graph-defined namespace, publisher, signature, and ledger policy.

## Implemented beyond the initial bootstrap

- strict canonical decoding and hostile fixtures;
- F0–F11 clean-room closure and Scala/Rust parity;
- graph-defined process, session, CESK-R, projection, equality, DeltaNet, and
  evidence policies;
- deterministic parallel DeltaNet and sequential CESK-R execution;
- simulated distributed CAS, signed publications, consensus, discovery, and
  artifact attestations;
- readable self-documenting/self-testing feature deltas;
- capability packages, profiles, canonical locks, and graph assemblies;
- assembly-backed Squeak image with an open-delta transcript lifecycle.

## Remaining limitations

The bootstrap intentionally leaves these for subsequent slices:

- full Pijul-like patch algebra and conflict representation;
- durable external repository/CAS persistence;
- production network consensus and key management;
- general Trellis AST-to-IR lowering;
- a Smalltalk language package and VM for genuine Transcript evaluation;
- complete named assembly verification/emission backend dispatch.
