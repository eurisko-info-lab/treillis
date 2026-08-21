package trellis

/**
 * Constitutional data model for the Trellis bootstrap.
 *
 * Deliberately generic: the Scala kernel does not define every future language
 * construct. It stores typed graph nodes whose node kinds can themselves be
 * described by Trellis data in the bootstrap graph.
 */
object Core:
  final case class ContentId(value: String) extends AnyVal
  final case class EntityId(value: String) extends AnyVal
  final case class ChangeId(value: String) extends AnyVal
  final case class PublicationId(value: String) extends AnyVal
  final case class BranchId(value: String) extends AnyVal

  enum Mode:
    case Unrestricted, Affine, Linear

    def duplicateAllowed: Boolean = this == Unrestricted
    def discardAllowed: Boolean = this != Linear

  enum Direction:
    case In, Out

  enum Capability:
    case Pure, Own, Read, Write, Suspended, Send, Recv, Session, Region, Effect, Process, Meta

  enum Ty:
    case Atom(name: String)
    case Tuple(items: Vector[Ty])
    case Cap(kind: Capability, structuralMode: Mode, inner: Ty, state: Option[String])

    def mode: Mode = this match
      case Atom(_) => Mode.Unrestricted
      case Tuple(items) =>
        if items.exists(_.mode == Mode.Linear) then Mode.Linear
        else if items.exists(_.mode == Mode.Affine) then Mode.Affine
        else Mode.Unrestricted
      case Cap(_, structuralMode, _, _) => structuralMode

  final case class Port(name: String, direction: Direction, ty: Ty)

  /** Node kinds are stable semantic names, e.g. core.move or meta.node-kind. */
  final case class Node(
      kind: String,
      ports: Vector[Port] = Vector.empty,
      attrs: Map[String, String] = Map.empty
  ):
    def port(name: String): Option[Port] = ports.find(_.name == name)

  final case class PortRef(node: ContentId, port: String)
  final case class Edge(from: PortRef, to: PortRef, role: String = "value")

  final case class Graph(
      nodes: Map[ContentId, Node] = Map.empty,
      edges: Map[ContentId, Edge] = Map.empty,
      entities: Map[EntityId, ContentId] = Map.empty,
      roots: Map[String, ContentId] = Map.empty
  ):
    def entity(id: EntityId): Option[Node] = entities.get(id).flatMap(nodes.get)
    def node(id: ContentId): Option[Node] = nodes.get(id)
    def outgoing(ref: PortRef): Vector[(ContentId, Edge)] =
      edges.iterator.filter(_._2.from == ref).toVector.sortBy(_._1.value)
    def incoming(ref: PortRef): Vector[(ContentId, Edge)] =
      edges.iterator.filter(_._2.to == ref).toVector.sortBy(_._1.value)
