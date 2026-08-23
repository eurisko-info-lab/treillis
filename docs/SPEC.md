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

A production version must add a decoder and adversarial canonical-decoding tests before publication is trusted.

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

## Not yet trusted/implemented

The bootstrap intentionally leaves these for subsequent slices:

- canonical decoder;
- persistent CAS;
- full Pijul-like patch algebra and conflict representation;
- session/protocol calculus;
- graph-defined CESK rules;
- e-graph derivation/extraction;
- DeltaNet lowering/runtime;
- real global blockchain consensus;
- cryptographic publisher identities;
- native Trellis Squeak.
