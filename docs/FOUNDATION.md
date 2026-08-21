# Trellis foundation staircase

Trellis foundations are derived, never supplied as successor snapshots.

```text
F0
  + F1.delta -> F1
  + F2.delta -> F2
  + F3.delta -> F3
  + F4.delta -> F4
  + F5.delta -> F5
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
```

## F5: projection language

F5 moves observation policy into Trellis data. It defines:

- `Selection`, `View`, `RenderRule`, `Layout`, `SemanticAnchor`, `NavigationTarget`, and `Document` as projection components;
- graph-defined Code, Typst, structure SVG, ownership SVG, process SVG, and machine SVG views;
- ten graph-resident rendering rules mapping semantic subjects to a small trusted primitive renderer;
- first-class `projection.component` and `projection.view` edges.

The host retains only primitive rendering mechanics such as XML escaping, simple layout arithmetic, and Typst text emission. View existence, format, filtering, layout values, and subject-to-primitive dispatch come from F5 graph data.

The pre-F5 projector remains temporarily available as a differential oracle. F5 acceptance requires byte-for-byte/content parity for the default Code, SVG, and Typst views, while a test mutation of F5 view data must alter the rule-driven result without altering the oracle.

There is no `F5.graph` snapshot in the repository. The only successor material is `F5.delta`.
