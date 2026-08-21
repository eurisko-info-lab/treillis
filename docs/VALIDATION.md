# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

## Foundation fixtures

Expected F0 root:

```text
6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd
```

Expected F1 delta id:

```text
45c76c2d537927e4f0506696b278d86023bf5b694b0401135a93130b59c56fb4
```

Expected F1 root:

```text
b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45
```

The tests must establish that:

1. F0 still hashes to its frozen literal root.
2. `F1.delta` strictly decodes and re-encodes byte-identically.
3. Its change id equals the frozen literal id.
4. Applying that delta to F0 produces F1 byte-identically.
5. The derived F1 graph equals the frozen literal F1 root.
6. F1's schema is represented by Trellis graph data and typed graph edges.
7. No checked-in F1 graph snapshot is needed.
8. Replacement of an entity removes obsolete unreferenced node content from the materialized graph, leaving history to the change/CAS layer.

A later independent implementation, for example Rust, must reproduce all three fixture values from the same canonical data.
