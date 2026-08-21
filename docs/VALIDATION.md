# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

The foundation tests require literal hashes for F0, F1.delta, F1, F2.delta, F2, F3.delta, and F3.

F3 validation additionally checks that:

- F3 depends exactly on F2.delta;
- F3 is reconstructed only from F2 + F3.delta;
- process kinds, capabilities, operations, and process rules are graph data;
- process relationships are first-class typed edges;
- send/receive/process-handle modes are read from F3 capability nodes;
- unrestricted send preserves sender access while affine send transfers ownership;
- an empty receive blocks and a later send wakes the receiver;
- affine captures move to spawned children while unrestricted captures are shared;
- process termination drops affine resources and rejects live linear obligations;
- join transfers the child result and consumes the affine process handle;
- a channel can carry another channel endpoint, establishing capability mobility;
- process transition choices are interpreted from F3 rule data rather than operation-specific Scala policy.

There must be no checked-in F1, F2, or F3 graph snapshot.
