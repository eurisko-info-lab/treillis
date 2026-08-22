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
F9  f572b5243f38cfefd2eff2eb82c2cdd75173ee3fd900642451c50cf51c7dcce0
```

## Frozen F9 change

```text
ccc41dcdba399dfa2c2fb016dff47e4762ccd72b619c4cf2f6e4e85b94dcd8ac
```

F9 depends exactly on F8.

## F9 deterministic parallel DeltaNet

F9 adds seven parallel-runtime concepts, one parallel policy, and twelve per-agent parallel profiles. The profiles cover exactly the twelve independently executable F8 runtime agents.

Each profile names:

- the F8 DeltaNet agent kind,
- its semantic operation,
- dynamic touch selectors,
- the F6 invariants it preserves,
- commutation evidence.

The parallel policy requires preservation of:

```text
type
resource
effect
protocol
```

and freezes:

```text
scheduler         maximal-nonconflicting
tie-break         stable-agent-id
conflict          touch-overlap
independence      disjoint-touch
confluence        readback-equality
oracle            sequential-f8
max-rounds        4096
proof-required    true
```

The host exposes only generic scheduling mechanics. It resolves graph-defined selectors against an instruction and current immutable runtime state, forms a maximal non-conflicting ready round, executes that round deterministically, and checks reverse-order local replay against readback equality.

Dynamic selectors can refer to direct instruction fields and semantic state derived from them. Examples include channel endpoints, the resource owning a loan, resources owned by a terminating process, the queued resource on a channel, and a terminated child's result.

F9 therefore permits genuine local parallelism while preserving resource/process dependencies. Changing F9 scheduler or footprint data changes round formation without changing F8 sequential reduction rules.
