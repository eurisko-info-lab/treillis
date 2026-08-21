# Validation

Run the complete bootstrap suite:

```bash
sbt "Test/runMain trellis.TestMain"
```

Compile and run the demo:

```bash
sbt run
```

Print only the foundation root in a fresh JVM process:

```bash
sbt "run hash"
```

The expected v0.2 root is:

```text
6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd
```

Dump the exact canonical text:

```bash
sbt "run dump" > bootstrap.canon
```

A useful clean-process reproduction check is:

```bash
expected=6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd
actual=$(sbt --error "run hash" | tail -n 1)
test "$actual" = "$expected"
```

The Scala test suite additionally checks strict rejection of malformed/noncanonical encodings and byte-identical replay properties.
