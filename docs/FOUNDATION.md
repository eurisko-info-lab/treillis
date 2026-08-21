# Trellis foundation staircase

Trellis foundations are derived, never supplied as successor snapshots.

```text
F0
  + F1.delta -> F1
  + F2.delta -> F2
  + F3.delta -> F3
  + F4.delta -> F4
  + F5.delta -> F5
  + F6.delta -> F6
  + F7.delta -> F7
```

Frozen identities:

```text
F0 root   6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd
F1 delta  45c76c2d537927e4f0506696b278d86023bf5b694b0401135a93130b59c56fb4
F1 root   b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45
F2 delta  36a8f04e97463c76b74f176c574aa5910099b6d9c562a22e56785bd822488de1
F2 root   09cc9ba6664b4e8dd84e4937a2b0cd63ea0863e9c2fdbcdeea27284f89da9496
F3 delta  12abc3e2f986d514d59d76d93b77fd1ba5221b3dfadd121c04134321f53ed5eb
F3 root   c565d28a992289608c45fac5ace462d1b2e05059ae83bfb5507c25bad311cc1c
F4 delta  678d58fddf41d20375e3485fb19a0c0d13b904ab1a317936d32ac0c4f5d52d7a
F4 root   616a960470e389c665ab94280b70bb5c7e203ba3b78cdf0b373948a0adf60847
F5 delta  d6fb1fb29f9864cbd8062af1b066270883aa0efcbe8dc405dfd17935fd091368
F5 root   3516c065b71cce1667a5075625deea2ee88f0e58365ccc21d215e86127b3aab1
F6 delta  1200106d29fc3cb9ce27647803db8339b3ca66cfdca83abf95756833713ebc20
F6 root   478974e6ac4c8767a64fecb00835b0505368c6140f1eac22f9cc618a3666bba1
F7 delta  b1e91c7e639bd57a1e968927a901e3f694749d1f4d67cf16a5c19c57be72bff9
F7 root   efcbbe6b6f335ebfcf67a1894d51aef35869d54e94da77b26b4700c68660750b
```

## F7: DeltaNet lowering and local interaction semantics

F7 introduces the local-interaction execution layer as Trellis graph data. It defines:

- Net, Agent, Wire, PrincipalPort, AuxiliaryPort, ActivePair, Interaction, Lowering, Readback, and Scheduler components;
- fifteen first-class agent kinds, including value, replicator, eraser, channel, process, allocation, movement, borrow, send/receive, spawn/terminate, and join agents;
- twelve machine-instruction lowering rules, covering every F4 instruction kind;
- seven active-pair rules for unrestricted replication, unrestricted erasure, affine drop, send/channel, receive/channel, spawn/process, and join/process interactions;
- an execution policy requiring preservation of the four F6 invariants, preservation evidence, a bounded interaction budget, deterministic stable-agent scheduling, and CESK-R state readback;
- a structural policy mapping F2 modes to explicit replicator/eraser behavior.

The lowering rules point by typed semantic edges to both their source operation and target agent kind. Active-pair rules point to their participating agent kinds and, for structural interactions, the relevant F2 mode. The execution policy points to the F6 type/resource/effect/protocol invariants.

F7 already executes structural net interactions directly:

```text
unrestricted value + replicator -> two value uses
affine value + replicator       -> forbidden
linear value + replicator       -> forbidden

unrestricted value + eraser     -> erase
affine value + eraser           -> deterministic drop
linear value + eraser           -> forbidden
```

For the non-structural instruction fragment, F7 first lowers into graph-selected DeltaNet agents, schedules those agents deterministically, and then delegates the primitive state transition to the F4 CESK-R executor. Differential tests require readback parity between this path and direct F4 execution.

This is intentional bootstrap staging: F7 freezes the lowering and interaction contract first. A later foundation can replace primitive delegation with an independent interaction reducer while retaining the same graph-defined semantics.

There is no `F7.graph` snapshot in the repository. The only successor material is `F7.delta`.
