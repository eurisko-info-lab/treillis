package trellis

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.security.MessageDigest
import scala.util.Try
import trellis.Core.*

/**
 * Language-neutral canonical encoding and content addressing.
 *
 * The v0.2 wire format is the UTF-8 byte sequence of the canonical text.
 * Every field is encoded as `<utf8-byte-length>:<payload>`. Nested records are
 * themselves payloads, so the format remains tiny while avoiding delimiters,
 * escaping rules, Scala enum spellings, and UTF-16 length assumptions.
 */
object Canon:
  private val Utf8 = StandardCharsets.UTF_8
  private val HashPattern = "[0-9a-f]{64}".r

  private def utf8(s: String): Array[Byte] = s.getBytes(Utf8)

  private def atom(s: String): String = s"${utf8(s).length}:$s"

  /** Public so other constitutional encodings (notably DeltaTrellis) use the same framing. */
  def record(tag: String, fields: Iterable[String]): String =
    atom(tag) + fields.iterator.map(atom).mkString

  private def sequence(tag: String, values: Iterable[String]): String = record(tag, values)

  def encodeMode(m: Mode): String = m match
    case Mode.Unrestricted => "unrestricted"
    case Mode.Affine => "affine"
    case Mode.Linear => "linear"

  def encodeDirection(d: Direction): String = d match
    case Direction.In => "in"
    case Direction.Out => "out"

  def encodeCapability(c: Capability): String = c match
    case Capability.Pure => "pure"
    case Capability.Own => "own"
    case Capability.Read => "read"
    case Capability.Write => "write"
    case Capability.Suspended => "suspended"
    case Capability.Send => "send"
    case Capability.Recv => "recv"
    case Capability.Session => "session"
    case Capability.Region => "region"
    case Capability.Effect => "effect"
    case Capability.Process => "process"
    case Capability.Meta => "meta"

  private def encodeOption(value: Option[String]): String = value match
    case None => record("none", Vector.empty)
    case Some(v) => record("some", Vector(v))

  def encodeTy(t: Ty): String = t match
    case Ty.Atom(name) => record("atom", Vector(name))
    case Ty.Tuple(items) => sequence("tuple", items.map(encodeTy))
    case Ty.Cap(kind, mode, inner, state) =>
      record("cap", Vector(encodeCapability(kind), encodeMode(mode), encodeTy(inner), encodeOption(state)))

  def encodePort(p: Port): String =
    record("port", Vector(p.name, encodeDirection(p.direction), encodeTy(p.ty)))

  def encodeNode(n: Node): String =
    val ports = n.ports.map(encodePort)
    val attrs = n.attrs.toVector.sortBy(_._1).map { case (k, v) => record("attr", Vector(k, v)) }
    record("node", Vector(n.kind, sequence("ports", ports), sequence("attrs", attrs)))

  def encodePortRef(r: PortRef): String = record("ref", Vector(r.node.value, r.port))

  def encodeEdge(e: Edge): String =
    record("edge", Vector(encodePortRef(e.from), encodePortRef(e.to), e.role))

  def encodeGraph(g: Graph): String =
    val nodes = g.nodes.toVector.sortBy(_._1.value).map { case (id, n) => record("n", Vector(id.value, encodeNode(n))) }
    val edges = g.edges.toVector.sortBy(_._1.value).map { case (id, e) => record("e", Vector(id.value, encodeEdge(e))) }
    val entities = g.entities.toVector.sortBy(_._1.value).map { case (e, n) => record("entity", Vector(e.value, n.value)) }
    val roots = g.roots.toVector.sortBy(_._1).map { case (name, id) => record("root", Vector(name, id.value)) }
    record("graph", Vector(sequence("nodes", nodes), sequence("edges", edges), sequence("entities", entities), sequence("roots", roots)))

  def encodeGraphBytes(g: Graph): Array[Byte] = utf8(encodeGraph(g))

  def sha256(text: String): String = sha256Bytes(utf8(text))

  def sha256Bytes(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    digest.map(b => f"${b & 0xff}%02x").mkString

  def nodeId(node: Node): ContentId = ContentId(sha256(encodeNode(node)))
  def edgeId(edge: Edge): ContentId = ContentId(sha256(encodeEdge(edge)))
  def graphId(graph: Graph): ContentId = ContentId(sha256Bytes(encodeGraphBytes(graph)))

  def addNode(g: Graph, node: Node): (Graph, ContentId) =
    val id = nodeId(node)
    (g.copy(nodes = g.nodes.updated(id, node)), id)

  def addEdge(g: Graph, edge: Edge): (Graph, ContentId) =
    val id = edgeId(edge)
    (g.copy(edges = g.edges.updated(id, edge)), id)

  def decodeGraph(text: String): Either[String, Graph] =
    decodeGraphBytes(utf8(text)).flatMap { graph =>
      if encodeGraph(graph) == text then Right(graph)
      else Left("non-canonical graph encoding")
    }

  def decodeGraphBytes(bytes: Array[Byte]): Either[String, Graph] =
    for
      text <- decodeUtf8(bytes)
      graph <- decodeGraphUnchecked(text)
      _ <- if java.util.Arrays.equals(encodeGraphBytes(graph), bytes) then Right(()) else Left("non-canonical graph bytes")
    yield graph

  private def decodeGraphUnchecked(text: String): Either[String, Graph] =
    for
      graphFields <- fixed(text, "graph", 4)
      nodeEntries <- fields(graphFields(0), "nodes")
      edgeEntries <- fields(graphFields(1), "edges")
      entityEntries <- fields(graphFields(2), "entities")
      rootEntries <- fields(graphFields(3), "roots")
      nodes <- decodeNodes(nodeEntries)
      edges <- decodeEdges(edgeEntries)
      entities <- decodeEntities(entityEntries)
      roots <- decodeRoots(rootEntries)
      graph = Graph(nodes, edges, entities, roots)
      _ <- validateReferences(graph)
    yield graph

  private def decodeNodes(entries: Vector[String]): Either[String, Map[ContentId, Node]] =
    decodeEntryVector(entries, "nodes") { encoded =>
      for
        parts <- fixed(encoded, "n", 2)
        _ <- validateHash(parts(0), "node ContentId")
        node <- decodeNode(parts(1))
        id = ContentId(parts(0))
        _ <- if nodeId(node) == id then Right(()) else Left(s"node ${id.value} does not match its content hash")
      yield id -> node
    }(_.value).map(_.toMap)

  private def decodeEdges(entries: Vector[String]): Either[String, Map[ContentId, Edge]] =
    decodeEntryVector(entries, "edges") { encoded =>
      for
        parts <- fixed(encoded, "e", 2)
        _ <- validateHash(parts(0), "edge ContentId")
        edge <- decodeEdge(parts(1))
        id = ContentId(parts(0))
        _ <- if edgeId(edge) == id then Right(()) else Left(s"edge ${id.value} does not match its content hash")
      yield id -> edge
    }(_.value).map(_.toMap)

  private def decodeEntities(entries: Vector[String]): Either[String, Map[EntityId, ContentId]] =
    decodeEntryVector(entries, "entities") { encoded =>
      for
        parts <- fixed(encoded, "entity", 2)
        _ <- nonEmpty(parts(0), "entity id")
        _ <- validateHash(parts(1), "entity ContentId")
      yield EntityId(parts(0)) -> ContentId(parts(1))
    }(_.value).map(_.toMap)

  private def decodeRoots(entries: Vector[String]): Either[String, Map[String, ContentId]] =
    decodeEntryVector(entries, "roots") { encoded =>
      for
        parts <- fixed(encoded, "root", 2)
        _ <- nonEmpty(parts(0), "root name")
        _ <- validateHash(parts(1), "root ContentId")
      yield parts(0) -> ContentId(parts(1))
    }(identity).map(_.toMap)

  private def decodeEntryVector[K, V](
      entries: Vector[String],
      label: String
  )(decode: String => Either[String, (K, V)])(keyText: K => String): Either[String, Vector[(K, V)]] =
    sequenceEither(entries.map(decode)).flatMap { decoded =>
      val keys = decoded.map(pair => keyText(pair._1))
      val duplicate = keys.groupBy(identity).collectFirst { case (k, xs) if xs.size > 1 => k }
      duplicate match
        case Some(key) => Left(s"duplicate $label key: $key")
        case None if keys != keys.sorted => Left(s"$label are not in canonical key order")
        case None => Right(decoded)
    }

  private[trellis] def decodeNode(encoded: String): Either[String, Node] =
    for
      parts <- fixed(encoded, "node", 3)
      kind = parts(0)
      _ <- nonEmpty(kind, "node kind")
      portEncodings <- fields(parts(1), "ports")
      ports <- sequenceEither(portEncodings.map(decodePort))
      _ <- ensureUnique(ports.map(_.name), "port name")
      attrEncodings <- fields(parts(2), "attrs")
      attrs <- decodeAttrs(attrEncodings)
    yield Node(kind, ports, attrs)

  private def decodeAttrs(entries: Vector[String]): Either[String, Map[String, String]] =
    decodeEntryVector(entries, "attributes") { encoded =>
      fixed(encoded, "attr", 2).flatMap { parts =>
        nonEmpty(parts(0), "attribute key").map(_ => parts(0) -> parts(1))
      }
    }(identity).map(_.toMap)

  private def decodePort(encoded: String): Either[String, Port] =
    for
      parts <- fixed(encoded, "port", 3)
      _ <- nonEmpty(parts(0), "port name")
      direction <- decodeDirection(parts(1))
      ty <- decodeTy(parts(2))
    yield Port(parts(0), direction, ty)

  private[trellis] def decodeEdge(encoded: String): Either[String, Edge] =
    for
      parts <- fixed(encoded, "edge", 3)
      from <- decodePortRef(parts(0))
      to <- decodePortRef(parts(1))
      _ <- nonEmpty(parts(2), "edge role")
    yield Edge(from, to, parts(2))

  private def decodePortRef(encoded: String): Either[String, PortRef] =
    for
      parts <- fixed(encoded, "ref", 2)
      _ <- validateHash(parts(0), "port reference ContentId")
      _ <- nonEmpty(parts(1), "port reference name")
    yield PortRef(ContentId(parts(0)), parts(1))

  def decodeTy(encoded: String): Either[String, Ty] =
    tagAndFields(encoded).flatMap {
      case ("atom", Vector(name)) => nonEmpty(name, "atom type name").map(_ => Ty.Atom(name))
      case ("tuple", items) => sequenceEither(items.map(decodeTy)).map(items => Ty.Tuple(items))
      case ("cap", Vector(kindText, modeText, innerText, stateText)) =>
        for
          kind <- decodeCapability(kindText)
          mode <- decodeMode(modeText)
          inner <- decodeTy(innerText)
          state <- decodeOption(stateText)
        yield Ty.Cap(kind, mode, inner, state)
      case (tag, _) => Left(s"invalid type record: $tag")
    }

  private def decodeOption(encoded: String): Either[String, Option[String]] =
    tagAndFields(encoded).flatMap {
      case ("none", Vector()) => Right(None)
      case ("some", Vector(value)) => Right(Some(value))
      case (tag, _) => Left(s"invalid option record: $tag")
    }

  private def decodeMode(s: String): Either[String, Mode] = s match
    case "unrestricted" => Right(Mode.Unrestricted)
    case "affine" => Right(Mode.Affine)
    case "linear" => Right(Mode.Linear)
    case other => Left(s"unknown mode: $other")

  private def decodeDirection(s: String): Either[String, Direction] = s match
    case "in" => Right(Direction.In)
    case "out" => Right(Direction.Out)
    case other => Left(s"unknown direction: $other")

  private def decodeCapability(s: String): Either[String, Capability] = s match
    case "pure" => Right(Capability.Pure)
    case "own" => Right(Capability.Own)
    case "read" => Right(Capability.Read)
    case "write" => Right(Capability.Write)
    case "suspended" => Right(Capability.Suspended)
    case "send" => Right(Capability.Send)
    case "recv" => Right(Capability.Recv)
    case "session" => Right(Capability.Session)
    case "region" => Right(Capability.Region)
    case "effect" => Right(Capability.Effect)
    case "process" => Right(Capability.Process)
    case "meta" => Right(Capability.Meta)
    case other => Left(s"unknown capability: $other")

  private def validateReferences(graph: Graph): Either[String, Unit] =
    val missingEntity = graph.entities.collectFirst { case (entity, node) if !graph.nodes.contains(node) =>
      s"entity ${entity.value} references missing node ${node.value}"
    }
    val missingRoot = graph.roots.collectFirst { case (name, node) if !graph.nodes.contains(node) =>
      s"root $name references missing node ${node.value}"
    }
    val badEdge = graph.edges.toVector.sortBy(_._1.value).collectFirst {
      case (id, edge) if !graph.nodes.contains(edge.from.node) => s"edge ${id.value} references missing source node ${edge.from.node.value}"
      case (id, edge) if !graph.nodes.contains(edge.to.node) => s"edge ${id.value} references missing target node ${edge.to.node.value}"
      case (id, edge) if graph.nodes(edge.from.node).port(edge.from.port).isEmpty => s"edge ${id.value} references missing source port ${edge.from.port}"
      case (id, edge) if graph.nodes(edge.to.node).port(edge.to.port).isEmpty => s"edge ${id.value} references missing target port ${edge.to.port}"
    }
    missingEntity.orElse(missingRoot).orElse(badEdge).toLeft(())

  private[trellis] def validateHash(value: String, label: String): Either[String, Unit] =
    if HashPattern.pattern.matcher(value).matches() then Right(()) else Left(s"malformed $label: $value")

  private[trellis] def nonEmpty(value: String, label: String): Either[String, Unit] =
    if value.nonEmpty then Right(()) else Left(s"empty $label")

  private def ensureUnique[A](values: Vector[A], label: String): Either[String, Unit] =
    values.groupBy(identity).collectFirst { case (value, xs) if xs.size > 1 => value } match
      case Some(value) => Left(s"duplicate $label: $value")
      case None => Right(())

  private[trellis] def fields(encoded: String, expectedTag: String): Either[String, Vector[String]] =
    tagAndFields(encoded).flatMap { case (tag, values) =>
      if tag == expectedTag then Right(values) else Left(s"expected $expectedTag record, found $tag")
    }

  private[trellis] def fixed(encoded: String, expectedTag: String, arity: Int): Either[String, Vector[String]] =
    fields(encoded, expectedTag).flatMap { values =>
      if values.size == arity then Right(values)
      else Left(s"$expectedTag record has ${values.size} fields; expected $arity")
    }

  private[trellis] def tagAndFields(encoded: String): Either[String, (String, Vector[String])] =
    splitAtoms(utf8(encoded)).flatMap {
      case Vector() => Left("empty canonical record")
      case values => Right(values.head -> values.tail)
    }

  private def splitAtoms(bytes: Array[Byte]): Either[String, Vector[String]] =
    val out = Vector.newBuilder[String]
    var offset = 0
    while offset < bytes.length do
      val start = offset
      while offset < bytes.length && bytes(offset) >= '0'.toByte && bytes(offset) <= '9'.toByte do offset += 1
      if start == offset then return Left(s"expected atom length at byte $offset")
      if offset >= bytes.length || bytes(offset) != ':'.toByte then return Left(s"expected ':' after atom length at byte $offset")
      val lengthText = new String(bytes, start, offset - start, StandardCharsets.US_ASCII)
      if lengthText.length > 1 && lengthText.head == '0' then return Left(s"non-canonical atom length: $lengthText")
      val length = Try(lengthText.toInt).toEither.left.map(_ => s"invalid atom length: $lengthText") match
        case Left(error) => return Left(error)
        case Right(value) => value
      offset += 1
      if length < 0 || bytes.length - offset < length then return Left(s"atom length $length exceeds remaining input at byte $offset")
      val payload = java.util.Arrays.copyOfRange(bytes, offset, offset + length)
      decodeUtf8(payload) match
        case Left(error) => return Left(error)
        case Right(value) => out += value
      offset += length
    Right(out.result())

  private[trellis] def decodeUtf8(bytes: Array[Byte]): Either[String, String] =
    val decoder = Utf8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    Try(decoder.decode(ByteBuffer.wrap(bytes)).toString).toEither.left.map(e => s"invalid UTF-8: ${e.getMessage}")

  private[trellis] def sequenceEither[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) { (acc, item) =>
      for
        xs <- acc
        x <- item
      yield xs :+ x
    }
