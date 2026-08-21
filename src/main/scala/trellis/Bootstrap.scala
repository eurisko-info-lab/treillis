package trellis

import trellis.Core.*

/** Builds the first self-describing Trellis universe as ordinary graph data. */
object Bootstrap:
  private def meta(kind: String, description: String): Node =
    Node("meta.node-kind", attrs = Map("name" -> kind, "description" -> description))

  val nodeKinds: Vector[(EntityId, Node)] = Vector(
    EntityId("meta.node") -> meta("meta.node", "describes semantic node kinds"),
    EntityId("meta.port") -> meta("meta.port", "describes typed ports"),
    EntityId("meta.edge") -> meta("meta.edge", "describes semantic edges"),
    EntityId("core.move") -> meta("core.move", "transfers an affine or linear capability"),
    EntityId("core.borrow.shared") -> meta("core.borrow.shared", "derives a temporary read capability"),
    EntityId("core.borrow.mut") -> meta("core.borrow.mut", "derives a temporary exclusive write capability"),
    EntityId("core.drop") -> meta("core.drop", "deterministically consumes an affine resource"),
    EntityId("core.replicate") -> meta("core.replicate", "explicit contraction for unrestricted capabilities"),
    EntityId("core.erase") -> meta("core.erase", "explicit weakening; affine values lower to drop"),
    EntityId("core.hole") -> meta("core.hole", "typed incomplete subgraph boundary"),
    EntityId("repo.change") -> meta("repo.change", "an immutable DeltaTrellis change"),
    EntityId("repo.branch") -> meta("repo.branch", "basis plus change frontier"),
    EntityId("machine.ceskr") -> meta("machine.ceskr", "reference resource/process semantics"),
    EntityId("projection.svg") -> meta("projection.svg", "interactive graph projection"),
    EntityId("projection.typst") -> meta("projection.typst", "formal/document projection")
  )

  lazy val graph: Graph =
    val withNodes = nodeKinds.foldLeft(Graph()) { case (g, (entity, node)) =>
      val (g1, id) = Canon.addNode(g, node)
      g1.copy(entities = g1.entities.updated(entity, id))
    }
    val repoRoot = Node("repo.root", attrs = Map("name" -> "trellis-bootstrap", "version" -> "0.1"))
    val (g2, rootId) = Canon.addNode(withNodes, repoRoot)
    g2.copy(roots = Map("bootstrap" -> rootId), entities = g2.entities.updated(EntityId("trellis.bootstrap"), rootId))
