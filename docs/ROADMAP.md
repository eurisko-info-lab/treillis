# Roadmap and implementation status

Slices A–G describe the architectural milestones and graph layers; some bullets
are complete graph models while others still need production persistence or
external integration. Remaining engineering work is collected at the end
rather than represented as another frozen product number.

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

## Slice D — Trellis Squeak

- graph navigator;
- synchronized semantic selection;
- interactive SVG views;
- Typst document preview;
- semantic delta review;
- optional Code View and later LSP adapter.

LspAdapter delivers the deferred LSP adapter after the global-network slice,
preserving every frozen predecessor identity while exposing semantic symbols
and exact definition locations over the Code View.

## Slice E — optimization and execution

- resource-aware e-graph derivation;
- law/proof-backed rewrites;
- target-dependent extraction;
- DeltaNet lowering;
- CESK-R vs DeltaNet differential tests.

## Slice F — global network

PublicationLedger implements the first item as a canonical, graph-admitted local
publication ledger. DistributedCas adds a deterministic replicated CAS simulation,
and SignedPublications authenticates namespaces, publications, and frontiers with
Ed25519. PublicationConsensus adds deterministic quorum finality over a hash-linked
publication chain. PackageDiscovery exposes finalized package discovery and verified
local branch-from-publication checkout. ArtifactAttestations completes the slice with
reproducible builders and signed artifact attestations over verified CAS bytes.

- simulated publication ledger first;
- distributed CAS;
- signed namespaces/publications/frontiers;
- blockchain consensus layer;
- package discovery and local branch-from-publication workflow;
- reproducible builder/artifact attestations.

## Slice G — worked semantic examples

FibonacciWorkspace begins with accumulator-style Fibonacci. Squeak discovers its
workspace node through ordinary search, traverses the reachable graph on
demand, and generates Graph, Trellis, UML-like SVG, and Typst views live from
semantic data.

## Slice H — assemblies and live images

- capability-selected graph assemblies;
- canonical locks and deterministic selected-frontier compilation;
- Squeak image fed by an assembly rather than source scanning;
- mutable open workspace delta;
- immutable commit/seal and separate publication lifecycle;
- System Browser, Transcript, inspector/projection, and engine views.

## Next

- replace Transcript's generic graph staging with a real Smalltalk language
  package, object model, method lookup, and evaluator;
- implement general Trellis AST-to-IR lowering;
- persist branches, changes, CAS objects, and transcript sessions across image
  restarts;
- expose graph conflict objects and interactive resolution;
- execute every assembly `verify` and `emit` declaration through registered
  backends;
- harden network consensus, publisher keys, and remote discovery.
