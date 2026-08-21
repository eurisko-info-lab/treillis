# Trellis foundation staircase

Trellis foundations are derived, never supplied as successor snapshots.

```text
F0
  + F1.delta
  = F1

F1
  + F2.delta
  = F2
```

Frozen identifiers:

```text
F0 root
6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd

F1.delta
45c76c2d537927e4f0506696b278d86023bf5b694b0401135a93130b59c56fb4

F1 root
b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45

F2.delta
36a8f04e97463c76b74f176c574aa5910099b6d9c562a22e56785bd822488de1

F2 root
09cc9ba6664b4e8dd84e4937a2b0cd63ea0863e9c2fdbcdeea27284f89da9496
```

## F2: resource calculus

F2 moves the concrete resource policy out of Scala conditionals and into Trellis graph data.

It defines:

- structural modes `unrestricted`, `affine`, and `linear`;
- capability kinds `pure`, `own`, `read`, `write`, and `suspended`;
- resource operations `move`, shared/mutable borrow, `end-borrow`, `drop`, `replicate`, and `erase`;
- ten graph-defined resource rules;
- first-class edges connecting rules to operations, modes, and capabilities.

The Scala bootstrap retains only a tiny generic rule interpreter. A rule is matched using generic attributes such as:

```text
port.in.mode = unrestricted
port.owner.capability = own
port.loan.capability = read
same-inner = owner,loan
result = allow | lower-drop
```

Thus `core.replicate` is not special-cased by the host. F2 states that its `in` port must be unrestricted. Affine erasure is likewise described by F2 as `lower-drop`.

No `F2.graph` file exists. The only successor artifact is `F2.delta`.
