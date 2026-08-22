# Trellis bootstrap through F10

The bootstrap remains a strict derivation staircase. F0 is the only foundation constructed directly by Scala; every successor is canonical DeltaTrellis data.

```text
F0 -> F1 -> F2 -> F3 -> F4 -> F5 -> F6 -> F7 -> F8 -> F9 -> F10
```

F8 made DeltaNet independently executable. F9 made deterministic parallel round formation graph-defined. F10 makes the resulting execution auditable: every deterministic parallel execution can emit canonical replay evidence binding the current foundation, evidence policy, lowered net, dynamic footprints, round boundaries, and observable state roots.

```text
encoding             = canonical-v1
hash                 = sha256
state-root           = observable-state-v1
round-order          = stable-index
agent-order          = stable-agent-id
verification         = replay-exact
require-footprints   = true
require-confluence   = true
bind-foundation-root = true
bind-policy-content  = true
```

A certificate contains one redex certificate per selected agent and one round certificate per F9 parallel round. State roots deliberately exclude diagnostic trace order, matching F9 observational confluence. The matching strict decoder rejects non-canonical text/bytes. Verification then replays lowering, scheduling, footprints, confluence checks, and F8 independent reductions from the supplied program and current Trellis graph, requiring byte-identical canonical evidence.

There is no F10 graph snapshot. The successor artifact is only:

```text
src/main/resources/trellis/foundations/F10.delta
```
