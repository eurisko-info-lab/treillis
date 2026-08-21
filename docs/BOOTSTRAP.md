# Trellis bootstrap through F8

The bootstrap is a derivation staircase. F0 is the only foundation constructed by the Scala host. Every successor exists only as a canonical DeltaTrellis change.

```text
F0 -> F1 -> F2 -> F3 -> F4 -> F5 -> F6 -> F7 -> F8
```

F8 is the first foundation where current DeltaNet execution no longer delegates machine steps to CESK-R. F7 still defines lowering and local interaction policy. F8 adds graph-defined runtime reduction rules and an explicit runtime policy:

```text
executor = independent
delegate = false
oracle   = ceskr
```

CESK-R remains executable as a differential oracle. Historical F7 execution retains its old delegation path so predecessor foundations stay testable.

No `F8` graph snapshot is checked in. The only successor artifact is:

```text
src/main/resources/trellis/foundations/F8.delta
```
