# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

Expected F8 identities:

```text
F8 delta 357603f917a830c5ff785c1bbc78e961d2389e9b1bc80e9a2af7a861e7cc69a2
F8 root  7a49a1579c84b4a2c1d6613de4d8d14a8eff180d55e85108f7a7ffc13d5136d1
```

Expected current graph shape:

```text
nodes      215
edges      215
entities   215
lowerings   12
interactions 7
reductions  12
```

Expected suite size after F8: **96 tests**.

The critical F8 differential checks are:

1. F8 DeltaNet and CESK-R produce byte-for-Scala-value identical `State` results over all current primitive families.
2. Corrupting F4 dispatch breaks CESK-R while F8 DeltaNet still executes.
3. Corrupting an F8 reduction rule changes DeltaNet while CESK-R remains unchanged.
4. F8 runtime reduction policy is entirely graph-defined and rejects missing preservation evidence or invariants.
