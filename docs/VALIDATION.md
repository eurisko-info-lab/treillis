# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

Expected F9 identities:

```text
F9 delta ccc41dcdba399dfa2c2fb016dff47e4762ccd72b619c4cf2f6e4e85b94dcd8ac
F9 root  f572b5243f38cfefd2eff2eb82c2cdd75173ee3fd900642451c50cf51c7dcce0
```

Expected current graph shape:

```text
nodes       235
edges       243
entities    235
lowerings    12
interactions  7
reductions   12
parallel profiles 12
```

Expected suite size after F9: **107 tests**.

Critical F9 checks:

1. Independent allocations are packed into one deterministic maximal round.
2. Allocation, borrow, end-borrow, and drop remain ordered by readiness and dynamic conflicts.
3. Every accepted parallel round commutes under stable versus reverse local replay modulo trace order.
4. F9 parallel execution is observationally equal to F8 sequential execution.
5. Switching the graph policy to singleton scheduling changes round structure but not readback.
6. Changing an F9 footprint profile changes conflicts without changing F8 execution.
