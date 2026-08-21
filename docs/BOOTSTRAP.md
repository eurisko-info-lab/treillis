# Trellis bootstrap boundary

The Scala host remains intentionally tiny. It provides:

- the generic graph substrate;
- canonical encoding and hashing;
- DeltaTrellis decoding/application;
- the Pijul-like change DAG and branch mechanics;
- graph well-formedness;
- generic interpreters for graph-defined resource and process rules;
- CESK-R reference bookkeeping, queues, scheduling state, and projections.

Concrete Trellis vocabulary should migrate upward into the foundation staircase rather than outward into new Scala cases.

## Current staircase

```text
F0  repository/meta substrate
 |
 +-- F1.delta --> F1 semantic schema
                    |
                    +-- F2.delta --> F2 resource calculus
                                       |
                                       +-- F3.delta --> F3 process/channel calculus
```

F2 is the first foundation whose definitions directly drive host behavior: resource-operation legality and structural duplication policy are read from F2 graph nodes.

F3 extends that boundary to concurrency. Channel endpoint modes and the transition policy for channel creation, send, receive, spawn, join, and process termination are Trellis graph data. Scala retains generic process tables, queues, ownership bookkeeping, and dispatch of the dispositions named by F3.

The next intended successor is F4, which should lift the remaining CESK-R transition vocabulary and continuation/resource-frame semantics into graph-defined machine rules.
