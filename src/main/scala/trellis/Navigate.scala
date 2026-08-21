package trellis

import trellis.Core.*
import trellis.Repo.*

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

  def neighborhood(graph: Graph, selection: Selection): Neighborhood = selection match
    case Selection.Entity(entity) =>
      val related = graph.entities.get(entity).toVector.map(id => "content" -> Selection.Node(id))
      Neighborhood(selection, related)
    case Selection.Node(id) =>
      val incoming = graph.edges.collect { case (eid, e) if e.to.node == id => "incoming" -> Selection.Edge(eid) }.toVector
      val outgoing = graph.edges.collect { case (eid, e) if e.from.node == id => "outgoing" -> Selection.Edge(eid) }.toVector
      val entities = graph.entities.collect { case (entity, nid) if nid == id => "entity" -> Selection.Entity(entity) }.toVector
      Neighborhood(selection, (entities ++ incoming ++ outgoing).sortBy(_._2.toString))
    case Selection.Edge(id) =>
      val related = graph.edges.get(id).toVector.flatMap(e => Vector("from" -> Selection.Node(e.from.node), "to" -> Selection.Node(e.to.node)))
      Neighborhood(selection, related)
    case _ => Neighborhood(selection, Vector.empty)

  def entityHistory(store: Store, entity: EntityId): Vector[ChangeId] =
    store.changes.toVector.collect {
      case (id, change) if Delta.footprint(change).contains("entity:" + entity.value) => id
    }.sortBy(_.value)
