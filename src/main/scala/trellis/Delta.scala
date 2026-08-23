package trellis

import java.nio.charset.StandardCharsets
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

  def encodeChangeBytes(change: Change): Array[Byte] = encodeChange(change).getBytes(StandardCharsets.UTF_8)

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

  /** Strict canonical decoder used by foundation deltas and future repository exchange. */
  def decodeChange(text: String): Either[String, Change] =
    decodeChangeUnchecked(text).flatMap { change =>
      if encodeChange(change) == text then Right(change)
      else Left("non-canonical DeltaTrellis change encoding")
    }

  def decodeChangeBytes(bytes: Array[Byte]): Either[String, Change] =
    Canon.decodeUtf8(bytes).flatMap { text =>
      decodeChange(text).flatMap { change =>
        if java.util.Arrays.equals(encodeChangeBytes(change), bytes) then Right(change)
        else Left("non-canonical DeltaTrellis change bytes")
      }
    }

  private def decodeChangeUnchecked(text: String): Either[String, Change] =
    for
      parts <- Canon.fixed(text, "change", 4)
      depTexts <- Canon.fields(parts(0), "dependencies")
      dependencies <- decodeDependencies(depTexts)
      opTexts <- Canon.fields(parts(1), "operations")
      operations <- Canon.sequenceEither(opTexts.map(decodeOp))
      _ <- Canon.nonEmpty(parts(2), "change message")
      _ <- Canon.nonEmpty(parts(3), "change author")
    yield Change(dependencies, operations, parts(2), parts(3))

  private def decodeDependencies(values: Vector[String]): Either[String, Set[ChangeId]] =
    val validation = values.foldLeft[Either[String, Unit]](Right(())) { (acc, value) =>
      acc.flatMap(_ => Canon.validateHash(value, "change dependency"))
    }
    validation.flatMap { _ =>
      val duplicate = values.groupBy(identity).collectFirst { case (id, xs) if xs.size > 1 => id }
      duplicate match
        case Some(id) => Left(s"duplicate change dependency: $id")
        case None if values != values.sorted => Left("change dependencies are not in canonical order")
        case None => Right(values.map(ChangeId.apply).toSet)
    }

  def decodeOp(encoded: String): Either[String, Op] =
    Canon.tagAndFields(encoded).flatMap {
      case ("op.add-node", Vector(nodeText)) => Canon.decodeNode(nodeText).map(Op.AddNode.apply)
      case ("op.bind-entity", Vector(entity, node)) =>
        for
          _ <- Canon.nonEmpty(entity, "entity id")
          _ <- Canon.validateHash(node, "bound node ContentId")
        yield Op.BindEntity(EntityId(entity), ContentId(node))
      case ("op.replace-entity", Vector(entity, nodeText)) =>
        for
          _ <- Canon.nonEmpty(entity, "entity id")
          node <- Canon.decodeNode(nodeText)
        yield Op.ReplaceEntity(EntityId(entity), node)
      case ("op.remove-entity", Vector(entity)) =>
        Canon.nonEmpty(entity, "entity id").map(_ => Op.RemoveEntity(EntityId(entity)))
      case ("op.connect", Vector(edgeText)) => Canon.decodeEdge(edgeText).map(Op.Connect.apply)
      case ("op.disconnect", Vector(edge)) =>
        Canon.validateHash(edge, "disconnected edge ContentId").map(_ => Op.Disconnect(ContentId(edge)))
      case ("op.add-root", Vector(name, node)) =>
        for
          _ <- Canon.nonEmpty(name, "root name")
          _ <- Canon.validateHash(node, "root node ContentId")
        yield Op.AddRoot(name, ContentId(node))
      case ("op.remove-root", Vector(name)) =>
        Canon.nonEmpty(name, "root name").map(_ => Op.RemoveRoot(name))
      case ("op.refine-hole", Vector(entity, nodeText)) =>
        for
          _ <- Canon.nonEmpty(entity, "hole entity id")
          node <- Canon.decodeNode(nodeText)
        yield Op.RefineHole(EntityId(entity), node)
      case (tag, _) => Left(s"invalid DeltaTrellis operation record: $tag")
    }

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

  private def nodeReferenced(graph: Graph, id: ContentId): Boolean =
    graph.entities.valuesIterator.exists(_ == id) ||
      graph.roots.valuesIterator.exists(_ == id) ||
      graph.edges.valuesIterator.exists(edge => edge.from.node == id || edge.to.node == id)

  /** Materialized graphs contain live semantic nodes; historical content remains in change/CAS history. */
  private def pruneIfUnreferenced(graph: Graph, id: ContentId): Graph =
    if nodeReferenced(graph, id) then graph else graph.copy(nodes = graph.nodes - id)

  def applyOp(graph: Graph, op: Op): Either[String, Graph] = op match
    case Op.AddNode(node) => Right(Canon.addNode(graph, node)._1)
    case Op.BindEntity(entity, node) =>
      if graph.nodes.contains(node) then Right(graph.copy(entities = graph.entities.updated(entity, node)))
      else Left(s"cannot bind ${entity.value}: missing node ${node.value}")
    case Op.ReplaceEntity(entity, node) =>
      val old = graph.entities.get(entity)
      val (g1, id) = Canon.addNode(graph, node)
      val rebound = g1.copy(entities = g1.entities.updated(entity, id))
      val pruned = old match
        case Some(oldId) if oldId != id => pruneIfUnreferenced(rebound, oldId)
        case _ => rebound
      Right(pruned)
    case Op.RemoveEntity(entity) =>
      val old = graph.entities.get(entity)
      val unbound = graph.copy(entities = graph.entities - entity)
      Right(old.fold(unbound)(oldId => pruneIfUnreferenced(unbound, oldId)))
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
