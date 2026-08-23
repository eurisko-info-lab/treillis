# Generated examples

Run:

```bash
sbt "run svg examples/bootstrap.svg"
sbt "run typst examples/bootstrap.typ"
sbt "run dump" > examples/bootstrap.canon.txt
```

The generated files are projections of the canonical bootstrap graph, not source inputs.

To generate a consumer graph instead of the bare foundation:

```bash
sbt "runMain trellis.Main compile-assembly squeak-debug /tmp/squeak.canon"
```

That root and its node/edge counts describe this assembly result only. They are
not identities of the individual feature deltas selected into it.
