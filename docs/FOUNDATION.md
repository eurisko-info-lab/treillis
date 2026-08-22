# Foundation staircase

Trellis foundations are changes, not snapshots.

```text
F0 repository/meta substrate
F1 semantic schema
F2 resource calculus
F3 process/channel calculus
F4 CESK-R semantics
F5 projection language
F6 resource-aware equality
F7 DeltaNet lowering and local interactions
F8 independent DeltaNet execution
F9 deterministic parallel DeltaNet
F10 canonical execution evidence
F11 clean-room bootstrap closure
```

## F11 closure

F11 introduces first-class graph concepts for `ClosureManifest`, `DerivationStep`, `FoundationRoot`, `DeltaId`, `CleanRoom`, `Reproducer`, `Verifier`, and `ClosureReport`.

The manifest contains exactly ten ordered steps, F1 through F10. A step records:

- predecessor foundation and frozen graph root,
- canonical DeltaTrellis change id,
- exact predecessor-change dependency,
- successor foundation root,
- canonical resource path,
- an explicit prohibition on successor snapshots.

The closure policy requires strict canonical delta decoding, predecessor-plus-delta reproduction, full graph validation, exact dependencies, fail-closed behavior, and canonical reports.

The clean-room verifier does not trust the already-materialized F1..F10 graphs. It starts from F0 and recomputes every successor. F11 remains a normal successor of F10 and therefore does not embed its own root in the closure manifest.
