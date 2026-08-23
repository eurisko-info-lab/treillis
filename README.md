# Trellis Bootstrap

A deliberately small Scala 3 bootstrap kernel for a graph-native, AI-authored programming system.

Trellis does **not** treat text files as canonical source. The canonical object is a typed semantic graph living in a Pijul-like immutable change DAG. A local project begins as a branch from a published global frontier. Humans navigate and review semantic projections; AI agents author `ΔTrellis` changes.

## Bootstrap boundary

The Scala implementation is intentionally only ten production source files:

1. `Core.scala` — graph, ports, types, capabilities, IDs.
2. `Canon.scala` — canonical encoding and SHA-256 content addressing.
3. `Delta.scala` — free Trellis change language.
4. `Repo.scala` — change DAG, branch frontiers, materialization, publication model.
5. `Check.scala` — graph/resource invariants.
6. `Machine.scala` — tiny CESK-R-style reference resource machine.
7. `Navigate.scala` — semantic selections and navigation.
8. `Project.scala` — SVG, Typst, and code-like projections.
9. `Bootstrap.scala` — initial self-describing semantic universe.
10. `Main.scala` — demo CLI.

The design pressure is intentional: new language features should become Trellis graph data, laws, changes, or derived machines rather than new Scala subsystems.

## Toolchain

- Scala 3.3.8 LTS
- sbt 1.12.15
- JDK 21+
- No runtime/test library dependencies

## Run

```bash
sbt run
sbt "Test/runMain trellis.TestMain"
```

Generate projections:

```bash
sbt "run svg examples/bootstrap.svg"
sbt "run typst examples/bootstrap.typ"
sbt "run dump" > examples/bootstrap.canon.txt
```

## What this proves already

The bootstrap demonstrates the architectural seam, not a complete language implementation:

- generic semantic graph rather than a hard-coded AST;
- immutable content IDs;
- entity lineage distinct from immutable content identity;
- a free semantic change language;
- concurrent-change conflict detection;
- branch materialization from a basis + frontier;
- resource fan-out validation for affine/linear capabilities;
- explicit move/borrow/drop/channel ownership in the reference machine;
- semantic navigation independent of files;
- SVG and Typst as projections with semantic IDs.

See `docs/` for the tutorial, specification, bootstrap contract, and next milestones.
The Squeak-like live graph image and assembly model are described in
[`docs/ASSEMBLIES.md`](docs/ASSEMBLIES.md).
