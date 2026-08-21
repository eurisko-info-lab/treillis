# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

The foundation tests require literal hashes for F0, F1.delta, F1, F2.delta, and F2.

F2 validation additionally checks that:

- F2 depends exactly on F1.delta;
- F2 is reconstructed only from F1 + F2.delta;
- modes, capabilities, operations, and resource rules are graph data;
- rule relationships are first-class typed edges;
- the generic checker reads structural permissions from F2 mode nodes;
- unrestricted replication is accepted and affine replication rejected from rule data;
- affine erase resolves to the graph-defined `lower-drop` disposition;
- shared-borrow capability shape is checked from graph-defined constraints.

There must be no checked-in F1 or F2 graph snapshot.
