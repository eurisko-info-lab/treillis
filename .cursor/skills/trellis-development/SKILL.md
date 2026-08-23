---
name: trellis-development
description: Develop, reorganize, test, document, and commit the Trellis graph-native bootstrap, delta language, storage, assemblies, execution engines, Squeak-like IDE, Agent API, and MCP server. Use for any change to this repository, especially Scala, Rust, .delta, .profile, .assembly, foundations F0-F11, graph contracts, CESK-R, DeltaNet, AgentApi, AgentMcp, or repository architecture.
---

# Trellis Development

## Start with the project model

Read the relevant documentation before changing an architectural boundary:

- `README.md`
- `docs/ARCHITECTURE.md`
- `docs/SPEC.md`
- `docs/DELTA-COMPOSITION.md` for packages, profiles, and selection
- `docs/ASSEMBLIES.md` for consumer graph assembly
- `docs/TRELLIS-SYNTAX.md` for the Trellis language
- `docs/SQUEAK-IMAGE.md` for the live image and open-delta lifecycle
- `docs/AGENT-API.md` for the local Agent API, MCP tools, and workspace persistence
- `docs/FOUNDATION.md` and `docs/BOOTSTRAP.md` for F0-F11

Treat code, graph data, tests, and documentation as one design. Do not preserve
an obsolete boundary merely for compatibility: this project does not maintain
legacy APIs, aliases, formats, generated artifacts, or migration shims.

## Preserve architectural boundaries

- `trellis.storage` owns graphs, changes, repositories, branches, publication,
  package discovery, profiles, selection locks, and assemblies. It must not
  know Fibonacci, Trellis syntax, Smalltalk syntax, or another object language.
- Language packages own parser/printer data, lowering, and language-specific
  execution adapters. Storage must remain usable with Trellis replaced by a
  language such as Unison.
- Execution engines consume graph-stored IR. Do not duplicate a function such
  as Fibonacci in Scala, JavaScript, or another host-language switch.
- The Squeak-like image consumes an assembled graph. It must not scan source
  files or reconstruct a product chain in browser code.
- `trellis.agent` owns the local Agent API, JSON codec, shared runtime,
  workspace persistence, and MCP stdio server. It stages writes through
  `Delta.Op` and must not invent a second mutation vocabulary.
- Keep general mechanisms in code and domain definitions in graph data.
  Reject product-name switches and hard-coded entity lists when discovery can
  derive the same information.

## Work with graph identity correctly

- An `EntityId` is a stable, human-facing, path-like graph name.
- A `ContentId` identifies immutable canonical content and may change when the
  content changes.
- A `ChangeId` identifies a canonical immutable change and belongs to repository
  provenance, not package composition.
- Never use a content or change UID as a package `requires`, `imports`,
  `provides`, `use`, or `expose` value.
- Composition contracts use `entity.path` for a typed node and
  `entity.path#port` for a particular typed connection point.
- Resolve declared endpoints and preserve the graph's complete port `Ty`,
  direction, capability, mode, and state. Do not introduce a parallel string
  type system.
- Let discovery fail on malformed paths, absent or ambiguous providers,
  nonexistent nodes or ports, false exports, and input ports claimed as exports.
- Let assembly recheck selected and exposed endpoints against its materialized
  graph. Do not treat manifest claims as sufficient evidence.

## Author deltas as source

- Human-readable `.delta` files are authoritative after the foundations.
- Do not commit generated `.tdc` or treat compiled output as source.
- Keep foundation wire deltas canonical where the bootstrap contract requires
  them. F0-F11 identities and roots are constitutional fixtures, not ordinary
  product metadata.
- Post-foundation deltas should be grouped by functional concern: generally one
  language package, execution feature, storage feature, optimization feature,
  or example package.
- Put durable documentation in `doc` attributes and executable acceptance in
  embedded `test` blocks. Prefer multiline strings with the `|` margin form.
- Do not record branch-dependent graph roots, node counts, or edge counts in a
  reusable delta. A different assembly or cherry-pick legitimately changes them.
- Use path dependencies between packages. Keep exact `ChangeId` dependencies
  only where immutable repository replay requires them.

## Change foundations deliberately

F0-F11 are frozen outputs, not untouchable source history. If a coherent
reorganization must cross the foundation boundary:

1. Unfreeze the affected staircase explicitly.
2. Modify the earliest appropriate foundation.
3. Regenerate every affected successor delta, change identity, graph root, and
   closure witness in order.
