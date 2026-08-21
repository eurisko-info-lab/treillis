package trellis

import trellis.Core.*

/** Builds the first self-describing Trellis universe as ordinary graph data. */
object Bootstrap:
  private def meta(kind: String, description: String): Node =
    Node("meta.node-kind", attrs = Map("name" -> kind, "description" -> description))

  /**
   * Constitutional vocabulary deliberately represented as Trellis data.
   * Scala knows only the tiny generic graph substrate; these semantic names
   * are ordinary entities in the bootstrap graph and can evolve through DeltaTrellis.
   */
  val nodeKinds: Vector[(EntityId, Node)] = Vector(
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

  val constitutionalEntities: Set[EntityId] = Set(
    EntityId("meta.node"),
    EntityId("meta.port"),
    EntityId("meta.edge"),
    EntityId("meta.entity"),
    EntityId("repo.change"),
    EntityId("repo.frontier"),
    EntityId("core.hole"),
    EntityId("meta.mode")
  )

  lazy val graph: Graph =
    val withNodes = nodeKinds.foldLeft(Graph()) { case (g, (entity, node)) =>
      val (g1, id) = Canon.addNode(g, node)
      g1.copy(entities = g1.entities.updated(entity, id))
    }
    val repoRoot = Node("repo.root", attrs = Map("name" -> "trellis-bootstrap", "version" -> "0.2"))
    val (g2, rootId) = Canon.addNode(withNodes, repoRoot)
    g2.copy(roots = Map("bootstrap" -> rootId), entities = g2.entities.updated(EntityId("trellis.bootstrap"), rootId))
