# Architecture: repository, languages, IR, and engines

Trellis Bootstrap has five explicit boundaries.

```text
language-neutral Repository
          ↓ supplies Graph basis and branch
capability packages + Assembly
          ↓ select one consumer graph
replaceable LanguagePackage
          ↓ produces versioned LoweredProgram
       Execution IR
          ↓ accepted by capability
replaceable ExecutionEngine
```

## Storage

`trellis.storage.Repository` owns generic graphs and deltas. It imports no
parser, concrete language, IR evaluator, or execution engine. The existing
content-addressed repository, branches, publication ledger, replicated CAS,
signatures, consensus, discovery, and attestations remain storage products.
Only F0–F11 carry host-checked frozen identities. Post-foundation roots are
computed properties of a selected branch, not constants embedded in feature
documentation.

## Languages

`trellis.language.LanguagePackage` gives each language an identity, version,
bidirectional `SyntaxCodec`, and typed lowering boundary. Concrete Trellis
syntax lives under `trellis.languages.trellis.TrellisLanguage`. Callers select
a language package explicitly; there is no root-level syntax facade.

A Unison integration would implement the same package contract with Unison's
own parser, syntax tree, name/type semantics, and lowering. Storage would not
change. It may lower to an installed shared IR or provide another IR and engine.

## Execution IR

`trellis.ir.ExecutionIr` identifies the currently executable Nat IR as
`trellis.ir.nat/v1` and validates its reachable `ir.*` vocabulary before engine
dispatch. Source-language ASTs are not execution IR.

## Engines

`trellis.engine.ExecutionEngine` is the common execution contract.
`DeltaNetEngine` and `CeskrEngine` advertise accepted IR versions and return the
same engine-neutral result shape. The Squeak runtime resolves engines through
`Engines`, not through syntax, `Machine`, or `Language` objects.

## Product assembly

Packages provide and require capabilities. Profiles establish reusable
selection policies. An assembly names a foundation, inherits a profile, adds or
omits capabilities, declares consumer entry points, requests verification, and
chooses emitted artifacts. Resolution produces a canonical lock; compilation
reissues selected source changes over a deterministic frontier.

The Squeak image receives the resulting graph and lock. The web shell no longer
scans `.delta` sources or assumes that every feature belongs to one linear
product history.

## Live branches

A Squeak workspace consists of an assembled basis, a local immutable change
frontier, and one mutable open delta. Reads and execution see the overlay.
Commit validates and seals the open delta, advances the local frontier, and
opens a clean delta. Publication is separate and requires a clean workspace.

The worked Fibonacci definition is stored as graph-resident IR and is consumed
by both engines. General Trellis AST-to-IR lowering remains a separate typed
phase; source parsing must not be mistaken for lowering or execution.
