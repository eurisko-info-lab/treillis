# Rust clean-room bootstrap parity

F0 through F11 are frozen bootstrap foundations. They are not extended by this
milestone. Instead, Trellis gains a second implementation of the constitutional
repository calculus.

The Rust verifier in `rust/trellis-verify` has no runtime dependencies. It
implements only the substrate needed to verify foundation identity:

- UTF-8 byte-length canonical atoms and records;
- strict canonical graph, node, edge, type, and DeltaTrellis decoding;
- SHA-256 content addressing;
- DeltaTrellis application, including live-node pruning on entity replacement;
- graph reference, direction, type, producer, and structural-fanout checks;
- byte-stable F0 construction;
- exact F1 through F11 delta dependency and root verification;
- F11 closure-manifest validation;
- clean-room F0 through F10 reproduction from manifest resources;
- canonical closure-report generation.

It deliberately does **not** implement CESK-R, projection rendering, equality
saturation, DeltaNet lowering, DeltaNet execution, or the parallel scheduler.
Those are semantic graph contents, not requirements for independently checking
the repository/canonical substrate that carries them.

## Acceptance

From the repository root:

```bash
./scripts/verify-bootstrap-parity.sh
```

The script fails unless all of the following hold:

1. Rust unit/adversarial tests pass.
2. Rust reconstructs F0 through F11 from F0 plus the eleven canonical deltas.
3. Every Rust delta ID equals the frozen Scala delta ID.
4. Every Rust F0 through F11 graph root equals the frozen Scala root.
5. Rust validates and replays the F11 closure manifest fail-closed.
6. Scala and Rust emit byte-identical canonical F0 through F10 closure reports.

The expected canonical closure-report content ID is:

```text
01386829dbf602510b5fa8cc09943ad3e150a87469b8da214984ecfe735807ae
```

## Shared hostile fixtures

`src/test/resources/trellis/canon/adversarial/` is consumed by both Scala and
Rust. It includes malformed UTF-8, nonminimal atom lengths, trailing input,
duplicate node keys, unordered node collections, and missing graph references.
Both implementations must reject every fixture.

This is intentionally a cross-process and cross-language test. A failure in one
implementation cannot be hidden by accepting the other implementation's output.

The verifier is distinct from `rust/delta-web`. The verifier independently
reconstructs frozen foundations from canonical wire deltas. The Squeak web
shell deliberately performs no delta decoding or graph assembly; it receives
the selected assembly graph from the Scala image service.
