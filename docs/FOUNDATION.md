# Trellis Foundation Root v0.2

The bootstrap graph is now treated as a reproducibility boundary rather than a process-local demo value.

## Foundation root

For the exact v0.2 bootstrap vocabulary and canonical encoding in this slice, the required SHA-256 graph root is:

```text
6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd
```

`CanonTest` compares this value literally. Because the expected value is not computed by the test process, every invocation is a cross-process reproduction check. `sbt run hash` prints the independently computed current root for shell-level comparison.

## Canonical wire form

The canonical text is a sequence of atoms:

```text
<utf8-byte-length>:<payload>
```

Records are an atom containing the record tag followed by atoms containing each field. Nested records are ordinary field payloads.

The canonical binary form is exactly the UTF-8 encoding of the canonical text. This intentionally avoids JVM-specific object serialization and UTF-16 character counts.

Enum spellings are explicit constitutional strings such as:

```text
unrestricted
affine
linear
in
out
own
read
write
```

They are not derived from Scala `toString`.

## Strict decoding

A canonical decoder rejects at least:

- malformed UTF-8;
- non-minimal atom lengths such as `05:graph`;
- truncated or trailing records;
- malformed content hashes;
- duplicate map keys;
- maps presented in non-canonical key order;
- node or edge IDs that do not match their content hashes;
- entity/root references to absent nodes;
- edge references to absent nodes or ports;
- equivalent but non-canonical byte encodings.

Successful decoding is followed by re-encoding and exact byte comparison.

## Derivation staircase

Repository tests establish the bootstrap form:

```text
G0 + delta1 -> G1
G1 + delta2 -> G2
G2 + delta3 -> G3
```

Each successor is constructed from the predecessor plus one change. Materializing the corresponding change-DAG frontier from `G0` must produce byte-identical canonical graph bytes.

For independent changes `A` and `B`:

```text
encode(apply(apply(G, A), B))
==
encode(apply(apply(G, B), A))
```

byte for byte.

## Branch basis and provenance

A branch pulled from a published package starts with an already materialized `basis` graph. Therefore the upstream publication frontier is provenance, not part of the local replay frontier:

```text
upstream publication frontier F
          |
          | materialized as basis graph G(F)
          v
local branch
    basis = G(F)
    local frontier = {}
```

Local changes advance only the local frontier. This avoids accidentally replaying upstream history over an already materialized upstream graph.

## Bootstrap self-description

The v0.2 bootstrap graph contains ordinary Trellis entities describing at least:

```text
Node      meta.node
Port      meta.port
Edge      meta.edge
Entity    meta.entity
Change    repo.change
Frontier  repo.frontier
Hole      core.hole
Mode      meta.mode
```

This is the first bootstrap-closure step: new semantic vocabulary should increasingly be added through Trellis graph data and DeltaTrellis changes rather than new Scala algebraic cases.
