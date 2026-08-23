# Graph assemblies and Squeak images

An assembly selects a graph branch for a consumer. It does not prescribe a
historical product chain. The resolver closes capability dependencies and emits
a canonical selection lock; the compiler reissues selected deltas over the
declared foundation in deterministic order.

```text
assembly squeak-debug
  foundation F11
  base production-ide
  use execute.sequential
  use debug.trace
  expose workspace.browser
  expose workspace.transcript
  expose workspace.inspector
  verify embedded-tests
  verify graph-valid
  emit graph
  emit selection-lock
```

Assembly syntax is described by the shared manifest grammar table and consumed
by its generic parser and printer. `parse(print(assembly))` is tested. The
current catalog contains `squeak-debug`; list and compile assemblies with:

```sh
sbt "runMain trellis.Main assemblies"
sbt "runMain trellis.Main assembly squeak-debug"
sbt "runMain trellis.Main compile-assembly squeak-debug /tmp/squeak.canon"
```

## Live image lifecycle

Trellis Squeak opens an assembly as a local graph branch. Browser and evaluator
reads use the branch plus its current open delta. Transcript edits append
operations to that temporary delta and do not mutate closed history.

Committing validates and seals the complete open delta as one immutable,
content-addressed change, advances the local branch, and opens an empty delta.
Publishing is a separate operation: only a clean workspace may attach its
closed frontier to a central graph publication. Publication policy, namespace,
publisher admission, signatures, and conflicts remain graph-defined.

```text
assembly graph → local branch → open delta
                         commit ↓
                    closed change → publish → central branch
```

Start the local image and browser with `scripts/run-squeak.sh`.

The assembly's `verify` and `emit` declarations are presently retained in the
typed recipe but are not yet incorporated into the canonical selection-lock
identity. Graph validation is wired into assembly compilation, and the CLI can
emit a canonical graph. Dispatching every named verification/output through a
registered backend—and binding those decisions into assembly provenance—is a
remaining extension rather than silently interpreted behavior.
