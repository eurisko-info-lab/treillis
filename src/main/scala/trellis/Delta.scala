package trellis

import trellis.Core.*

/** The small free change language used both by AI editing and repository history. */
object Delta:
  enum Op:
    case AddNode(node: Node)
    case BindEntity(entity: EntityId, node: ContentId)
    case ReplaceEntity(entity: EntityId, node: Node)
    case RemoveEntity(entity: EntityId)
    case Connect(edge: Edge)
    case Disconnect(edge: ContentId)
    case AddRoot(name: String, node: ContentId)
    case RemoveRoot(name: String)
    case RefineHole(entity: EntityId, replacement: Node)

  final case class Change(
      dependencies: Set[ChangeId],
      operations: Vector[Op],
      message: String,
      author: String = "ai"
  )

  object Change:
    def id(change: Change): ChangeId = ChangeId(Canon.sha256(encodeChange(change)))

  def encodeChange(change: Change): String =
    val deps = change.dependencies.toVector.sortBy(_.value).map(_.value)
    Canon.record(
      "change",
      Vector(
        Canon.record("dependencies", deps),
        Canon.record("operations", change.operations.map(encodeOp)),
        change.message,
        change.author
      )
    )

  def encodeOp(op: Op): String = op match
    case Op.AddNode(node) => Canon.record("op.add-node", Vector(Canon.encodeNode(node)))
    case Op.BindEntity(entity, node) => Canon.record("op.bind-entity", Vector(entity.value, node.value))
    case Op.ReplaceEntity(entity, node) => Canon.record("op.replace-entity", Vector(entity.value, Canon.encodeNode(node)))
    case Op.RemoveEntity(entity) => Canon.record("op.remove-entity", Vector(entity.value))
    case Op.Connect(edge) => Canon.record("op.connect", Vector(Canon.encodeEdge(edge)))
    case Op.Disconnect(edge) => Canon.record("op.disconnect", Vector(edge.value))
    case Op.AddRoot(name, node) => Canon.record("op.add-root", Vector(name, node.value))
    case Op.RemoveRoot(name) => Canon.record("op.remove-root", Vector(name))
    case Op.RefineHole(entity, replacement) => Canon.record("op.refine-hole", Vector(entity.value, Canon.encodeNode(replacement)))

  /** Keys used to reject concurrent semantic edits that would not commute. */
  def footprint(change: Change): Set[String] = change.operations.flatMap {
    case Op.AddNode(node) => Vector("node:" + Canon.nodeId(node).value)
    case Op.BindEntity(entity, _) => Vector("entity:" + entity.value)
    case Op.ReplaceEntity(entity, _) => Vector("entity:" + entity.value)
    case Op.RemoveEntity(entity) => Vector("entity:" + entity.value)
    case Op.Connect(edge) => Vector("edge:" + Canon.edgeId(edge).value)
    case Op.Disconnect(edge) => Vector("edge:" + edge.value)
    case Op.AddRoot(name, _) => Vector("root:" + name)
    case Op.RemoveRoot(name) => Vector("root:" + name)
    case Op.RefineHole(entity, _) => Vector("entity:" + entity.value)
  }.toSet

  def applyOp(graph: Graph, op: Op): Either[String, Graph] = op match
    case Op.AddNode(node) => Right(Canon.addNode(graph, node)._1)
    case Op.BindEntity(entity, node) =>
      if graph.nodes.contains(node) then Right(graph.copy(entities = graph.entities.updated(entity, node)))
      else Left(s"cannot bind ${entity.value}: missing node ${node.value}")
    case Op.ReplaceEntity(entity, node) =>
      val (g1, id) = Canon.addNode(graph, node)
      Right(g1.copy(entities = g1.entities.updated(entity, id)))
    case Op.RemoveEntity(entity) => Right(graph.copy(entities = graph.entities - entity))
    case Op.Connect(edge) =>
      val nodesExist = graph.nodes.contains(edge.from.node) && graph.nodes.contains(edge.to.node)
      if nodesExist then Right(Canon.addEdge(graph, edge)._1)
      else Left("cannot connect edge: endpoint node missing")
    case Op.Disconnect(edge) => Right(graph.copy(edges = graph.edges - edge))
    case Op.AddRoot(name, node) =>
      if graph.nodes.contains(node) then Right(graph.copy(roots = graph.roots.updated(name, node)))
      else Left(s"cannot add root $name: missing node ${node.value}")
    case Op.RemoveRoot(name) => Right(graph.copy(roots = graph.roots - name))
    case Op.RefineHole(entity, replacement) =>
      graph.entity(entity) match
        case Some(old) if old.kind == "core.hole" => applyOp(graph, Op.ReplaceEntity(entity, replacement))
        case Some(_) => Left(s"entity ${entity.value} is not a hole")
        case None => Left(s"unknown hole entity ${entity.value}")

  def applyChange(graph: Graph, change: Change): Either[String, Graph] =
    change.operations.foldLeft[Either[String, Graph]](Right(graph)) { (acc, op) => acc.flatMap(applyOp(_, op)) }
