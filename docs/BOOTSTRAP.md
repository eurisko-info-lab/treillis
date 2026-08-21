# Bootstrap

The Scala bootstrap is intentionally small. It supplies canonical graph/change encoding, repository mechanics, generic validation, a reference machine primitive executor, semantic navigation, and primitive projection rendering.

The current foundation is F5 and is reconstructed at startup from the frozen staircase:

```text
F0 + F1.delta -> F1
F1 + F2.delta -> F2
F2 + F3.delta -> F3
F3 + F4.delta -> F4
F4 + F5.delta -> F5
```

No F1-F5 graph snapshot is supplied.

F5 makes projection policy graph-resident. `Project.scala` interprets `projection.view` and `projection.rule` nodes and delegates only to a small set of trusted rendering primitives. The former direct projector remains solely as a temporary parity oracle.

Current bootstrap roots and delta IDs are printed by `sbt run` and can be queried individually with `hash-fN` and `delta-fN` commands.
