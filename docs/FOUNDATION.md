# Foundation staircase

## Frozen roots

```text
F0   6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd
F1   b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45
F2   09cc9ba6664b4e8dd84e4937a2b0cd63ea0863e9c2fdbcdeea27284f89da9496
F3   c565d28a992289608c45fac5ace462d1b2e05059ae83bfb5507c25bad311cc1c
F4   616a960470e389c665ab94280b70bb5c7e203ba3b78cdf0b373948a0adf60847
F5   3516c065b71cce1667a5075625deea2ee88f0e58365ccc21d215e86127b3aab1
F6   478974e6ac4c8767a64fecb00835b0505368c6140f1eac22f9cc618a3666bba1
F7   efcbbe6b6f335ebfcf67a1894d51aef35869d54e94da77b26b4700c68660750b
F8   7a49a1579c84b4a2c1d6613de4d8d14a8eff180d55e85108f7a7ffc13d5136d1
F9   f572b5243f38cfefd2eff2eb82c2cdd75173ee3fd900642451c50cf51c7dcce0
F10  ecc80f1c146e1065f62f99811e897bc12c012b4a95e0b10ca02bbd5010fe69dc
```

## Frozen F10 change

```text
3e0c7f3ecf831457b2dba30d6b4ed21702ef9363943d2b976b479ae365895ace
```

F10 depends exactly on F9.

## F10 canonical execution evidence

F10 adds eight evidence concepts and one evidence policy:

```text
deltanet.execution-certificate
deltanet.round-certificate
deltanet.redex-certificate
deltanet.net-root
deltanet.state-root
deltanet.readback-root
deltanet.replay
deltanet.verifier

deltanet.policy.evidence
```

The relationships among these concepts are first-class typed graph edges. An execution certificate binds:

- the complete current foundation graph root,
- the content id of the F10 evidence policy,
- the canonical lowered DeltaNet program root,
- the observable initial state root,
- every round's before and after state roots,
- each selected agent and its F8 reduction rule,
- the dynamic F9 footprint of every selected redex,
- the local confluence verdict for every round,
- the final/readback root.

The certificate is content-addressed with SHA-256 over a language-neutral `Canon.record` encoding. F10 provides strict canonical text and UTF-8 byte decoders, so certificates can cross process/language boundaries rather than existing only as JVM values. Round certificates are ordered by stable round index; redex certificates are ordered by stable agent id; touch keys are sorted before encoding.

Observable state roots include the current process, process table, resources and loans, channels, and waiting queues. Diagnostic trace order is intentionally excluded, because F9 defines confluence modulo trace ordering.

Verification is `replay-exact`: the verifier reruns graph-defined F7 lowering, F9 scheduling/footprints/confluence, and F8 independent reduction from the supplied program and initial state. It accepts only if the canonical regenerated certificate is exactly equal to the supplied certificate.

This means a certificate is bound to both program and semantics. Changing scheduler policy, footprints, reduction rules, or any other graph content changes the foundation root and therefore changes or invalidates the certificate even when final readback remains observationally equal.