4. Update Rust parity fixtures and all foundation tests.
5. Re-run clean-room reproduction through F11.
6. Refreeze only after canonical replay and cross-process verification pass.

Never patch a frozen hash or expected root merely to silence a failing test.

## Parser and printer work

- Treat grammars as graph data interpreted by generic lexer/parser/printer
  machinery. Do not add construct-specific recursive-descent branches when the
  grammar table can express the construct.
- Trellis function bodies and match cases use indentation.
- Parse/print must round-trip canonically and diagnostics must identify the
  relevant source location.
- Pretty-printed text, SVG, and Typst are projections generated from the same
  reachable graph, not pregenerated example assets.

## Squeak-like image behavior

- A workspace is the graph reachable from its selected node; it is not a
  separately hard-coded Fibonacci workspace.
- Search discovers entities. Selecting a result makes opening its workspace
  available.
- Display the local name in a workspace and move namespace prefixes into
  breadcrumbs.
- Keep text and graph views synchronized around semantic selections.
- Attribute nodes may be hidden by a view toggle but remain graph data.
- The live image is `assembled basis + closed local frontier + open delta`.
- Transcript edits append operations to the open delta. Commit validates and
  seals it; publication separately attaches a clean closed frontier.
- Do not claim Smalltalk evaluation exists unless graph-defined Smalltalk syntax,
  lowering, and runtime execution are actually wired. The current Trellis IR
  runner is not a Smalltalk VM.

## Agent API, MCP, and persistence

- Prefer filtered query endpoints (`/entities`, `/entity`, `/navigate`,
  `/history`) over dumping `/workspace/graph` when answering or editing.
- Writes always go through `Delta.Op` via `POST /workspace/ops` or MCP
  `apply_ops`. Do not mutate closed history in place.
- Semantic targets use `EntityId` paths. Use content ids only for
  `connect` / `disconnect` / `bindEntity` after reading them from the graph.
- `AgentRuntime` is shared by `SqueakServer` and `AgentMcp`. Do not run both
  concurrently against the same workspace file.
- Persist open delta, sealed frontier, and stored changes to
  `.trellis/workspace.json` (override with `TRELLIS_WORKSPACE`). Save after
  mutations and on exit; restore on startup. Never commit `.trellis/` artifacts.
- Cursor MCP is configured in `.cursor/mcp.json` and launched by
  `scripts/run-trellis-mcp.sh`, which must locate JDK/`sbt` even under a minimal
  PATH.
- Keep Agent API behavior documented in `docs/AGENT-API.md` without overstating
  persistence or Smalltalk support.

## Verify every implementation

Run the checks proportional to the change. Before handing off a substantive
change, run the full project test runner:

```bash
sbt "Test/runMain trellis.TestMain"
```

For assembly changes also run:

```bash
sbt "runMain trellis.Main assembly squeak-debug"
sbt "runMain trellis.Main compile-assembly squeak-debug /tmp/squeak.canon"
```

For foundation or canonical-codec changes also run:

```bash
cargo test --manifest-path rust/trellis-verify/Cargo.toml
```

For Agent API / persistence / MCP changes also cover:

- `AgentApiTest` and `WorkspacePersistenceTest` via `trellis.TestMain`
- `git diff --check`

Always run:

```bash
git diff --check
```

Do not report `sbt test` as sufficient: this repository's suites are executed by
`trellis.TestMain`.

## Keep changes and history clean

- Inspect `git status` before editing and preserve unrelated user changes.
- Make cohesive changes; update affected docs and embedded tests with the code.
- Do not add compatibility wrappers when reorganization is cleaner.
- Do not commit generated `.tdc`, assembled `.canon`, server output, temporary
  workspace deltas, or `.trellis/` snapshots.
- Use `graph(<feature>): ...` for commits containing graph/delta content only.
- Use `feat(<scope>): ...` when a commit includes runtime, tooling, parser, IDE,
  or other code as well as graph data.
- Use `fix(<scope>): ...`, `docs: ...`, and `refactor(<scope>): ...` when those
  accurately describe the change.
- Before committing, inspect the staged diff and run `git diff --cached --check`.

## Completion criteria

A task is complete only when the requested behavior is implemented through the
correct graph/runtime boundary, relevant contracts and failure cases are tested,
documentation describes the actual behavior without overstating it, validation
passes, and the working tree contains no accidental generated artifacts.
