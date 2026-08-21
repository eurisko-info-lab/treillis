# Trellis bootstrap boundary

The Scala host remains intentionally tiny. It provides:

- the generic graph substrate;
- canonical encoding and hashing;
- DeltaTrellis decoding/application;
- the Pijul-like change DAG and branch mechanics;
- graph well-formedness;
- a generic interpreter for graph-defined resource constraints;
- the CESK-R reference machinery and projections.

Concrete Trellis vocabulary should migrate upward into the foundation staircase rather than outward into new Scala cases.

## Current staircase

```text
F0  repository/meta substrate
 |
 +-- F1.delta --> F1 semantic schema
                    |
                    +-- F2.delta --> F2 resource calculus
```

F2 is the first foundation whose definitions directly drive host behavior: resource-operation legality and structural duplication policy are read from F2 graph nodes.

The next intended successor is F3, which should define process/channel capability semantics as graph data while keeping Scala responsible only for a generic transition interpreter.
