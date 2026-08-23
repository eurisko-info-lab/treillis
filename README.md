# Trellis Bootstrap

Trellis is a graph-native, content-addressed programming substrate. Typed
semantic graphs are canonical; readable `.delta` files describe graph changes,
packages select capabilities, and assemblies build consumer-specific graph
branches. Text, SVG, Typst, and the Squeak-like browser are projections or
editing surfaces rather than competing sources of truth.

## Architecture

```text
frozen F0–F11 foundation
          +
readable, documented, tested feature deltas
          ↓ capability resolution
canonical selection lock
          ↓ assembly compilation
consumer graph branch
          ↓
language package / DeltaNet / CESK-R / Trellis Squeak
```

The implementation separates:

- `trellis.storage`: graphs, changes, branches, publication, packages, profiles,
  and assemblies;
- `trellis.language`: replaceable syntax and lowering contracts;
- `trellis.languages.trellis`: the table-described Trellis parser and printer;
- `trellis.ir`: validated execution IR;
- `trellis.engine`: parallel DeltaNet and sequential CESK-R engines;
- `trellis.squeak`: the assembly-backed live graph image.

Foundation deltas retain their frozen canonical wire encoding because they are
verified before the readable delta language exists. Post-foundation `.delta`
files are human-readable and contain their guide and executable contract.
Optional `.tdc` files are generated exchange artifacts and are never source.

## Requirements

- Scala 3.3.8
- sbt 1.12.15
- JDK 17 or newer
- Rust/Cargo for the independent verifier and Squeak web shell

There are no Scala runtime or test-library dependencies.

## Validate

```bash
sbt "Test/runMain trellis.TestMain"
./scripts/verify-bootstrap-parity.sh
cargo test --manifest-path rust/delta-web/Cargo.toml
```

## Assemblies

List, inspect, or compile graph assemblies:

```bash
sbt "runMain trellis.Main assemblies"
sbt "runMain trellis.Main assembly squeak-debug"
sbt "runMain trellis.Main compile-assembly squeak-debug /tmp/squeak.canon"
```

An assembly requests capabilities and emits a reproducible selected graph. The
IDE consumes that assembled graph; it does not scan source deltas and invent a
product chain.

## Trellis Squeak

Start the local image service and web shell:

```bash
./scripts/run-squeak.sh
```

Open <http://127.0.0.1:8421/>, search for `fib`, and open the Fibonacci System
Browser workspace. Transcript edits accumulate in one temporary open delta.
Commit seals that delta as one immutable change and opens a fresh delta;
Publish separately submits a clean closed frontier to graph-defined publication
policy.

The current Transcript edits graph entities; the workspace runner evaluates
stored Trellis IR through DeltaNet or CESK-R. This is Squeak-like workflow
infrastructure, not yet a Smalltalk parser or virtual machine; see
[SQUEAK-IMAGE.md](docs/SQUEAK-IMAGE.md).

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Assemblies and live branches](docs/ASSEMBLIES.md)
- [Bootstrap ceremony](docs/BOOTSTRAP.md)
- [Delta composition](docs/DELTA-COMPOSITION.md)
- [Readable delta sets](docs/DELTA-SETS.md)
- [Foundation staircase](docs/FOUNDATION.md)
- [Squeak image](docs/SQUEAK-IMAGE.md)
- [Trellis syntax](docs/TRELLIS-SYNTAX.md)
- [Tutorial](docs/TUTORIAL.md)
- [Validation](docs/VALIDATION.md)
