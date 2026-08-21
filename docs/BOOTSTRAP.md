# Bootstrap

The Scala bootstrap is intentionally small. It supplies canonical graph/change encoding, repository mechanics, generic validation, a reference machine primitive executor, semantic navigation, primitive projection rendering, and a bounded equality-saturation kernel.

The current foundation is F6 and is reconstructed at startup from the frozen staircase:

```text
F0 + F1.delta -> F1
F1 + F2.delta -> F2
F2 + F3.delta -> F3
F3 + F4.delta -> F4
F4 + F5.delta -> F5
F5 + F6.delta -> F6
```

No F1-F6 graph snapshot is supplied.

F6 makes equality admission and extraction policy graph-resident. `Check.scala` interprets `equality.rewrite`, `equality.enode`, invariant, policy, and cost-model nodes. The host retains only bounded closure, recursive congruence traversal, and weighted cost arithmetic.

A rewrite enters an e-class only when its declared preservation set covers the invariants required by `equality.policy.rewrite`, its structural-mode guard matches, and the graph-defined proof policy is satisfied. Extraction uses the seven graph-defined cost dimensions and weights.

Current bootstrap roots and delta IDs are printed by `sbt run` and can be queried individually with `hash-fN` and `delta-fN` commands.
