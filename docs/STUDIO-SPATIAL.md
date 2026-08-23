# Studio spatial syntax

Studio presents Trellis objects as one structured space rather than parallel
text and graph documents. Text is the dense glyph for a semantic subgraph;
containers, boundary ports, and wires expose structure that text normally
hides.

## Visual grammar

- A scope or region is a container.
- A definition is a named, expandable container.
- Parameters and results are typed ports on its boundary.
- Nested lexical scopes and pattern branches are nested containers.
- Ordinary expressions remain textual glyphs until expanded.
- References, captures, effects, and other boundary crossings are wires.
- An invocation is a compact macro-node that can open its definition.

The container border is semantic: crossing it distinguishes explicit inputs,
captures, capabilities, effects, and results. Zoom and expansion change the
amount of exposed structure, not the underlying representation.

## Editing rule

Text edits and spatial edits must produce changes in the same free change
language. Editing an expression rewires its semantic subgraph; rewiring a port
rewrites the expression's textual glyph. Neither presentation is a second
source of truth.

The first Studio implementation renders the Fibonacci workspace with function
and nested-function containers, boundary ports, pattern regions, textual
expressions, and reference/result wires. The `IR` tab remains a diagnostic
projection and `Typst` remains an export projection.

## Executable workspaces

The workspace runner discovers the selected definition and interprets its
reachable `ir.*` nodes. Function and parameter names are data used for lookup;
the host has no dispatch for Fibonacci or its helper. Nat execution relies on
zero equality and addition, while constructor and pattern shape provide zero,
successor, and predecessor binding.

Two engines consume the same IR. DeltaNet is the default fast path: independent
operands and arguments are scheduled concurrently and tail calls use a
trampoline. CESK-R evaluates strictly left-to-right and records a debug trace of
control, environment, continuation depth, and resource domain for every
reduction. Both must return the same observable Nat.

Studio reaches the Scala engines through the loopback Studio execution
service; the Rust delta browser only proxies the request. The browser contains
no evaluator. Start both services with `scripts/run-studio.sh`.

## Layout stress cases

The workspace layout lab exercises direct recursion, higher-order polymorphic
functions, closure capture, effects, multiple results, and high arity. It makes
several limits explicit:

- Types should be shared badges for an interface, not repeated beside every
  port.
- Lexical references should normally use restrained identity color shared with
  their boundary port; drawing a wire repeats information already present in
  the text. Reserve wires for relationships that cross a scope boundary or are
  otherwise hidden by textual notation.
- Captures and capabilities need visually distinct boundary-port classes.
- Multiple results need named output ports.
- High-arity interfaces need bundling or progressive disclosure rather than an
  indefinitely long vertical port list.
- Recursive and higher-order calls need definition links that remain available
  without crossing every value wire.
