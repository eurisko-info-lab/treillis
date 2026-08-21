# Bootstrap Contract

The Scala host exists to make Trellis capable of defining more Trellis.

## What Scala is allowed to know

The host kernel may permanently know only small constitutional mechanics:

1. immutable IDs and graph containers;
2. canonical encoding/hashing;
3. the free change envelope and replay mechanics;
4. branch/change-DAG mechanics;
5. a minimal trusted graph/resource validation substrate;
6. a minimal reference machine substrate;
7. projection and navigation interfaces;
8. bootstrap loading.

## What should migrate into Trellis data

As soon as practical, these belong in the semantic repository rather than Scala:

- concrete language node kinds;
- standard types/resources/protocols;
- typing rules beyond the tiny kernel;
- CESK transition rules;
- projection templates;
- optimizer rewrites and cost models;
- DeltaNet lowering rules;
- builders and target descriptions;
- package policies;
- documentation.

## Bootstrap success criterion

The bootstrap is successful when a new Trellis revision can introduce a substantial language feature by publishing semantic graph definitions and `ΔTrellis` changes without adding a new Scala source file.

A later clean-room implementation should be able to reproduce the same canonical graph/change hashes from the frozen bootstrap specification.
