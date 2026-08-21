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
```

## F6: resource-aware equality saturation

F6 moves semantic equivalence admission and extraction policy into Trellis data. It defines:

- EGraph, EClass, ENode, Rewrite, Pattern, Substitution, Analysis, Invariant, CostModel, Extractor, Saturation, Equivalence, and ProofEvidence as equality components;
- type, resource, effect, and protocol preservation invariants;
- reflexive, symmetric, transitive, and congruence laws as graph data;
- a constitutional rewrite policy requiring all four invariants, proof evidence, bounded saturation, and F2 structural-mode guards;
- seven extraction-cost dimensions: nodes, allocations, replication, interactions, peak memory, communication, and critical path;
- a graph-defined default weighted cost model.

The tiny host does not hard-code Trellis rewrites. Local/package graphs may add `equality.rewrite` nodes. The generic kernel reads their left/right operator names, structural-mode guard, preservation claims, and evidence reference, then constructs bounded equivalence classes under symmetric/transitive closure and recursive congruence.

`equality.enode` nodes may provide operator cost metrics. Extraction recursively scores representatives with the graph-defined cost model and chooses the deterministic minimum.

There is no `F6.graph` snapshot in the repository. The only successor material is `F6.delta`.
