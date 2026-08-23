# Human-readable delta sets

Foundation deltas F1 through F11 retain their frozen canonical wire encoding.
Post-foundation products are organized as readable capability packages:

- `Trellis.delta` — one concrete language package;
- `Ceskr.delta` — optional sequential execution and traces;
- `Navigation.delta` — the current navigator protocol layer;
- `Optimize.delta` — optimization and executable lowering;
- `OptimizeDifferential.delta` — optional CESK-R/DeltaNet differential certification;
- `Storage.delta` — publication, CAS, consensus, discovery, and attestation;
- `Studio.delta` — navigation, projections, and review;
- `StudioRuntime.delta` — LSP and runtime UI after storage is available;
- `Fibonacci.delta` — one worked example.

The format is intentionally small and canonical:

```text
delta-package package.name
  purpose human readable description
  requires prerequisite.capability
  provides package.capability
  include source/OrderedFragment.delta
    post-action validate-graph
```

Small packages may define graph data inline instead of including a compiled
fragment:

```text
delta-package common.lambda
  provides language.core.lambda
  change add common Lambda Calculus
    entity common.lambda.schema language.package
      attr name Lambda Calculus
    post-action validate-graph
```

Inline changes are compiled to the same canonical `Change` representation as
included fragments. Package imports therefore operate on graph data and
capabilities, not Scala inheritance.

Includes are relative, ordered, and may not be absolute or contain `..`.
Package includes point directly at authoritative `.delta` files. The
Scala catalog compiles every fragment in memory, derives dependency order,
replays the chain from F11, and validates each result. No `.tdc` artifact is
checked into the repository or loaded by application code.

Every fragment is authored under `trellis/products/source` in the symbolic,
indentation-based `.delta` language. Sources name entities and edge
endpoints directly, so humans and AI tools need not synthesize content hashes.

Documentation and tests are first-class source declarations rather than
companion files:

```text
doc "guide" "Design rationale, examples, and operational notes."
test "canonical delta contract"
  assert "canonical"
  assert "valid"
  assert "source-roundtrip"
  assert "dependency" "predecessor-delta"
  assert "introduces" "feature.schema" "feature.kind"
```

Long documentation uses triple-quoted block strings. A leading margin marker is
removed automatically, so Markdown remains readable inside the delta:

```text
doc "guide" """
  |# Feature
  |
  |The guide lives with the change it describes.
  """
```

`doc` values survive semantic parse/print round-trips and are available to
Studio tooling.
The generic delta-test interpreter executes every indented assertion without a
product-name switch. Supported contracts cover canonical replay,
constitutional validity, source round-tripping, dependencies, and introduced
or defined entities.
Running `scripts/compile-delta-sources.sh` emits optional canonical `.tdc`
exchange artifacts under `target/generated-deltas` (or
`TRELLIS_GENERATED_DIR`). These are disposable build products. Use
`product-source NAME` to print an authoritative source from the generic CLI.

## Foundation boundary

Post-foundation `.delta` files use this readable language. F1–F11 retain their
canonical wire encoding because the zero-dependency verifier must authenticate
them before the readable language, its lexer, or its test interpreter exists.
Making F1 depend on a language introduced above F11 would create a bootstrap
cycle and replace every frozen identity. The shared `.delta` suffix therefore
means “authoritative change”; its encoding is selected by the constitutional
boundary, not by legacy compatibility.

Post-actions belong to the include they follow. They are executed after that
change has been applied, and unknown actions fail loading. This keeps lifecycle
policy in resource data: Scala does not select validation behavior from product
names or version numbers. `validate-graph` runs the constitutional graph checker.

`CompositionCatalog` decodes these resources into packages and loads profiles
from `trellis/profiles`. The `full` profile resolves all current fragments using
capabilities alone and produces a canonical selection lock.

This arrangement keeps the frozen change history and byte-level parity while
making the maintained product structure readable through nine chain-aligned
capability packages.

Only the foundational F0–F11 ceremony remains named and frozen in host code.
Post-foundation hashes and graph roots are observations computed by the catalog,
not constants that must be synchronized with resources.
