# Validation

Run:

```bash
sbt compile
sbt "Test/compile"
sbt "Test/runMain trellis.TestMain"
sbt run
```

Expected F10 identities:

```text
F10 delta 3e0c7f3ecf831457b2dba30d6b4ed21702ef9363943d2b976b479ae365895ace
F10 root  ecc80f1c146e1065f62f99811e897bc12c012b4a95e0b10ca02bbd5010fe69dc
```

Expected current graph shape:

```text
nodes        244
edges        263
entities     244
lowerings     12
interactions   7
reductions    12
parallel profiles 12
evidence components 8
```

Expected suite size after F10: **119 tests**.

Critical F10 checks:

1. F10 derives only from frozen F9 plus canonical `F10.delta`.
2. Evidence concepts and replay policy are Trellis graph data connected by typed semantic edges.
3. Repeated certification of the same program/state/graph is byte-identical and content-address identical; strict text/byte decoding round-trips exactly.
4. Certificates bind the foundation root, evidence-policy content id, lowered-net root, and observable state roots.
5. Round certificates reproduce F9 round selection, agent order, dynamic footprints, and confluence verdicts.
6. Exact replay accepts intact certificates and rejects any tampering.
7. Changing F9 scheduling policy changes the certificate and round structure while preserving final observational readback.
8. A certificate created under one graph is rejected under changed semantics.
9. Diagnostic trace ordering does not perturb observable state roots or certificate identity.
