package trellis

import trellis.Core.*
import trellis.storage.RepositoryProducts.*

/** Semantic navigation independent of files, text offsets, or a particular IDE. */
object Navigate:
  enum Selection:
    case Entity(id: EntityId)
    case Node(id: ContentId)
    case Edge(id: ContentId)
    case Change(id: ChangeId)
    case Branch(id: BranchId)
    case Publication(id: PublicationId)

  final case class Neighborhood(center: Selection, related: Vector[(String, Selection)])
  final case class NavigatorPolicy(entity: EntityId, order: String, traversal: String, identity: String, failure: String, maxDepth: Int, maxResults: Int)
  final case class NavigatorItem(depth: Int, relation: String, selection: Selection)
  final case class NavigatorView(center: Selection, items: Vector[NavigatorItem])

  def selectionKey(selection: Selection): String = selection match
    case Selection.Entity(id) => s"entity:${id.value}"
    case Selection.Node(id) => s"node:${id.value}"
    case Selection.Edge(id) => s"edge:${id.value}"
    case Selection.Change(id) => s"change:${id.value}"
    case Selection.Branch(id) => s"branch:${id.value}"
    case Selection.Publication(id) => s"publication:${id.value}"

  def graphView(graph: Graph, center: Selection): Either[String, NavigatorView] =
    for
      policy <- navigatorPolicy(graph)
      _ <- selectionExists(graph, center)
      view <-
        var frontier = Vector(NavigatorItem(0, "center", center))
        var visited = Set(selectionKey(center))
        var result = Vector.empty[NavigatorItem]
        while frontier.nonEmpty && result.size < policy.maxResults do
          val current = frontier.head
          frontier = frontier.tail
          result :+= current
          if current.depth < policy.maxDepth then
            val next = neighborhood(graph, current.selection).related
              .map { case (relation, selection) => NavigatorItem(current.depth + 1, relation, selection) }
              .sortBy(item => (item.relation, selectionKey(item.selection)))
              .filter(item => !visited.contains(selectionKey(item.selection)))
            visited ++= next.map(item => selectionKey(item.selection))
            frontier ++= next
        Right(NavigatorView(center, result))
    yield view

  private def navigatorPolicy(graph: Graph): Either[String, NavigatorPolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) =>
      graph.nodes.get(id).filter(_.kind == "studio.navigator-policy").map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          order <- node.attrs.get("order").toRight(s"${entity.value} lacks order")
          traversal <- node.attrs.get("traversal").toRight(s"${entity.value} lacks traversal")
          identity <- node.attrs.get("identity").toRight(s"${entity.value} lacks identity")
          failure <- node.attrs.get("failure").toRight(s"${entity.value} lacks failure")
          maxDepth <- node.attrs.get("max-depth").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max-depth")
          maxResults <- node.attrs.get("max-results").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer max-results")
          _ <- if order == "relation-then-identity" && traversal == "breadth-first" && identity == "semantic" && failure == "strict" && maxDepth >= 0 && maxDepth <= 16 && maxResults > 0 && maxResults <= 4096 then Right(()) else Left(s"unsupported navigator policy ${entity.value}")
        yield NavigatorPolicy(entity, order, traversal, identity, failure, maxDepth, maxResults)
      case Vector() => Left("missing Studio navigator policy")
      case _ => Left("multiple Studio navigator policies")

  private def selectionExists(graph: Graph, selection: Selection): Either[String, Unit] = selection match
    case Selection.Entity(id) => Either.cond(graph.entities.contains(id), (), s"unknown entity ${id.value}")
    case Selection.Node(id) => Either.cond(graph.nodes.contains(id), (), s"unknown node ${id.value}")
    case Selection.Edge(id) => Either.cond(graph.edges.contains(id), (), s"unknown edge ${id.value}")
    case other => Left(s"unsupported graph selection ${selectionKey(other)}")

  def neighborhood(graph: Graph, selection: Selection): Neighborhood = selection match
    case Selection.Entity(entity) =>
      val related = graph.entities.get(entity).toVector.map(id => "content" -> Selection.Node(id))
      Neighborhood(selection, related)
    case Selection.Node(id) =>
      val incoming = graph.edges.collect { case (eid, e) if e.to.node == id => "incoming" -> Selection.Edge(eid) }.toVector
      val outgoing = graph.edges.collect { case (eid, e) if e.from.node == id => "outgoing" -> Selection.Edge(eid) }.toVector
      val entities = graph.entities.collect { case (entity, nid) if nid == id => "entity" -> Selection.Entity(entity) }.toVector
      Neighborhood(selection, (entities ++ incoming ++ outgoing).sortBy { case (relation, item) => (relation, selectionKey(item)) })
    case Selection.Edge(id) =>
      val related = graph.edges.get(id).toVector.flatMap(e => Vector("from" -> Selection.Node(e.from.node), "to" -> Selection.Node(e.to.node)))
      Neighborhood(selection, related)
    case _ => Neighborhood(selection, Vector.empty)

  def entityHistory(store: Store, entity: EntityId): Vector[ChangeId] =
    store.changes.toVector.collect {
      case (id, change) if Delta.footprint(change).contains("entity:" + entity.value) => id
    }.sortBy(_.value)
