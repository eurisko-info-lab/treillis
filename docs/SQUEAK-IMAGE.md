# Trellis Squeak image

Trellis Squeak is a Smalltalk-inspired live image for graph branches. It
presents Trellis objects as one structured space rather than parallel
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

## Image and transcript lifecycle

The image starts from the graph produced by `squeak-debug.assembly`. It does not
scan every source delta. Its state is:

```text
assembled basis + closed local frontier + open workspace delta
```

Transcript edits append graph operations to the open delta. All browser,
inspector, projection, and execution reads see the overlay. Commit validates
and seals the complete open delta as one content-addressed change, advances the
local branch, records the event in the transcript, and opens an empty delta.
Publish is deliberately separate and rejects dirty workspaces.

The first Squeak implementation renders the Fibonacci workspace with function
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

Squeak reaches the Scala engines through the loopback image service. The Rust
web shell serves the UI and proxies `/api/*` to the runtime; it contains no graph
assembler or evaluator. Start both services with `scripts/run-squeak.sh`.

AI agents can query and edit the same local branch through the [Agent API](AGENT-API.md)
on port 8422 without loading the full graph dump.

## Smalltalk status

The interaction model is Squeak-like, but a Smalltalk language package is not
implemented yet. The current Transcript's **Do it** stages the entered source
as an attribute of the named graph entity; it does not parse or execute
Smalltalk expressions. DeltaNet and CESK-R execute the selected graph-resident
Trellis IR, including the Fibonacci example.

Genuine Smalltalk support requires graph-defined Smalltalk syntax and method
lookup, an object/class/block model, lowering or an accepted Smalltalk IR, and
evaluator actions for **Do it**, **Print it**, **Inspect it**, and method
acceptance. Until then the Transcript is a live graph-change workspace.

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
