# Trellis Tutorial

## 1. Start from the global graph

A project starts by branching a published Trellis frontier rather than creating an empty source tree:

```text
Global Trellis
  trellis/application/default @ F
                 |
                 | branch
                 v
             local/demo
```

The local branch initially contains the same semantic graph as `F`. Local AI work adds immutable `ΔTrellis` changes.

## 2. Navigate semantics, not files

A selection is an entity, immutable node, edge, change, branch, or publication. From an entity such as `app/analyze`, Squeak can navigate to its content, callers, capability edges, history, origin publication, proof dependencies, or execution projection.

The bootstrap exposes this through `Navigate.Selection` and `Navigate.neighborhood`.

## 3. Open a Squeak image

An assembly chooses the consumer graph. Start the debug image with:

```bash
./scripts/run-squeak.sh
```

Search for `fib` and open the workspace. The System Browser, Graph, IR, Typst,
and Debug views are projections of the same assembled graph.

## 4. Edit with `ΔTrellis`

An AI does not rewrite a `.trellis` file. It proposes a change:

```text
ReplaceEntity(app/analyze, new semantic node)
Connect(...)
AddRoot(...)
RefineHole(...)
```

In the Transcript these operations first accumulate in a mutable open delta.
Commit validates and seals them as one immutable, content-addressed change.
Publish is a later operation governed by the selected graph's publication
policy. The repository checks concurrent footprints before materialization so
unrelated changes commute while overlapping semantic edits become explicit
conflicts.

## 5. Read the graph in several lenses

`Project.scala` implements the first three projections:

- **Code View**: compact code-like semantic dump for quick reading.
- **SVG**: interactive-friendly graph output carrying `data-trellis-node` / `data-trellis-edge` identifiers.
- **Typst**: formal/document projection suitable for richer generated reports later.

None is canonical source.

## 6. Resource ownership is graph structure

Ports carry types and structural modes:

```text
Unrestricted: duplicate yes, discard yes
Affine:       duplicate no,  discard yes via Drop
Linear:       duplicate no,  discard no
```

The validator rejects an affine or linear output port with implicit fan-out. Duplication must therefore be explicit and legal.

## 7. Execute through selected engines

DeltaNet provides the parallel path; CESK-R provides deterministic sequential
execution and traces. Both consume validated graph IR rather than dispatching
on example names. `Machine.scala` also contains the constitutional resource
machine. A resource has exactly one current owner, which can be a process or
channel queue.

A representative trace is:

```text
alloc job -> main
channel jobs
send job -> jobs
recv job -> worker
```

The queue temporarily owns the affine resource. No reachability scan discovers its lifetime.

## 8. Distinguish Squeak from Smalltalk

The current image adopts a Squeak-style System Browser and Transcript workflow,
but no Smalltalk parser or VM exists yet. **Do it** currently stages graph
source. Stored Trellis IR can be executed with DeltaNet or CESK-R.

## 9. Bootstrap the rest

`Bootstrap.scala` stores semantic concepts such as `core.move`, `core.borrow.shared`, `repo.change`, and `projection.svg` as ordinary graph entities. The goal is to move increasingly rich language/runtime/projection definitions into Trellis itself while the Scala kernel remains small.
