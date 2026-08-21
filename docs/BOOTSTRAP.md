# Bootstrap boundary

The Scala implementation is intentionally a small host kernel. Its job is to make the Trellis semantic repository reproducible enough that more of Trellis can move into Trellis data.

## Scala remains responsible for

- the generic immutable graph substrate (`Node`, `Port`, `Edge`, IDs);
- canonical UTF-8 encoding/strict decoding and SHA-256 addressing;
- the free DeltaTrellis change constructors and replay mechanics;
- change-DAG closure, conflict detection, branch frontiers, and provenance;
- the small trusted graph/resource validator;
- the CESK-R reference machine bootstrap;
- semantic selection and projection plumbing.

## The Trellis graph increasingly owns

- semantic node-kind definitions;
- the vocabulary of entities, changes, frontiers, holes, and modes;
- resource/protocol libraries;
- laws and proofs;
- projection definitions;
- optimizer rules;
- package and builder descriptions.

The constitutional direction is:

```text
small Scala graph machine
        |
        v
canonical bootstrap graph
        |
        + DeltaTrellis
        v
Trellis defines more Trellis
```

The host kernel should not grow merely because a new Trellis semantic construct is introduced.

## Current closure checkpoint

The v0.2 slice freezes a literal bootstrap root and tests:

- exact canonical text/byte round trips;
- adversarial canonical decoding;
- content-hash integrity;
- predecessor-plus-delta derivation staircases;
- byte-identical replay of independent changes;
- upstream basis provenance separated from local frontier;
- graph-level definitions of Node, Port, Edge, Entity, Change, Frontier, Hole, and Mode.

See `FOUNDATION.md` for the exact root and wire rules.
