# Roadmap

## Slice A — harden the repository kernel

- canonical decoder and round-trip tests;
- canonical change encoding without ad-hoc string interpolation;
- persistent content-addressed object store;
- explicit conflict objects rather than string errors;
- dependency-frontier representation;
- provenance queries across local/global branches.

## Slice B — make the semantic core self-describing

- represent types, modes, node schemas, and validation rules as graph data;
- load checker rules from the bootstrap graph;
- typed holes with structural interfaces;
- package/module/name graph.

## Slice C — reference language semantics

- graph-defined lambda/apply/construct/match;
- processes and asynchronous channels;
- session protocols;
- CESK-R transitions generated/interpreted from graph rules;
- deterministic trace format.

## Slice D — Trellis Studio

- graph navigator;
- synchronized semantic selection;
- interactive SVG views;
- Typst document preview;
- semantic delta review;
- optional Code View and later LSP adapter.

## Slice E — optimization and execution

- resource-aware e-graph derivation;
- law/proof-backed rewrites;
- target-dependent extraction;
- DeltaNet lowering;
- CESK-R vs DeltaNet differential tests.

## Slice F — global network

- simulated publication ledger first;
- distributed CAS;
- signed namespaces/publications/frontiers;
- blockchain consensus layer;
- package discovery and local branch-from-publication workflow;
- reproducible builder/artifact attestations.
