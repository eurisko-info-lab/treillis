# Trellis Bootstrap Boundary

The Scala implementation is intentionally a bootstrap kernel, not the permanent home of the Trellis language definition.

## Host responsibilities

Scala currently provides only the machinery required to interpret the constitutional graph substrate:

- generic content-addressed nodes, ports, edges, entities and roots;
- canonical UTF-8 encoding and strict decoding;
- DeltaTrellis encoding, decoding and replay;
- change DAGs, branches and provenance;
- graph/resource validation;
- CESK-R reference mechanics;
- navigation and projections.

The production source count remains ten Scala files.

## Semantic responsibility moves into Trellis data

F0 contains the minimum generic vocabulary needed to bootstrap.

F1 is the first deliberate migration out of Scala. Its semantic-schema vocabulary is stored as a canonical DeltaTrellis resource and derived from F0:

```text
F0
 │
 │ F1.delta
 ▼
F1
```

The F1 schema entities are ordinary graph entities of kind `meta.schema`. Their relationships are ordinary typed graph edges. For example, Capability refers to Type and Mode through graph edges rather than Scala field references.

Future concepts should preferentially be introduced by later foundation deltas rather than by adding host-language cases.

## Current foundation

`Bootstrap.graph` is the currently active foundation and now aliases derived F1.

For explicit access:

```text
Bootstrap.f0
Bootstrap.f1Change
Bootstrap.f1
```

Useful CLI commands are:

```bash
sbt "run hash-f0"
sbt "run delta-f1"
sbt "run hash-f1"
sbt run
```
