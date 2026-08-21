# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

Expected F6 identities:

```text
F6 delta  1200106d29fc3cb9ce27647803db8339b3ca66cfdca83abf95756833713ebc20
F6 root   478974e6ac4c8767a64fecb00835b0505368c6140f1eac22f9cc618a3666bba1
```

The suite must establish:

- canonical F0-F6 staircase derivation;
- F6 depends exactly on F5;
- equality components, invariants, laws, costs, policy, and relationships are graph data;
- the default rewrite policy requires preservation of type, resource, effect, and protocol semantics;
- equality closure is symmetric/transitive and recursively congruent;
- rewrites that omit required resource preservation do not enter an e-class;
- structural-mode guards are interpreted from rewrite graph data;
- proof-required policy blocks rewrites without evidence;
- extraction uses graph-defined multi-objective costs;
- changing graph policy or cost data changes saturation/extraction without changing Scala mechanics.

Expected suite size after this slice: **75 tests**.

Useful foundation commands:

```bash
sbt "run hash-f6"
sbt "run delta-f6"
sbt "run dump-f6"
```
