# Validation note

This bundle was structurally checked in the generation environment:

- 10 production Scala files;
- 7 test Scala files;
- no non-standard-library imports;
- no obvious delimiter imbalance;
- project layout and build metadata present;
- JDK 21 is available.

The environment does **not** contain Scala or sbt, and outbound Maven resolution is unavailable there, so the bundle was not compile-executed during generation. Run the following in a normal networked Scala environment:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

The bootstrap deliberately uses Scala 3.3.8 LTS and no library dependencies, which keeps external validation small.
