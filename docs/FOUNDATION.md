# Foundation Staircase

## Frozen roots

- F0: `6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd`
- F1 delta: `45c76c2d537927e4f0506696b278d86023bf5b694b0401135a93130b59c56fb4`
- F1: `b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45`
- F2 delta: `36a8f04e97463c76b74f176c574aa5910099b6d9c562a22e56785bd822488de1`
- F2: `09cc9ba6664b4e8dd84e4937a2b0cd63ea0863e9c2fdbcdeea27284f89da9496`
- F3 delta: `12abc3e2f986d514d59d76d93b77fd1ba5221b3dfadd121c04134321f53ed5eb`
- F3: `c565d28a992289608c45fac5ace462d1b2e05059ae83bfb5507c25bad311cc1c`
- F4 delta: `678d58fddf41d20375e3485fb19a0c0d13b904ab1a317936d32ac0c4f5d52d7a`
- F4: `616a960470e389c665ab94280b70bb5c7e203ba3b78cdf0b373948a0adf60847`

## F4 content

F4 defines as Trellis graph data:

- machine state and process state structure,
- control, environment, store, continuation,
- resource and channel state,
- process table and current-process identity,
- address, binding, frame, owner, and loan concepts,
- a machine-rule schema,
- twelve instruction dispatch rules.

The twelve rule instructions are `alloc`, `move`, `borrow-shared`, `borrow-mut`, `end-borrow`, `drop`, `new-channel`, `send`, `receive`, `spawn`, `terminate`, and `join`.

F4 deliberately does not yet encode arbitrary state rewrites as a Trellis language. It maps instructions to a tiny trusted transition-primitive vocabulary. The next closure step can move those primitive rewrites themselves into graph-defined programs.
