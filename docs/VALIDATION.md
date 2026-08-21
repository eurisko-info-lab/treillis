# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

F4 acceptance requires all earlier canonical/repository/resource/process tests plus:

- `F4.delta` depends exactly on `F3.delta`,
- F4 is reconstructed only from F3 plus F4.delta,
- machine state components are graph data,
- all twelve machine rules are graph data,
- machine component and operation relationships are typed edges,
- every reference-machine instruction kind has exactly one F4 dispatch rule,
- rule-driven execution stays equal to the pre-F4 direct oracle on representative resource/process programs,
- changing F4 dispatch data changes rule-driven admissibility while leaving the direct oracle unchanged.

The direct oracle is temporary bootstrap scaffolding. It should be removed only after the graph-defined transition language is independently strong enough to replace it.
