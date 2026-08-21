# Bootstrap

The Scala bootstrap is intentionally small. It supplies canonical graph/change encoding, repository mechanics, generic validation, CESK-R transition primitives, semantic navigation, primitive projection rendering, bounded equality closure, and the first generic DeltaNet lowering/reduction substrate.

The current foundation is F7 and is reconstructed at startup from the frozen staircase:

```text
F0 + F1.delta -> F1
F1 + F2.delta -> F2
F2 + F3.delta -> F3
F3 + F4.delta -> F4
F4 + F5.delta -> F5
F5 + F6.delta -> F6
F6 + F7.delta -> F7
```

No F1-F7 graph snapshot is supplied.

F7 moves DeltaNet vocabulary, agent kinds, machine-to-net lowering, local active-pair selection, structural replication/erasure policy, scheduling, invariant preservation, and readback policy into Trellis data.

The F7 reducer is deliberately transitional. Graph-defined lowering is real, and replicator/eraser structural interactions reduce directly as local net interactions. Non-structural instruction agents currently delegate their primitive state transition to the F4 CESK-R executor after lowering. This provides a differential parity bridge rather than prematurely duplicating the full machine semantics in Scala.

A later foundation can replace that delegated primitive executor with a fully independent interaction reducer without changing the F7 lowering contract.

Current bootstrap roots and delta IDs are printed by `sbt run` and can be queried individually with `hash-fN` and `delta-fN` commands.
