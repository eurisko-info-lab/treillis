package trellis

import java.nio.charset.StandardCharsets
import trellis.Core.*
import trellis.Delta.*

/**
 * Trellis foundation staircase.
 *
 * F0 is the frozen generic repository/meta substrate constructed by the tiny
 * Scala host. F1 is NOT supplied as a graph snapshot: it is derived by loading
 * the canonical DeltaTrellis resource `/trellis/foundations/F1.delta` and
 * applying that single change to F0.
 */
object Bootstrap:
  val F0Root = "6503a6ecb482388edcea4258224e49547da4ece85233687b981d2086d40b13dd"
  val F1ChangeId = "45c76c2d537927e4f0506696b278d86023bf5b694b0401135a93130b59c56fb4"
  val F1Root = "b8fddea20b4dba0ded6493fa70e98650377ea66bed2b0e0363b29a252dd6fe45"

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

  lazy val f1Change: Change =
    val bytes = readResource("/trellis/foundations/F1.delta")
    val change = Delta.decodeChangeBytes(bytes).fold(error => throw new IllegalStateException(s"invalid F1.delta: $error"), identity)
    require(Change.id(change).value == F1ChangeId, "F1.delta no longer matches its frozen change id")
    change

  lazy val f1: Graph =
    val graph = Delta.applyChange(f0, f1Change).fold(error => throw new IllegalStateException(s"cannot derive F1 from F0: $error"), identity)
    val errors = Check.validate(graph)
    require(errors.isEmpty, s"derived F1 is invalid: ${errors.mkString("; ")}")
    require(Canon.graphId(graph).value == F1Root, "derived F1 no longer matches its frozen foundation root")
    graph

  /** Current Trellis foundation used by demos and new local branches. */
  lazy val graph: Graph = f1

  private def readResource(path: String): Array[Byte] =
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse {
      throw new IllegalStateException(s"missing bootstrap resource $path")
    }
    try stream.readAllBytes()
    finally stream.close()
