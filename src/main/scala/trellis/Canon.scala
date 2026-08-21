package trellis

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import trellis.Core.*

/** Byte-stable canonical encoding and content addressing. */
object Canon:
  private def atom(s: String): String = s.length.toString + ":" + s
  private def list(tag: String, xs: Iterable[String]): String =
    atom(tag) + xs.iterator.map(atom).mkString

  def encodeMode(m: Mode): String = m.toString
  def encodeDirection(d: Direction): String = d.toString
  def encodeCapability(c: Capability): String = c.toString

  def encodeTy(t: Ty): String = t match
    case Ty.Atom(name) => list("atom", Vector(name))
    case Ty.Tuple(items) => list("tuple", items.map(encodeTy))
    case Ty.Cap(kind, mode, inner, state) =>
      list("cap", Vector(encodeCapability(kind), encodeMode(mode), encodeTy(inner), state.getOrElse("")))

  def encodePort(p: Port): String =
    list("port", Vector(p.name, encodeDirection(p.direction), encodeTy(p.ty)))

  def encodeNode(n: Node): String =
    val ports = n.ports.map(encodePort)
    val attrs = n.attrs.toVector.sortBy(_._1).map { case (k, v) => list("attr", Vector(k, v)) }
    list("node", Vector(n.kind, list("ports", ports), list("attrs", attrs)))

  def encodePortRef(r: PortRef): String = list("ref", Vector(r.node.value, r.port))

  def encodeEdge(e: Edge): String =
    list("edge", Vector(encodePortRef(e.from), encodePortRef(e.to), e.role))

  def encodeGraph(g: Graph): String =
    val nodes = g.nodes.toVector.sortBy(_._1.value).map { case (id, n) => list("n", Vector(id.value, encodeNode(n))) }
    val edges = g.edges.toVector.sortBy(_._1.value).map { case (id, e) => list("e", Vector(id.value, encodeEdge(e))) }
    val entities = g.entities.toVector.sortBy(_._1.value).map { case (e, n) => list("entity", Vector(e.value, n.value)) }
    val roots = g.roots.toVector.sortBy(_._1).map { case (name, id) => list("root", Vector(name, id.value)) }
    list("graph", Vector(list("nodes", nodes), list("edges", edges), list("entities", entities), list("roots", roots)))

  def sha256(text: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))
    digest.map(b => f"${b & 0xff}%02x").mkString

  def nodeId(node: Node): ContentId = ContentId(sha256(encodeNode(node)))
  def edgeId(edge: Edge): ContentId = ContentId(sha256(encodeEdge(edge)))
  def graphId(graph: Graph): ContentId = ContentId(sha256(encodeGraph(graph)))

  def addNode(g: Graph, node: Node): (Graph, ContentId) =
    val id = nodeId(node)
    (g.copy(nodes = g.nodes.updated(id, node)), id)

  def addEdge(g: Graph, edge: Edge): (Graph, ContentId) =
    val id = edgeId(edge)
    (g.copy(edges = g.edges.updated(id, edge)), id)
