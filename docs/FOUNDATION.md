# Trellis foundation staircase

Trellis foundations are derived, never supplied as successor snapshots.

```text
F0
  + F1.delta
  = F1

F1
  + F2.delta
  = F2

F2
  + F3.delta
  = F3
```

Frozen identifiers:

```text
F0 root
6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd

F1.delta
45c76c2d537927e4f0506696b278d86023bf5b694b0401135a93130b59c56fb4

F1 root
b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45

F2.delta
36a8f04e97463c76b74f176c574aa5910099b6d9c562a22e56785bd822488de1

F2 root
09cc9ba6664b4e8dd84e4937a2b0cd63ea0863e9c2fdbcdeea27284f89da9496

F3.delta
12abc3e2f986d514d59d76d93b77fd1ba5221b3dfadd121c04134321f53ed5eb

F3 root
c565d28a992289608c45fac5ace462d1b2e05059ae83bfb5507c25bad311cc1c
```

## F2: resource calculus

F2 moves concrete resource policy out of Scala conditionals and into Trellis graph data.

It defines structural modes, resource capabilities, move/borrow/drop/replicate/erase operations, ten resource rules, and first-class rule relationships.

The Scala bootstrap retains a generic resource-rule matcher rather than per-operation Trellis semantics.

## F3: process/channel calculus

F3 builds concurrency on top of F2 rather than redefining ownership.

It defines:

- process, channel, queue, and message semantic kinds;
- send, receive, and process-handle capability definitions;
- `new-channel`, `send`, `receive`, `spawn`, `join`, and `terminate` operations;
- ten graph-defined process rules;
- first-class edges from rules to operations and F2 modes;
- first-class channel relationships to send/receive capability definitions.

The graph-defined dispositions are:

```text
create-channel
copy-to-channel
transfer-to-channel
transfer-to-process
share-with-child
transfer-to-child
transfer-to-joiner
structural-discard
```

This makes the distinction between unrestricted communication and affine/linear capability transfer explicit in F3 data:

```text
send unrestricted -> copy-to-channel
send affine       -> transfer-to-channel
send linear       -> transfer-to-channel

spawn unrestricted capture -> share-with-child
spawn affine capture       -> transfer-to-child
spawn linear capture       -> transfer-to-child
```

Process termination delegates live-resource disposal to the structural policy already defined by F2. Thus unrestricted values may be erased, affine values are dropped, and live linear obligations prevent termination.

`Send` endpoint capability mode is unrestricted, while `Recv` and process-handle capability modes are affine. These modes are read from F3 graph nodes by the reference machine.

No `F1.graph`, `F2.graph`, or `F3.graph` file exists. Each successor is reconstructed strictly from its predecessor plus one canonical delta.
