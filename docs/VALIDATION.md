# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

Expected F7 identities:

```text
F7 delta  b1e91c7e639bd57a1e968927a901e3f694749d1f4d67cf16a5c19c57be72bff9
F7 root   efcbbe6b6f335ebfcf67a1894d51aef35869d54e94da77b26b4700c68660750b
```

Expected materialized F7 size:

```text
nodes      196
edges      181
entities   196
lowerings   12
interactions 7
```

The suite must establish:

- canonical F0-F7 staircase derivation;
- F7 depends exactly on F6;
- DeltaNet components, agent kinds, lowerings, interactions, policies, and relationships are graph data;
- the twelve F4 instruction kinds are covered by graph-defined lowering rules;
- lowering rules preserve every invariant required by the F7 execution policy and carry evidence;
- stable-agent scheduling and CESK-R readback are selected from graph data;
- changing an F7 lowering changes the produced net without changing CESK-R host mechanics;
- DeltaNet lowering/readback stays in parity with F4 CESK-R for the covered instruction fragment;
- unrestricted duplication reduces through a replicator;
- affine and linear duplication are rejected;
- unrestricted erasure and affine drop reduce through the eraser policy;
- linear erasure is rejected;
- send/channel, receive/channel, spawn/process, and join/process active-pair actions are graph-defined;
- removing required resource-preservation evidence can reject DeltaNet lowering while leaving CESK-R unchanged.

Expected suite size after this slice: **87 tests**.

Useful foundation commands:

```bash
sbt "run hash-f7"
sbt "run delta-f7"
sbt "run dump-f7"
```
