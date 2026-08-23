# Trellis bootstrap

The host remains a deliberately small Scala 3.3.8 mechanics layer. The canonical program is the Trellis graph.

The frozen staircase is now closed through F11:

```text
F0
 + F1.delta = F1
 ...
F9
 + F10.delta = F10
F10
 + F11.delta = F11
```

No successor graph snapshot is checked in.

F11 is the closure declaration. It contains a graph-resident manifest for the ten predecessor-plus-delta derivations F0 -> F10. Each step binds its predecessor root, canonical delta id, exact dependency, successor root, resource name, and `snapshot=forbidden`.

`Bootstrap.cleanRoomReproduce` starts again at F0, strictly decodes each bundled delta, verifies its hash and dependency, applies it, validates the derived graph, and requires the exact frozen successor root. Any missing, malformed, reordered, skipped, or tampered step fails closed.

The generated closure report is canonical and content-addressed. F11 itself is not listed inside its own manifest, which avoids a self-referential F11 root while preserving the ordinary `F10 + F11.delta = F11` derivation.

F11 is also the boundary between encodings. Foundations must be authenticated
by the zero-dependency verifier and therefore remain canonical wire `.delta`
resources. Readable symbolic `.delta` syntax, embedded documentation/tests,
capability packages, and assemblies live above F11 and cannot be pulled into
the ceremony without creating a bootstrap cycle.
