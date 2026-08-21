# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

Expected F5 identities:

```text
F5 delta  d6fb1fb29f9864cbd8062af1b066270883aa0efcbe8dc405dfd17935fd091368
F5 root   3516c065b71cce1667a5075625deea2ee88f0e58365ccc21d215e86127b3aab1
```

The suite must establish:

- canonical F0-F5 staircase derivation;
- F5 depends exactly on F4;
- projection components, views, rules, and relationships are graph data;
- six views and ten render rules are discovered from F5;
- Code, structure SVG, and Typst rule-driven projections match the pre-F5 oracle;
- changing F5 projection data changes driven rendering without changing the oracle;
- specialized ownership/process/machine SVG views are selected by graph-defined filters and retain semantic identifiers.

Useful projection commands:

```bash
sbt "run svg"
sbt "run svg-ownership"
sbt "run svg-process"
sbt "run svg-machine"
sbt "run typst"
```
