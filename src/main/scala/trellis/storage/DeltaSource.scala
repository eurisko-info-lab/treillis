package trellis.storage

import scala.collection.mutable
import trellis.{Canon, Delta}
import trellis.Core.*
import trellis.Delta.*

object DeltaSource:
  final case class Doc(attribute: String, value: String)
  final case class Assertion(kind: String, arguments: Vector[String])
  final case class Test(name: String, assertions: Vector[Assertion])

  enum Command:
    case Entity(entity: String, node: Node)
    case Replace(entity: String, node: Node)
    case Connect(from: String, fromPort: String, to: String, toPort: String, role: String)
    case Disconnect(from: String, fromPort: String, to: String, toPort: String, role: String)
    case DisconnectId(edge: ContentId)

  final case class Document(
      name: String,
      message: String,
      author: String,
      dependencies: Vector[String],
      docs: Vector[Doc],
      tests: Vector[Test],
      commands: Vector[Command]
  )

  def render(name: String, change: Change, basis: Graph, dependencyNames: Map[ChangeId, String]): Either[String, String] =
    val dependencies = change.dependencies.toVector.sortBy(_.value).map(id => dependencyNames.getOrElse(id, s"@${id.value}"))
    val commands = mutable.ArrayBuffer.empty[Command]
    var graph = basis
    val knownEdges = mutable.Map.from(basis.edges)
    var index = 0
    while index < change.operations.size do
      change.operations(index) match
        case Op.AddNode(node) =>
          val id = Canon.nodeId(node)
          change.operations.lift(index + 1) match
            case Some(Op.BindEntity(entity, bound)) if bound == id =>
              commands += Command.Entity(entity.value, node)
              graph = Delta.applyOp(graph, Op.AddNode(node)).flatMap(Delta.applyOp(_, Op.BindEntity(entity, bound))).fold(error => return Left(error), identity)
              index += 2
            case _ => return Left(s"$name has an add-node not immediately paired with its bind-entity")
        case Op.ReplaceEntity(entity, node) =>
          commands += Command.Replace(entity.value, node)
          graph = Delta.applyOp(graph, change.operations(index)).fold(error => return Left(error), identity)
          index += 1
        case Op.Connect(edge) =>
          val from = symbolicNode(graph, edge.from.node).fold(error => return Left(error), identity)
          val to = symbolicNode(graph, edge.to.node).fold(error => return Left(error), identity)
          commands += Command.Connect(from, edge.from.port, to, edge.to.port, edge.role)
          knownEdges(Canon.edgeId(edge)) = edge
          graph = Delta.applyOp(graph, change.operations(index)).fold(error => return Left(error), identity)
          index += 1
        case Op.Disconnect(id) =>
          knownEdges.get(id) match
            case Some(edge) =>
              val from = symbolicNode(graph, edge.from.node).fold(error => return Left(error), identity)
              val to = symbolicNode(graph, edge.to.node).fold(error => return Left(error), identity)
              commands += Command.Disconnect(from, edge.from.port, to, edge.to.port, edge.role)
            case None => commands += Command.DisconnectId(id)
          graph = Delta.applyOp(graph, change.operations(index)).fold(error => return Left(error), identity)
          index += 1
        case other => return Left(s"$name source syntax does not support $other")
    Right(print(Document(name, change.message, change.author, dependencies, Vector.empty, Vector.empty, commands.toVector)))

  def compile(document: Document, basis: Graph, dependencyIds: Map[String, ChangeId]): Either[String, (Change, Graph)] =
    for
      dependencies <- sequence(document.dependencies.map { name =>
        dependencyIds.get(name).orElse(Option.when(name.startsWith("@"))(ChangeId(name.drop(1)))).toRight(s"unknown source dependency $name")
      })
      result <- document.commands.foldLeft[Either[String, (Vector[Op], Graph)]](Right(Vector.empty -> basis)) { (acc, command) =>
        acc.flatMap { case (operations, graph) =>
          val nextOps: Vector[Op] = command match
            case Command.Entity(entity, node) => Vector(Op.AddNode(node), Op.BindEntity(EntityId(entity), Canon.nodeId(node)))
            case Command.Replace(entity, node) => Vector(Op.ReplaceEntity(EntityId(entity), node))
            case Command.Connect(from, fromPort, to, toPort, role) =>
              Vector(Op.Connect(Edge(PortRef(resolveNode(graph, from).fold(error => return Left(error), identity), fromPort), PortRef(resolveNode(graph, to).fold(error => return Left(error), identity), toPort), role)))
            case Command.Disconnect(from, fromPort, to, toPort, role) =>
              val edge = Edge(PortRef(resolveNode(graph, from).fold(error => return Left(error), identity), fromPort), PortRef(resolveNode(graph, to).fold(error => return Left(error), identity), toPort), role)
              Vector(Op.Disconnect(Canon.edgeId(edge)))
            case Command.DisconnectId(edge) => Vector(Op.Disconnect(edge))
          nextOps.foldLeft[Either[String, Graph]](Right(graph))((state, op) => state.flatMap(Delta.applyOp(_, op))).map(next => operations ++ nextOps -> next)
        }
      }
      change = Change(dependencies.toSet, result._1, document.message, document.author)
    yield change -> result._2

  def parse(source: String): Either[String, Document] =
    val lines = logicalLines(source).fold(error => return Left(error), identity).filterNot(_._1.trim.isEmpty)
    var name: Option[String] = None
    var message: Option[String] = None
    var author: Option[String] = None
    val dependencies = mutable.ArrayBuffer.empty[String]
    val docs = mutable.ArrayBuffer.empty[Doc]
    val tests = mutable.ArrayBuffer.empty[Test]
    val commands = mutable.ArrayBuffer.empty[Command]
    var index = 0
    while index < lines.size do
      val (raw, lineNumber) = lines(index)
      val indent = raw.takeWhile(_ == ' ').length
      tokenize(raw.trim).fold(error => return Left(s"line ${lineNumber + 1}: $error"), identity) match
        case Vector("delta-change", value) if indent == 0 => name = Some(value); index += 1
        case Vector("message", value) if indent == 0 => message = Some(value); index += 1
        case Vector("author", value) if indent == 0 => author = Some(value); index += 1
        case Vector("depends", value) if indent == 0 => dependencies += value; index += 1
        case Vector("doc", attribute, value) if indent == 0 => docs += Doc(attribute, value); index += 1
        case Vector("test", testName) if indent == 0 =>
          val assertions = mutable.ArrayBuffer.empty[Assertion]
          index += 1
          while index < lines.size && lines(index)._1.takeWhile(_ == ' ').length > indent do
            val (childRaw, childNumber) = lines(index)
            tokenize(childRaw.trim).fold(error => return Left(s"line ${childNumber + 1}: $error"), identity) match
              case Vector("assert", kind, arguments*) => assertions += Assertion(kind, arguments.toVector)
              case values => return Left(s"line ${childNumber + 1}: invalid test member ${values.mkString(" ")}")
            index += 1
          if assertions.isEmpty then return Left(s"line ${lineNumber + 1}: test has no assertions")
          tests += Test(testName, assertions.toVector)
        case Vector(kind, entity, nodeKind) if indent == 2 && (kind == "entity" || kind == "replace") =>
          val ports = mutable.ArrayBuffer.empty[Port]
          val attrs = mutable.Map.empty[String, String]
          index += 1
          while index < lines.size && lines(index)._1.takeWhile(_ == ' ').length > indent do
            val (childRaw, childNumber) = lines(index)
            tokenize(childRaw.trim).fold(error => return Left(s"line ${childNumber + 1}: $error"), identity) match
              case Vector("attr", key, value) => attrs(key) = value
              case Vector("port", portName, direction, ty) =>
                val decodedDirection = direction match
                  case "in" => Direction.In
                  case "out" => Direction.Out
                  case other => return Left(s"line ${childNumber + 1}: unknown direction $other")
                ports += Port(portName, decodedDirection, decodeSourceTy(ty).fold(error => return Left(s"line ${childNumber + 1}: $error"), identity))
              case values => return Left(s"line ${childNumber + 1}: invalid node member ${values.mkString(" ")}")
            index += 1
          val node = Node(nodeKind, ports.toVector, attrs.toMap)
          commands += (if kind == "entity" then Command.Entity(entity, node) else Command.Replace(entity, node))
        case Vector("components", kind, slice) if indent == 2 =>
          index += 1
          while index < lines.size && lines(index)._1.takeWhile(_ == ' ').length > indent do
            val (childRaw, childNumber) = lines(index)
            tokenize(childRaw.trim).fold(error => return Left(s"line ${childNumber + 1}: $error"), identity) match
              case Vector("component", slug, componentName) =>
                commands += Command.Entity(s"$kind.$slug", Node(kind, Vector.empty, Map("name" -> componentName, "slice" -> slice)))
              case values => return Left(s"line ${childNumber + 1}: invalid components member ${values.mkString(" ")}")
            index += 1
        case Vector("rules", kind) if indent == 2 =>
          index += 1
          while index < lines.size && lines(index)._1.takeWhile(_ == ' ').length > indent do
            val (childRaw, childNumber) = lines(index)
            tokenize(childRaw.trim).fold(error => return Left(s"line ${childNumber + 1}: $error"), identity) match
              case Vector("rule", slug, ruleName, effect) =>
                commands += Command.Entity(s"$kind.$slug", Node(kind, Vector.empty, Map("effect" -> effect, "name" -> ruleName)))
              case values => return Left(s"line ${childNumber + 1}: invalid rules member ${values.mkString(" ")}")
            index += 1
        case Vector("connect", from, fromPort, to, toPort, role) if indent == 2 => commands += Command.Connect(from, fromPort, to, toPort, role); index += 1
        case Vector("disconnect", from, fromPort, to, toPort, role) if indent == 2 => commands += Command.Disconnect(from, fromPort, to, toPort, role); index += 1
        case Vector("disconnect-id", edge) if indent == 2 => commands += Command.DisconnectId(ContentId(edge.stripPrefix("@"))); index += 1
        case values => return Left(s"line ${lineNumber + 1}: invalid source directive ${values.mkString(" ")}")
    for
      documentName <- name.toRight("missing delta-change")
      documentMessage <- message.toRight("missing message")
      documentAuthor <- author.toRight("missing author")
    yield Document(documentName, documentMessage, documentAuthor, dependencies.toVector, docs.toVector, tests.toVector, commands.toVector)

  def print(document: Document): String =
    val out = mutable.ArrayBuffer(qline("delta-change", document.name), qline("message", document.message), qline("author", document.author))
    document.docs.foreach { doc =>
      out += s"doc ${quote(doc.attribute)} \"\"\""
      doc.value.linesIterator.foreach(line => out += s"  |$line")
      out += "  \"\"\""
    }
    document.tests.foreach { test =>
      out += qline("test", test.name)
      test.assertions.foreach(assertion => out += "  " + qwords(Vector("assert", assertion.kind) ++ assertion.arguments))
    }
    document.dependencies.foreach(value => out += qline("depends", value))
    document.commands.foreach {
      case Command.Entity(entity, node) => out ++= nodeLines("entity", entity, node)
      case Command.Replace(entity, node) => out ++= nodeLines("replace", entity, node)
      case Command.Connect(from, fromPort, to, toPort, role) => out += "  " + qwords(Vector("connect", from, fromPort, to, toPort, role))
      case Command.Disconnect(from, fromPort, to, toPort, role) => out += "  " + qwords(Vector("disconnect", from, fromPort, to, toPort, role))
      case Command.DisconnectId(edge) => out += "  " + qwords(Vector("disconnect-id", s"@${edge.value}"))
    }
    out.mkString("\n") + "\n"

  private def nodeLines(operation: String, entity: String, node: Node): Vector[String] =
    Vector("  " + qwords(Vector(operation, entity, node.kind))) ++
      node.ports.map(port => "    " + qwords(Vector("port", port.name, port.direction.toString.toLowerCase, encodeSourceTy(port.ty)))) ++
      node.attrs.toVector.sortBy(_._1).map { case (key, value) => "    " + qwords(Vector("attr", key, value)) }

  private def symbolicNode(graph: Graph, id: ContentId): Either[String, String] =
    Right(graph.entities.toVector.collect { case (entity, node) if node == id => entity.value }.sorted.headOption.getOrElse(s"@${id.value}"))

  private def resolveNode(graph: Graph, value: String): Either[String, ContentId] =
    if value.startsWith("@") then Right(ContentId(value.drop(1))) else graph.entities.get(EntityId(value)).toRight(s"unknown symbolic entity $value")

  private def encodeSourceTy(ty: Ty): String = ty match
    case Ty.Atom(name) => name
    case other => Canon.encodeTy(other)

  private def decodeSourceTy(value: String): Either[String, Ty] =
    if value.headOption.exists(_.isDigit) then Canon.decodeTy(value) else Right(Ty.Atom(value))

  private def qline(keyword: String, value: String): String = s"$keyword ${quote(value)}"
  private def qwords(values: Vector[String]): String = values.head + values.tail.map(value => " " + quote(value)).mkString
  private def quote(value: String): String = "\"" + value.flatMap {
    case '\\' => "\\\\"
    case '"' => "\\\""
    case '\n' => "\\n"
    case '\r' => "\\r"
    case '\t' => "\\t"
    case char => char.toString
  } + "\""

  private def logicalLines(source: String): Either[String, Vector[(String, Int)]] =
    val physical = source.linesIterator.toVector
    val result = mutable.ArrayBuffer.empty[(String, Int)]
    var index = 0
    while index < physical.size do
      val line = physical(index)
      val opening = line.indexOf("\"\"\"")
      if opening < 0 then
        result += line -> index
        index += 1
      else
        val prefix = line.take(opening)
        val initial = line.drop(opening + 3)
        val content = mutable.ArrayBuffer.empty[String]
        var suffix = ""
        var closed = false
        val sameLineClose = initial.indexOf("\"\"\"")
        if sameLineClose >= 0 then
          content += initial.take(sameLineClose)
          suffix = initial.drop(sameLineClose + 3)
          closed = true
        else
          if initial.nonEmpty then content += initial
          index += 1
          while index < physical.size && !closed do
            val candidate = physical(index)
            val closing = candidate.indexOf("\"\"\"")
            if closing >= 0 then
              if candidate.take(closing).trim.nonEmpty then content += candidate.take(closing)
              suffix = candidate.drop(closing + 3)
              closed = true
            else content += candidate
            if !closed then index += 1
        if !closed then return Left(s"line ${result.size + 1}: unterminated block string")
        result += (prefix + quote(normalizeBlock(content.toVector)) + suffix) -> (result.size)
        index += 1
    Right(result.toVector)

  private def normalizeBlock(lines: Vector[String]): String =
    val withoutMargins = lines.map { line =>
      val first = line.indexWhere(!_.isWhitespace)
      if first >= 0 && line.charAt(first) == '|' then line.drop(first + 1) else line
    }
    val nonEmpty = withoutMargins.filter(_.trim.nonEmpty)
    val indent = nonEmpty.map(_.takeWhile(_.isWhitespace).length).minOption.getOrElse(0)
    withoutMargins.map(line => if line.trim.isEmpty then "" else line.drop(math.min(indent, line.length))).mkString("\n") + "\n"

  private def tokenize(line: String): Either[String, Vector[String]] =
    val values = mutable.ArrayBuffer.empty[String]
    var index = 0
    while index < line.length do
      while index < line.length && line.charAt(index).isWhitespace do index += 1
      if index < line.length then
        if line.charAt(index) == '"' then
          index += 1
          val value = new StringBuilder
          var closed = false
          while index < line.length && !closed do
            line.charAt(index) match
              case '"' => closed = true; index += 1
              case '\\' if index + 1 < line.length =>
                line.charAt(index + 1) match
                  case 'n' => value += '\n'
                  case 'r' => value += '\r'
                  case 't' => value += '\t'
                  case '\\' => value += '\\'
                  case '"' => value += '"'
                  case other => return Left(s"unknown escape \\$other")
                index += 2
              case char => value += char; index += 1
          if !closed then return Left("unterminated quoted token")
          values += value.result()
        else
          val start = index
          while index < line.length && !line.charAt(index).isWhitespace do index += 1
          values += line.substring(start, index)
    Right(values.toVector)

  private def sequence[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty))((acc, value) => acc.flatMap(xs => value.map(xs :+ _)))
