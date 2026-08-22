# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
sbt "run closure"
```

F11 acceptance requires:

- `F11.delta` strictly decodes and has the frozen change id,
- its only dependency is `F10.delta`,
- applying it to F10 produces the frozen F11 root,
- all closure vocabulary and ten derivation steps are graph data,
- the closure manifest has exactly the ordered F1..F10 staircase,
- each step consumes the previous successor root and delta dependency,
- all deltas are loaded from canonical `.delta` resources,
- no `F1.graph` through `F11.graph` successor snapshot resource exists,
- clean-room reproduction from F0 reaches exactly the frozen F10 root,
- two independent clean-room reproductions produce byte-identical canonical reports,
- tampering a manifest step is rejected,
- validation has no skip or permissive fallback path.
