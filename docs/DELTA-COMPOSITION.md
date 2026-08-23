# Capability-directed delta composition

## Implemented slice

`Composition.resolve` now performs deterministic capability closure, profile
inheritance, provider selection, import closure, conflict checks, package
topological ordering, and canonical lock generation. `ManifestLanguage`
describes directive shapes as data and provides one parser/printer used by the
product catalog. Tests cover production/debug selection, shared Lambda imports,
ambiguous providers, deterministic locks, and manifest round-tripping.

`SelectionApplication.materialize` verifies selected payload hashes, proves
that every content dependency is supplied by the basis or selected closure,
topologically applies only selected changes, and invokes injected post-action
handlers. Executable tests materialize separate production and debug graphs:
the production graph physically lacks the CESK-R entity while the debug graph
contains both engines.

The first physical split is complete. `production` materializes the actual
LanguageBootstrap–SessionProtocols changes over F11 and omits CeskrTransitions/17 CESK-R and trace
changes. `debug` extends that selection with CeskrTransitions/17. The F11 DeltaNet
capability is recorded explicitly as basis-provided in the production lock.

`production-ide` now selects 34 of the 37 real source fragments, omitting
CeskrTransitions, CeskrTraces, and DifferentialCertificates. `SelectionCompiler` deterministically
reissues selected operations over the selected predecessor frontier, validates
every intermediate graph, and records every source-ID to compiled-ID derivation.
The resulting graph contains SqueakNavigator, Squeak runtime, storage, and Fibonacci
while physically excluding CESK-R, trace, and differential-certificate nodes.
Replaying the compiled lock reproduces its graph root.

The CLI exposes `profiles`, `profile NAME`, and `compile-profile NAME` without
profile-specific branches.

`common.lambda` is now an inline, human-readable delta package. The manifest
grammar's generic `change`, `entity`, and `attr` constructs compile it into a
canonical change; Trellis imports `language.core.lambda`. Production, debug,
full, and production-IDE locks select the shared package exactly once, and their
materialized graphs expose qualified `common.lambda.*` entities.

Delta sets evolve from ordered include manifests into reproducible package
compositions. A profile requests capabilities; it does not name implementation
files to remove. The resolver selects providers, closes their imports and
requirements, checks conflicts, and emits a canonical lock containing exact
change IDs and deterministic application order.

## Source model

```text
delta-package common.lambda
  provides language.core.lambda
  include source/Lambda.delta
    post-action validate-graph

delta-package language.trellis
  imports language.core.lambda
  provides language.trellis.syntax
  provides language.trellis.lowering
  include source/TrellisSyntax.delta
    post-action validate-graph
  include source/TrellisLowering.delta
    post-action validate-graph

delta-package execution.deltanet
  requires language.trellis.lowering
  provides execute.parallel
  include source/DeltaNet.delta
    post-action validate-graph

delta-package execution.ceskr
  requires language.trellis.lowering
  provides execute.sequential
  provides debug.trace
  include source/Ceskr.delta
    post-action validate-graph

profile production
  require language.trellis.syntax
  require execute.parallel

profile debug
  extends production
  require execute.sequential
  require debug.trace
```

Resolving `production` excludes CESK-R naturally: no requested capability has
the CESK-R package as its selected provider. Resolving `debug` adds it. A
deployment can request several language syntax/lowering packages while sharing
one `language.core.lambda` provider.

## Resolution

1. Expand profile inheritance.
2. Select exactly one provider for every required capability unless the
   capability is explicitly declared multi-provider.
3. Add package `imports` and `requires` transitively.
4. Reject missing providers, ambiguous providers, conflicts, and dependency
   cycles with an explanation path.
5. Topologically order selected fragments, with change ID as the deterministic
   tie-breaker for independent fragments.
6. Apply each change and its declared post-actions.
7. Emit a canonical selection lock recording the profile, capability-to-provider
   decisions, exact change IDs, order, post-actions, and resulting graph root.

The lock—not the source profile—is the exchange/reproduction artifact. Profile
guidance may choose among valid providers using size, latency, platform, or
observed execution data, but every choice becomes explicit in the lock.

## Required delta refactor

The current post-foundation products form one predecessor chain. Optional
selection requires orthogonal fragments instead:

- common Lambda Calculus data and laws;
- Trellis syntax and lowering;
- DeltaNet execution;
- CESK-R execution and tracing;
- Squeak navigation;
- debugger UI;
- examples.

Fragments must depend only on the bases they actually consume. Independent
fragments may have several content dependencies and must compose without
overlapping writes, unless an explicit deterministic merge policy admits the
overlap.

## Language imports

A language import is graph data, not a Scala inheritance relationship. Imported
definitions retain stable qualified identities such as `common.lambda.apply`.
The importing language may expose aliases in its own namespace, while ownership,
provenance, and conflict checking continue to refer to the qualified identity.
Printers can shorten names in a workspace when the import environment makes the
short form unambiguous.

## Grammar implementation

The growing manifest syntax must be represented as token, constructor, category,
and print-rule data interpreted by one generic lexer/parser/printer. Adding
`profile`, `provides`, `requires`, `imports`, or future selection constraints
must add grammar entries rather than another directive-specific parser branch.
Round-trip identity and static checks for left recursion and ambiguous providers
are part of validation.
