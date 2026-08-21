# Trellis Foundation Staircase

Trellis foundations are derived states, never a sequence of checked-in graph snapshots.

## Frozen F0

F0 is the tiny generic repository/meta substrate constructed by the Scala bootstrap.

```text
F0 root
6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd
```

Changing the F0 construction is a constitutional change and must change the declared foundation lineage rather than silently moving this hash.

## Derived F1

F1 introduces the first explicit semantic schema:

- Type
- Mode
- Capability
- Port
- NodeKind
- EdgeKind
- Graph
- Hole
- Change
- Frontier

These definitions are not Scala enum cases and there is no `F1.graph` snapshot. They are encoded as ordinary Trellis nodes and typed edges by:

```text
src/main/resources/trellis/foundations/F1.delta
```

The canonical change id is:

```text
45c76c2d537927e4f0506696b278d86023bf5b694b0401135a93130b59c56fb4
```

The only legal derivation is:

```text
F0 + F1.delta -> F1
```

whose canonical graph root is:

```text
b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45
```

At startup `Bootstrap` loads the canonical delta resource, strictly decodes it, checks its literal change id, applies it to F0, validates the resulting graph, and checks the literal F1 root.

## Why no successor snapshot

Supplying both `F1.delta` and a serialized F1 graph would weaken the bootstrap contract: the implementation could accidentally load the expected successor instead of deriving it.

Therefore the repository contains only:

```text
F0 construction
F1.delta
```

The F1 graph exists only as the deterministic result of replay.

Future foundations follow the same rule:

```text
F0
 + δ1 -> F1
 + δ2 -> F2
 + δ3 -> F3
 ...
```

Each `δn` is canonical Trellis change data and each successor root is pinned as an independently reproducible fixture.
