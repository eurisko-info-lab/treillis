# Trellis Bootstrap

The Scala host remains intentionally small. It provides the generic graph/CAS/change substrate, validation, a tiny trusted transition-primitive set, repository mechanics, projections, and the bootstrap interpreter.

The foundation staircase is data-derived:

```text
F0 + F1.delta = F1   semantic schema
F1 + F2.delta = F2   resource calculus
F2 + F3.delta = F3   process/channel calculus
F3 + F4.delta = F4   CESK-R transition semantics
```

No F1/F2/F3/F4 graph snapshots are checked in. Each successor is reconstructed from its predecessor plus one canonical DeltaTrellis change.

F4 is the first machine-semantics foundation. The Trellis graph owns the instruction-to-transition-primitive dispatch table. Scala executes only the small trusted primitive vocabulary and keeps the old F3 direct dispatcher temporarily as a parity oracle.
