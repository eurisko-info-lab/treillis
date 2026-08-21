package trellis

import trellis.Core.*
import trellis.Delta.*

/**
 * Trellis foundation staircase.
 *
 * F0 is the frozen generic repository/meta substrate constructed by the tiny
 * Scala host. Every successor is supplied only as canonical DeltaTrellis data:
 *
 *   F0 + F1.delta = F1
 *   F1 + F2.delta = F2
 *   F2 + F3.delta = F3
 *   F3 + F4.delta = F4
 *   F4 + F5.delta = F5
 *   F5 + F6.delta = F6
 *   F6 + F7.delta = F7
 *
 * No successor graph snapshot is checked in.
 */
object Bootstrap:
  val F0Root = "6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd"
  val F1ChangeId = "45c76c2d537927e4f0506696b278d86023bf5b694b0401135a93130b59c56fb4"
  val F1Root = "b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45"
  val F2ChangeId = "36a8f04e97463c76b74f176c574aa5910099b6d9c562a22e56785bd822488de1"
  val F2Root = "09cc9ba6664b4e8dd84e4937a2b0cd63ea0863e9c2fdbcdeea27284f89da9496"
  val F3ChangeId = "12abc3e2f986d514d59d76d93b77fd1ba5221b3dfadd121c04134321f53ed5eb"
  val F3Root = "c565d28a992289608c45fac5ace462d1b2e05059ae83bfb5507c25bad311cc1c"
  val F4ChangeId = "678d58fddf41d20375e3485fb19a0c0d13b904ab1a317936d32ac0c4f5d52d7a"
  val F4Root = "616a960470e389c665ab94280b70bb5c7e203ba3b78cdf0b373948a0adf60847"
  val F5ChangeId = "d6fb1fb29f9864cbd8062af1b066270883aa0efcbe8dc405dfd17935fd091368"
  val F5Root = "3516c065b71cce1667a5075625deea2ee88f0e58365ccc21d215e86127b3aab1"
  val F6ChangeId = "1200106d29fc3cb9ce27647803db8339b3ca66cfdca83abf95756833713ebc20"
  val F6Root = "478974e6ac4c8767a64fecb00835b0505368c6140f1eac22f9cc618a3666bba1"
  val F7ChangeId = "b1e91c7e639bd57a1e968927a901e3f694749d1f4d67cf16a5c19c57be72bff9"
  val F7Root = "efcbbe6b6f335ebfcf67a1894d51aef35869d54e94da77b26b4700c68660750b"

  private def meta(kind: String, description: String): Node =
    Node("meta.node-kind", attrs = Map("name" -> kind, "description" -> description))

  /**
   * F0 constitutional vocabulary deliberately represented as Trellis data.
   * Keep this construction byte-stable: F0 is frozen at `F0Root`.
   */
  private val f0NodeKinds: Vector[(EntityId, Node)] = Vector(
    EntityId("meta.node") -> meta("meta.node", "describes semantic node kinds"),
    EntityId("meta.port") -> meta("meta.port", "describes typed ports"),
    EntityId("meta.edge") -> meta("meta.edge", "describes semantic edges"),
    EntityId("meta.entity") -> meta("meta.entity", "describes stable semantic entity lineage"),
    EntityId("meta.mode") -> meta("meta.mode", "describes unrestricted, affine, and linear structural modes"),
    EntityId("core.move") -> meta("core.move", "transfers an affine or linear capability"),
    EntityId("core.borrow.shared") -> meta("core.borrow.shared", "derives a temporary read capability"),
    EntityId("core.borrow.mut") -> meta("core.borrow.mut", "derives a temporary exclusive write capability"),
    EntityId("core.drop") -> meta("core.drop", "deterministically consumes an affine resource"),
    EntityId("core.replicate") -> meta("core.replicate", "explicit contraction for unrestricted capabilities"),
    EntityId("core.erase") -> meta("core.erase", "explicit weakening; affine values lower to drop"),
    EntityId("core.hole") -> meta("core.hole", "typed incomplete subgraph boundary"),
    EntityId("repo.change") -> meta("repo.change", "an immutable DeltaTrellis change"),
    EntityId("repo.branch") -> meta("repo.branch", "a materialized basis plus a local change frontier"),
    EntityId("repo.frontier") -> meta("repo.frontier", "the maximal set of included changes defining a branch head"),
    EntityId("machine.ceskr") -> meta("machine.ceskr", "reference resource/process semantics"),
    EntityId("projection.svg") -> meta("projection.svg", "interactive graph projection"),
    EntityId("projection.typst") -> meta("projection.typst", "formal/document projection")
  )

  val f0ConstitutionalEntities: Set[EntityId] = Set(
    EntityId("meta.node"),
    EntityId("meta.port"),
    EntityId("meta.edge"),
    EntityId("meta.entity"),
    EntityId("repo.change"),
    EntityId("repo.frontier"),
    EntityId("core.hole"),
    EntityId("meta.mode")
  )

  val f1SchemaEntities: Set[EntityId] = Set(
    EntityId("meta.type"),
    EntityId("meta.mode"),
    EntityId("meta.capability"),
    EntityId("meta.port"),
    EntityId("meta.node-kind"),
    EntityId("meta.edge-kind"),
    EntityId("meta.graph"),
    EntityId("core.hole"),
    EntityId("repo.change"),
    EntityId("repo.frontier")
  )

  val f2ModeEntities: Set[EntityId] = Set(
    EntityId("resource.mode.unrestricted"),
    EntityId("resource.mode.affine"),
    EntityId("resource.mode.linear")
  )

  val f2CapabilityEntities: Set[EntityId] = Set(
    EntityId("resource.capability.pure"),
    EntityId("resource.capability.own"),
    EntityId("resource.capability.read"),
    EntityId("resource.capability.write"),
    EntityId("resource.capability.suspended")
  )

  val f2OperationEntities: Set[EntityId] = Set(
    EntityId("core.move"),
    EntityId("core.borrow.shared"),
    EntityId("core.borrow.mut"),
    EntityId("core.end-borrow"),
    EntityId("core.drop"),
    EntityId("core.replicate"),
    EntityId("core.erase")
  )

  val f2RuleEntities: Set[EntityId] = Set(
    EntityId("resource.rule.replicate.unrestricted"),
    EntityId("resource.rule.erase.unrestricted"),
    EntityId("resource.rule.erase.affine"),
    EntityId("resource.rule.move.affine"),
    EntityId("resource.rule.move.linear"),
    EntityId("resource.rule.drop.affine"),
    EntityId("resource.rule.borrow.shared"),
    EntityId("resource.rule.borrow.mut"),
    EntityId("resource.rule.end-borrow.shared"),
    EntityId("resource.rule.end-borrow.mut")
  )

  val f3ProcessEntities: Set[EntityId] = Set(
    EntityId("process.process"),
    EntityId("process.channel"),
    EntityId("process.queue"),
    EntityId("process.message")
  )

  val f3CapabilityEntities: Set[EntityId] = Set(
    EntityId("process.capability.send"),
    EntityId("process.capability.recv"),
    EntityId("process.capability.handle")
  )

  val f3OperationEntities: Set[EntityId] = Set(
    EntityId("process.new-channel"),
    EntityId("process.send"),
    EntityId("process.receive"),
    EntityId("process.spawn"),
    EntityId("process.join"),
    EntityId("process.terminate")
  )

  val f3RuleEntities: Set[EntityId] = Set(
    EntityId("process.rule.new-channel"),
    EntityId("process.rule.send.unrestricted"),
    EntityId("process.rule.send.affine"),
    EntityId("process.rule.send.linear"),
    EntityId("process.rule.receive"),
    EntityId("process.rule.spawn.unrestricted"),
    EntityId("process.rule.spawn.affine"),
    EntityId("process.rule.spawn.linear"),
    EntityId("process.rule.join"),
    EntityId("process.rule.terminate")
  )

  val f4ComponentEntities: Set[EntityId] = Set(
    EntityId("machine.control"),
    EntityId("machine.environment"),
    EntityId("machine.store"),
    EntityId("machine.continuation"),
    EntityId("machine.resource-state"),
    EntityId("machine.channel-state"),
    EntityId("machine.process-table"),
    EntityId("machine.current-process"),
    EntityId("machine.address"),
    EntityId("machine.binding"),
    EntityId("machine.frame"),
    EntityId("machine.owner"),
    EntityId("machine.loan")
  )

  val f4RuleEntities: Set[EntityId] = Set(
    EntityId("machine.rule.alloc"),
    EntityId("machine.rule.move"),
    EntityId("machine.rule.borrow.shared"),
    EntityId("machine.rule.borrow.mut"),
    EntityId("machine.rule.end-borrow"),
    EntityId("machine.rule.drop"),
    EntityId("machine.rule.new-channel"),
    EntityId("machine.rule.send"),
    EntityId("machine.rule.receive"),
    EntityId("machine.rule.spawn"),
    EntityId("machine.rule.terminate"),
    EntityId("machine.rule.join")
  )

  val f5ComponentEntities: Set[EntityId] = Set(
    EntityId("projection.selection"),
    EntityId("projection.view"),
    EntityId("projection.render-rule"),
    EntityId("projection.layout"),
    EntityId("projection.semantic-anchor"),
    EntityId("projection.navigation-target"),
    EntityId("projection.document")
  )

  val f5ViewEntities: Set[EntityId] = Set(
    EntityId("projection.code"),
    EntityId("projection.svg"),
    EntityId("projection.svg.ownership"),
    EntityId("projection.svg.process"),
    EntityId("projection.svg.machine"),
    EntityId("projection.typst")
  )

  val f5RuleEntities: Set[EntityId] = Set(
    EntityId("projection.rule.code.node"),
    EntityId("projection.rule.svg.node"),
    EntityId("projection.rule.svg.edge"),
    EntityId("projection.rule.svg.ownership.node"),
    EntityId("projection.rule.svg.ownership.edge"),
    EntityId("projection.rule.svg.process.node"),
    EntityId("projection.rule.svg.process.edge"),
    EntityId("projection.rule.svg.machine.node"),
    EntityId("projection.rule.svg.machine.edge"),
    EntityId("projection.rule.typst.entity")
  )


  val f6ComponentEntities: Set[EntityId] = Set(
    EntityId("equality.egraph"),
    EntityId("equality.eclass"),
    EntityId("equality.enode"),
    EntityId("equality.rewrite"),
    EntityId("equality.pattern"),
    EntityId("equality.substitution"),
    EntityId("equality.analysis"),
    EntityId("equality.invariant"),
    EntityId("equality.cost-model"),
    EntityId("equality.extractor"),
    EntityId("equality.saturation"),
    EntityId("equality.equivalence"),
    EntityId("equality.proof")
  )

  val f6InvariantEntities: Set[EntityId] = Set(
    EntityId("equality.invariant.type"),
    EntityId("equality.invariant.resource"),
    EntityId("equality.invariant.effect"),
    EntityId("equality.invariant.protocol")
  )

  val f6CostDimensionEntities: Set[EntityId] = Set(
    EntityId("equality.cost.nodes"),
    EntityId("equality.cost.allocations"),
    EntityId("equality.cost.replication"),
    EntityId("equality.cost.interactions"),
    EntityId("equality.cost.peak-memory"),
    EntityId("equality.cost.communication"),
    EntityId("equality.cost.critical-path")
  )

  val f6LawEntities: Set[EntityId] = Set(
    EntityId("equality.law.reflexive"),
    EntityId("equality.law.symmetric"),
    EntityId("equality.law.transitive"),
    EntityId("equality.law.congruence")
  )

  val f7ComponentEntities: Set[EntityId] = Set(
    EntityId("deltanet.net"),
    EntityId("deltanet.agent"),
    EntityId("deltanet.wire"),
    EntityId("deltanet.principal-port"),
    EntityId("deltanet.auxiliary-port"),
    EntityId("deltanet.active-pair"),
    EntityId("deltanet.interaction"),
    EntityId("deltanet.lowering"),
    EntityId("deltanet.readback"),
    EntityId("deltanet.scheduler")
  )

  val f7AgentKindEntities: Set[EntityId] = Set(
    EntityId("deltanet.agent-kind.value"),
    EntityId("deltanet.agent-kind.replicator"),
    EntityId("deltanet.agent-kind.eraser"),
    EntityId("deltanet.agent-kind.channel"),
    EntityId("deltanet.agent-kind.process"),
    EntityId("deltanet.agent-kind.alloc"),
    EntityId("deltanet.agent-kind.move"),
    EntityId("deltanet.agent-kind.borrow.shared"),
    EntityId("deltanet.agent-kind.borrow.mut"),
    EntityId("deltanet.agent-kind.end.borrow"),
    EntityId("deltanet.agent-kind.send"),
    EntityId("deltanet.agent-kind.receive"),
    EntityId("deltanet.agent-kind.spawn"),
    EntityId("deltanet.agent-kind.terminate"),
    EntityId("deltanet.agent-kind.join")
  )

  val f7LoweringEntities: Set[EntityId] = Set(
    EntityId("deltanet.lower.alloc"),
    EntityId("deltanet.lower.move"),
    EntityId("deltanet.lower.borrow.shared"),
    EntityId("deltanet.lower.borrow.mut"),
    EntityId("deltanet.lower.end.borrow"),
    EntityId("deltanet.lower.drop"),
    EntityId("deltanet.lower.new.channel"),
    EntityId("deltanet.lower.send"),
    EntityId("deltanet.lower.receive"),
    EntityId("deltanet.lower.spawn"),
    EntityId("deltanet.lower.terminate"),
    EntityId("deltanet.lower.join")
  )

  val f7InteractionEntities: Set[EntityId] = Set(
    EntityId("deltanet.interaction.replicate.unrestricted"),
    EntityId("deltanet.interaction.erase.unrestricted"),
    EntityId("deltanet.interaction.erase.affine"),
    EntityId("deltanet.interaction.send.channel"),
    EntityId("deltanet.interaction.receive.channel"),
    EntityId("deltanet.interaction.spawn.process"),
    EntityId("deltanet.interaction.join.process")
  )

  lazy val f0: Graph =
    val withNodes = f0NodeKinds.foldLeft(Graph()) { case (g, (entity, node)) =>
      val (g1, id) = Canon.addNode(g, node)
      g1.copy(entities = g1.entities.updated(entity, id))
    }
    val repoRoot = Node("repo.root", attrs = Map("name" -> "trellis-bootstrap", "version" -> "0.2"))
    val (g2, rootId) = Canon.addNode(withNodes, repoRoot)
    val graph = g2.copy(roots = Map("bootstrap" -> rootId), entities = g2.entities.updated(EntityId("trellis.bootstrap"), rootId))
    require(Canon.graphId(graph).value == F0Root, "F0 construction no longer matches its frozen foundation root")
    graph

  lazy val f1Change: Change = loadFoundationChange("F1", F1ChangeId)

  lazy val f1: Graph =
    deriveFoundation("F1", f0, f1Change, F1Root)

  lazy val f2Change: Change =
    val change = loadFoundationChange("F2", F2ChangeId)
    require(
      change.dependencies == Set(ChangeId(F1ChangeId)),
      "F2.delta must depend exactly on F1.delta"
    )
    change

  lazy val f2: Graph =
    deriveFoundation("F2", f1, f2Change, F2Root)

  lazy val f3Change: Change =
    val change = loadFoundationChange("F3", F3ChangeId)
    require(
      change.dependencies == Set(ChangeId(F2ChangeId)),
      "F3.delta must depend exactly on F2.delta"
    )
    change

  lazy val f3: Graph =
    deriveFoundation("F3", f2, f3Change, F3Root)

  lazy val f4Change: Change =
    val change = loadFoundationChange("F4", F4ChangeId)
    require(
      change.dependencies == Set(ChangeId(F3ChangeId)),
      "F4.delta must depend exactly on F3.delta"
    )
    change

  lazy val f4: Graph =
    deriveFoundation("F4", f3, f4Change, F4Root)

  lazy val f5Change: Change =
    val change = loadFoundationChange("F5", F5ChangeId)
    require(
      change.dependencies == Set(ChangeId(F4ChangeId)),
      "F5.delta must depend exactly on F4.delta"
    )
    change

  lazy val f5: Graph =
    deriveFoundation("F5", f4, f5Change, F5Root)

  lazy val f6Change: Change =
    val change = loadFoundationChange("F6", F6ChangeId)
    require(
      change.dependencies == Set(ChangeId(F5ChangeId)),
      "F6.delta must depend exactly on F5.delta"
    )
    change

  lazy val f6: Graph =
    deriveFoundation("F6", f5, f6Change, F6Root)

  lazy val f7Change: Change =
    val change = loadFoundationChange("F7", F7ChangeId)
    require(
      change.dependencies == Set(ChangeId(F6ChangeId)),
      "F7.delta must depend exactly on F6.delta"
    )
    change

  lazy val f7: Graph =
    deriveFoundation("F7", f6, f7Change, F7Root)

  /** Current Trellis foundation used by demos and new local branches. */
  lazy val graph: Graph = f7

  private def loadFoundationChange(name: String, expectedId: String): Change =
    val bytes = readResource(s"/trellis/foundations/$name.delta")
    val change = Delta.decodeChangeBytes(bytes).fold(
      error => throw new IllegalStateException(s"invalid $name.delta: $error"),
      identity
    )
    require(Change.id(change).value == expectedId, s"$name.delta no longer matches its frozen change id")
    change

  private def deriveFoundation(name: String, predecessor: Graph, change: Change, expectedRoot: String): Graph =
    val graph = Delta.applyChange(predecessor, change).fold(
      error => throw new IllegalStateException(s"cannot derive $name from its predecessor: $error"),
      identity
    )
    val errors = Check.validate(graph)
    require(errors.isEmpty, s"derived $name is invalid: ${errors.mkString("; ")}")
    require(Canon.graphId(graph).value == expectedRoot, s"derived $name no longer matches its frozen foundation root")
    graph

  private def readResource(path: String): Array[Byte] =
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse {
      throw new IllegalStateException(s"missing bootstrap resource $path")
    }
    try stream.readAllBytes()
    finally stream.close()
