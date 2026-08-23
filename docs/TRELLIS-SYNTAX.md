# Trellis concrete syntax

Trellis now has a concrete source grammar and AST rather than relying on the
Squeak's pretty-printer as an implicit language definition.

The grammar is data: token punctuation, keywords, categories, constructors,
and their element sequences live in `TrellisLanguage.grammar`. One generic lexer
and one generic parser interpret those tables. `Tree(tag, fields)` is the
syntax-independent CST/AST carrier, and a table-driven printer provides a
canonical source representation.

The first language slice covers modules, typed functions and parameters,
references, calls, addition, constructor patterns, and matches. It is enough to
represent the complete tail-recursive Fibonacci example:

```trellis
module example.tailrec {
  fn fibonacci(n: Nat) -> Nat =
    loop(n, Zero(), Succ(Zero()));
  fn loop(remaining: Nat, current: Nat, next: Nat) -> Nat =
    match remaining
      Zero -> current
      Succ(rest) -> loop(rest, next, current + next)
}
```

The canonical invariant is `parse(print(parse(source))) == parse(source)`.
Future syntax forms extend the grammar and print-rule data, not the parser.

This closes the concrete-syntax/AST gap. Lowering this AST into graph-resident
`ir.*` entities is deliberately a separate typed phase; execution continues to
consume graph IR rather than syntax trees.

The worked Fibonacci workspace already contains its complete lowered IR and is
therefore executable through both DeltaNet and CESK-R. That example does not
make general `TrellisLanguage.lower` complete.

Trellis syntax is also independent of the Squeak interaction model. The current
Squeak Transcript can stage graph edits and execute stored Trellis IR, but it
does not yet define or evaluate Smalltalk syntax.
