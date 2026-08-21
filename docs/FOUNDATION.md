# Foundation staircase

## Frozen roots

```text
F0  6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd
F1  b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45
F2  09cc9ba6664b4e8dd84e4937a2b0cd63ea0863e9c2fdbcdeea27284f89da9496
F3  c565d28a992289608c45fac5ace462d1b2e05059ae83bfb5507c25bad311cc1c
F4  616a960470e389c665ab94280b70bb5c7e203ba3b78cdf0b373948a0adf60847
F5  3516c065b71cce1667a5075625deea2ee88f0e58365ccc21d215e86127b3aab1
F6  478974e6ac4c8767a64fecb00835b0505368c6140f1eac22f9cc618a3666bba1
F7  efcbbe6b6f335ebfcf67a1894d51aef35869d54e94da77b26b4700c68660750b
F8  7a49a1579c84b4a2c1d6613de4d8d14a8eff180d55e85108f7a7ffc13d5136d1
```

## Frozen F8 change

```text
357603f917a830c5ff785c1bbc78e961d2389e9b1bc80e9a2af7a861e7cc69a2
```

F8 depends exactly on F7.

## F8 independent DeltaNet runtime

F8 adds five runtime components, an independent runtime policy, one runtime schema, and twelve agent reduction rules. Every reduction rule names:

- the DeltaNet agent kind it consumes,
- the semantic operation that agent represents,
- the trusted local primitive to execute,
- the preserved F6 invariants,
- preservation evidence.

The reduction table covers every agent produced by the twelve F7 lowerings.

The runtime policy requires preservation of:

```text
type
resource
effect
protocol
```

and freezes:

```text
executor       independent
delegate       false
scheduler      stable-agent-id
readback       ceskr-state
oracle         ceskr
max-reductions 4096
proof-required true
```

The host reducer may still share generic resource/process bookkeeping helpers with CESK-R, but F8 execution does not call `Machine.step` or `Machine.stepDirect`. Thus changing F4 dispatch can break CESK-R without changing F8 DeltaNet execution, while changing an F8 reduction rule can change DeltaNet without changing CESK-R.

This differential independence is part of the F8 acceptance contract.
