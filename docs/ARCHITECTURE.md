# Architecture: repository, languages, IR, and engines

Trellis Bootstrap is split across four explicit boundaries.

```text
language-neutral Repository
          ↓ supplies Graph basis
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
Their frozen graph and delta identities are unchanged by the package split.

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

Historical product graphs remain assembled from canonical deltas, but their
identities are baselines rather than architectural constraints. Reorganization
may deliberately establish new roots and freeze those new roots.

Trellis AST-to-IR lowering is the next missing implementation behind
`TrellisLanguage.lower`; its explicit failure prevents source parsing from
being mistaken for executable lowering.
