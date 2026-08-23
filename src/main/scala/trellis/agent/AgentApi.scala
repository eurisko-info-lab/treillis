package trellis.agent

import trellis.{Bootstrap, Canon, Check, Navigate, Project}
import trellis.Core.*
import trellis.Delta.*
import trellis.Navigate.{NavigatorView, Selection}
import trellis.storage.{AssemblyCatalog, CompositionCatalog}
import trellis.storage.RepositoryProducts.*

/** Local graph read/query and change-staging API for AI agents. */
object AgentApi:
  private val maxEntityResults = 4096
  private val defaultEntityLimit = 256

  final case class Image(store: Store, session: WorkspaceSession, ledger: Ledger)

  def openSqueakImage: Either[String, (Image, String)] =
    val validating: Map[String, Graph => Either[String, Unit]] = Map(
      "validate-graph" -> { graph =>
        val errors = Check.validate(graph)
        if errors.isEmpty then Right(()) else Left(errors.mkString("; "))
      }
    )
    for
      assembly <- AssemblyCatalog.named("squeak-debug")
      compiled <- CompositionCatalog.compileAssembly(assembly, Bootstrap.graph, Set(ChangeId(Bootstrap.F11ChangeId)), validating)
      branchId = BranchId("squeak/local")
      image = Image(
        Store().addBranch(Branch(branchId, compiled.graph, Set.empty, None)),
        WorkspaceSession(branchId),
        Ledger()
      )
    yield image -> assembly.id

  def preview(image: Image): Either[String, Graph] =
    previewWorkspace(image.store, image.session)

  def listEntities(graph: Graph, prefix: Option[String], kind: Option[String], limit: Int): Vector[(EntityId, Node)] =
    val capped = limit.max(1).min(maxEntityResults)
    graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).map(entity -> _)
    }.filter { case (entity, node) =>
      prefix.forall(entity.value.startsWith) && kind.forall(_ == node.kind)
    }.take(capped)

  def entityDetail(graph: Graph, path: String): Either[String, (EntityId, Node, Vector[(String, ContentId, Edge)], Vector[(String, ContentId, Edge)])] =
    val entity = EntityId(path)
    for
      nodeId <- graph.entities.get(entity).toRight(s"unknown entity $path")
      node <- graph.nodes.get(nodeId).toRight(s"missing node content for $path")
      incoming = graph.edges.toVector.sortBy(_._1.value).flatMap { case (edgeId, edge) =>
        if edge.to.node == nodeId then Some(("incoming", edgeId, edge)) else None
      }
      outgoing = graph.edges.toVector.sortBy(_._1.value).flatMap { case (edgeId, edge) =>
        if edge.from.node == nodeId then Some(("outgoing", edgeId, edge)) else None
      }
    yield (entity, node, incoming, outgoing)

  def parseSelection(raw: String): Either[String, Selection] =
    raw.split(":", 2).toList match
      case "entity" :: path :: Nil if path.nonEmpty => Right(Selection.Entity(EntityId(path)))
      case "node" :: hash :: Nil =>
        Canon.validateHash(hash, "node ContentId").map(_ => Selection.Node(ContentId(hash)))
      case "edge" :: hash :: Nil =>
        Canon.validateHash(hash, "edge ContentId").map(_ => Selection.Edge(ContentId(hash)))
      case _ => Left(s"invalid selection $raw; expected entity:PATH, node:CONTENT_ID, or edge:CONTENT_ID")

  def stageOperations(image: Image, operations: Vector[Op], transcript: Vector[String]): Either[String, (Image, Graph)] =
    if operations.isEmpty then Left("operations must be non-empty")
    else if transcript.nonEmpty && transcript.size != operations.size then Left("transcript length must match operations length when provided")
    else
      val entries = if transcript.nonEmpty then transcript
      else operations.map(op => s"agent: ${encodeOp(op)}")
      val nextSession = operations.zip(entries).foldLeft(image.session) { case (session, (op, entry)) =>
        editWorkspace(session, op, entry)
      }
      previewWorkspace(image.store, nextSession).map { graph =>
        image.copy(session = nextSession) -> graph
      }

  def commit(image: Image, message: String): Either[String, (Image, WorkspaceCommit)] =
    commitWorkspace(image.store, image.session, message).map { committed =>
      image.copy(store = committed.store, session = committed.session) -> committed
    }

  def publish(image: Image, packageName: String, branch: String, publisher: String): Either[String, (Image, Publication)] =
    for
      graph <- previewWorkspace(image.store, image.session)
      result <- publishWorkspace(graph, image.ledger, image.store, image.session, packageName, branch, publisher)
    yield image.copy(ledger = result._1) -> result._2

  def decodeOpsRequest(body: String): Either[String, (Vector[Op], Vector[String])] =
    for
      json <- AgentJson.parse(body)
      opsJson <- AgentJson.field(json, "operations").flatMap(AgentJson.asArray)
      operations <- AgentJson.sequenceEither(opsJson.map(decodeOperation))
      transcript <- AgentJson.field(json, "transcript").fold(
        _ => Right(Vector.empty[String]),
        value => AgentJson.asArray(value).flatMap { items =>
          AgentJson.sequenceEither(items.map(item => AgentJson.asString(item)))
        }
      )
    yield operations -> transcript

  def decodeCommitRequest(body: String): Either[String, String] =
    for
      json <- AgentJson.parse(body)
      message <- AgentJson.field(json, "message").flatMap(AgentJson.asString).map(_.trim)
      _ <- Either.cond(message.nonEmpty, (), "commit message must be non-empty")
    yield message

  def decodePublishRequest(body: String): Either[String, (String, String, String)] =
    if body.trim.isEmpty then Right(("trellis/application/default", "workspace", "trellis-foundation"))
    else
      for
        json <- AgentJson.parse(body)
        packageName <- optionalString(json, "package", "trellis/application/default")
        branch <- optionalString(json, "branch", "workspace")
        publisher <- optionalString(json, "publisher", "trellis-foundation")
      yield (packageName, branch, publisher)

  private def optionalString(json: AgentJson.Json, name: String, default: String): Either[String, String] =
    AgentJson.field(json, name).fold(
      _ => Right(default),
      value => AgentJson.asString(value).map(_.trim).flatMap { text =>
        Either.cond(text.nonEmpty, text, s"$name must be non-empty")
      }
    )

  private def decodeOperation(json: AgentJson.Json): Either[String, Op] = json match
    case AgentJson.Json.Str(text) => decodeOp(text)
    case AgentJson.Json.Obj(fields) if fields.size == 1 =>
      fields.head match
        case ("replaceEntity", value) => decodeReplaceEntity(value)
        case ("removeEntity", value) => decodeRemoveEntity(value)
        case ("addNode", value) => decodeAddNode(value)
        case ("bindEntity", value) => decodeBindEntity(value)
        case ("connect", value) => decodeConnect(value)
        case ("disconnect", value) => decodeDisconnect(value)
        case ("addRoot", value) => decodeAddRoot(value)
        case ("removeRoot", value) => decodeRemoveRoot(value)
        case ("refineHole", value) => decodeRefineHole(value)
        case ("canon", value) => AgentJson.asString(value).flatMap(decodeOp)
        case (tag, _) => Left(s"unsupported operation tag $tag")
    case _ => Left("each operation must be a canon string or single-key object")

  private def decodeReplaceEntity(json: AgentJson.Json): Either[String, Op] =
    for
      fields <- AgentJson.asObject(json)
      entity <- requiredString(fields, "entity")
      kind <- requiredString(fields, "kind")
      attrs <- optionalStringMap(fields.get("attrs"))
    yield Op.ReplaceEntity(EntityId(entity), Node(kind, attrs = attrs))

  private def decodeRemoveEntity(json: AgentJson.Json): Either[String, Op] =
    for
      fields <- AgentJson.asObject(json)
      entity <- requiredString(fields, "entity")
    yield Op.RemoveEntity(EntityId(entity))

  private def decodeAddNode(json: AgentJson.Json): Either[String, Op] =
    for
      fields <- AgentJson.asObject(json)
      kind <- requiredString(fields, "kind")
      attrs <- optionalStringMap(fields.get("attrs"))
      ports <- optionalPorts(fields.get("ports"))
    yield Op.AddNode(Node(kind, ports = ports, attrs = attrs))

  private def decodeBindEntity(json: AgentJson.Json): Either[String, Op] =
    for
      fields <- AgentJson.asObject(json)
      entity <- requiredString(fields, "entity")
      nodeId <- requiredString(fields, "node").flatMap(hash => Canon.validateHash(hash, "bound node ContentId").map(_ => ContentId(hash)))
    yield Op.BindEntity(EntityId(entity), nodeId)

  private def decodeConnect(json: AgentJson.Json): Either[String, Op] =
    for
      fields <- AgentJson.asObject(json)
      fromNode <- requiredString(fields, "fromNode").flatMap(hash => Canon.validateHash(hash, "from node ContentId").map(_ => ContentId(hash)))
      fromPort <- requiredString(fields, "fromPort")
      toNode <- requiredString(fields, "toNode").flatMap(hash => Canon.validateHash(hash, "to node ContentId").map(_ => ContentId(hash)))
      toPort <- requiredString(fields, "toPort")
      role <- fields.get("role").fold(Right("value"): Either[String, String])(value => AgentJson.asString(value))
    yield Op.Connect(Edge(PortRef(fromNode, fromPort), PortRef(toNode, toPort), role))

  private def decodeDisconnect(json: AgentJson.Json): Either[String, Op] =
    for
      fields <- AgentJson.asObject(json)
      edge <- requiredString(fields, "edge").flatMap(hash => Canon.validateHash(hash, "edge ContentId").map(_ => ContentId(hash)))
    yield Op.Disconnect(edge)

  private def decodeAddRoot(json: AgentJson.Json): Either[String, Op] =
    for
      fields <- AgentJson.asObject(json)
      name <- requiredString(fields, "name")
      node <- requiredString(fields, "node").flatMap(hash => Canon.validateHash(hash, "root node ContentId").map(_ => ContentId(hash)))
    yield Op.AddRoot(name, node)

  private def decodeRemoveRoot(json: AgentJson.Json): Either[String, Op] =
    for
      fields <- AgentJson.asObject(json)
      name <- requiredString(fields, "name")
    yield Op.RemoveRoot(name)

  private def decodeRefineHole(json: AgentJson.Json): Either[String, Op] =
    for
      fields <- AgentJson.asObject(json)
      entity <- requiredString(fields, "entity")
      kind <- requiredString(fields, "kind")
      attrs <- optionalStringMap(fields.get("attrs"))
    yield Op.RefineHole(EntityId(entity), Node(kind, attrs = attrs))

  private def requiredString(fields: Map[String, AgentJson.Json], name: String): Either[String, String] =
    fields.get(name).toRight(s"missing $name").flatMap(AgentJson.asString).flatMap { value =>
      Either.cond(value.nonEmpty, value, s"$name must be non-empty")
    }

  private def optionalStringMap(json: Option[AgentJson.Json]): Either[String, Map[String, String]] = json match
    case None => Right(Map.empty)
    case Some(value) =>
      AgentJson.asObject(value).flatMap { fields =>
        AgentJson.sequenceEither(fields.toVector.map { case (key, item) =>
          AgentJson.asString(item).map(key -> _)
        }).map(_.toMap)
      }

  private def optionalPorts(json: Option[AgentJson.Json]): Either[String, Vector[Port]] = json match
    case None => Right(Vector.empty)
    case Some(value) =>
      AgentJson.asArray(value).flatMap { items =>
        AgentJson.sequenceEither(items.map { item =>
          for
            fields <- AgentJson.asObject(item)
            name <- requiredString(fields, "name")
            direction <- requiredString(fields, "direction").flatMap {
              case "in" => Right(Direction.In)
              case "out" => Right(Direction.Out)
              case other => Left(s"invalid port direction $other")
            }
            tyText <- requiredString(fields, "ty")
            ty <- Canon.decodeTy(tyText)
          yield Port(name, direction, ty)
        })
      }

  def encodeStatus(assemblyId: String, image: Image, graph: Graph): String =
    val branch = image.store.branches(image.session.branch)
    AgentJson.objectFields(
      Vector(
        "assembly" -> AgentJson.quote(assemblyId),
        "branch" -> AgentJson.quote(image.session.branch.value),
        "dirty" -> AgentJson.boolean(image.session.isDirty),
        "openOperations" -> AgentJson.number(image.session.operations.size),
        "closedChanges" -> AgentJson.number(branch.frontier.size),
        "graphRoot" -> AgentJson.quote(Canon.graphId(graph).value),
        "transcript" -> AgentJson.stringArray(image.session.transcript)
      )
    )

  def encodeEntities(graph: Graph, prefix: Option[String], kind: Option[String], limit: Int): String =
    val rows = listEntities(graph, prefix, kind, limit)
    val entities = rows.map { case (entity, node) =>
      AgentJson.objectFields(
        Vector(
          "path" -> AgentJson.quote(entity.value),
          "kind" -> AgentJson.quote(node.kind),
          "contentId" -> AgentJson.quote(graph.entities(entity).value),
          "attrs" -> encodeStringMap(node.attrs)
        )
      )
    }.mkString("[", ",", "]")
    AgentJson.objectFields(Vector("count" -> AgentJson.number(rows.size), "entities" -> entities))

  def encodeEntityDetail(graph: Graph, path: String): Either[String, String] =
    entityDetail(graph, path).map { case (entity, node, incoming, outgoing) =>
      val nodeId = graph.entities(entity)
      AgentJson.objectFields(
        Vector(
          "path" -> AgentJson.quote(entity.value),
          "kind" -> AgentJson.quote(node.kind),
          "contentId" -> AgentJson.quote(nodeId.value),
          "attrs" -> encodeStringMap(node.attrs),
          "ports" -> encodePorts(node.ports),
          "incoming" -> encodeIncidents(incoming),
          "outgoing" -> encodeIncidents(outgoing)
        )
      )
    }

  def encodeNavigate(view: NavigatorView): String =
    val items = view.items.map { item =>
      AgentJson.objectFields(
        Vector(
          "depth" -> AgentJson.number(item.depth),
          "relation" -> AgentJson.quote(item.relation),
          "selection" -> AgentJson.quote(Navigate.selectionKey(item.selection))
        )
      )
    }.mkString("[", ",", "]")
    AgentJson.objectFields(
      Vector(
        "center" -> AgentJson.quote(Navigate.selectionKey(view.center)),
        "items" -> items
      )
    )

  def encodeHistory(store: Store, entityPath: String): String =
    val entity = EntityId(entityPath)
    val changes = Navigate.entityHistory(store, entity)
    AgentJson.objectFields(
      Vector(
        "entity" -> AgentJson.quote(entity.value),
        "changes" -> AgentJson.stringArray(changes.map(_.value))
      )
    )

  def encodeLspDocument(graph: Graph): Either[String, String] =
    Project.lspDocument(graph).map { document =>
      val symbols = document.symbols.map { symbol =>
        AgentJson.objectFields(
          Vector(
            "name" -> AgentJson.quote(symbol.name),
            "entity" -> AgentJson.quote(symbol.entity.value),
            "line" -> AgentJson.number(symbol.position.line),
            "character" -> AgentJson.number(symbol.position.character)
          )
        )
      }.mkString("[", ",", "]")
      AgentJson.objectFields(
        Vector(
          "uri" -> AgentJson.quote(document.uri),
          "text" -> AgentJson.quote(document.text),
          "symbols" -> symbols
        )
      )
    }

  def encodeCommit(changeId: ChangeId, graph: Graph): String =
    AgentJson.objectFields(
      Vector(
        "change" -> AgentJson.quote(changeId.value),
        "graphRoot" -> AgentJson.quote(Canon.graphId(graph).value)
      )
    )

  def encodePublish(publication: Publication): String =
    AgentJson.objectFields(
      Vector(
        "publication" -> AgentJson.quote(publication.id.value),
        "graphRoot" -> AgentJson.quote(publication.graphRoot.value)
      )
    )

  def encodeGraphRoot(graph: Graph): String =
    AgentJson.objectFields(Vector("graphRoot" -> AgentJson.quote(Canon.graphId(graph).value), "dirty" -> AgentJson.boolean(true)))

  def encodeGraph(graph: Graph, assemblyId: String): String =
    val entityNames = graph.entities.toVector.groupMap(_._2)(_._1.value).view.mapValues(_.sorted).toMap
    val entities = graph.entities.toVector.sortBy(_._1.value).map { case (entity, id) =>
      val node = graph.nodes(id)
      val attrs = encodeStringMap(node.attrs)
      val ports = encodePorts(node.ports)
      AgentJson.objectFields(
        Vector(
          "entity" -> AgentJson.quote(entity.value),
          "contentId" -> AgentJson.quote(id.value),
          "kind" -> AgentJson.quote(node.kind),
          "attrs" -> attrs,
          "ports" -> ports
        )
      )
    }.mkString("[", ",", "]")
    val edges = graph.edges.toVector.sortBy(_._1.value).map { case (id, edge) =>
      val fromEntities = entityNames.getOrElse(edge.from.node, Vector.empty)
      val toEntities = entityNames.getOrElse(edge.to.node, Vector.empty)
      AgentJson.objectFields(
        Vector(
          "contentId" -> AgentJson.quote(id.value),
          "fromNode" -> AgentJson.quote(edge.from.node.value),
          "fromEntities" -> AgentJson.stringArray(fromEntities),
          "fromPort" -> AgentJson.quote(edge.from.port),
          "toNode" -> AgentJson.quote(edge.to.node.value),
          "toEntities" -> AgentJson.stringArray(toEntities),
          "toPort" -> AgentJson.quote(edge.to.port),
          "role" -> AgentJson.quote(edge.role)
        )
      )
    }.mkString("[", ",", "]")
    val roots = graph.roots.toVector.sortBy(_._1).map { case (name, id) =>
      val names = entityNames.getOrElse(id, Vector.empty)
      AgentJson.objectFields(
        Vector(
          "name" -> AgentJson.quote(name),
          "contentId" -> AgentJson.quote(id.value),
          "entities" -> AgentJson.stringArray(names)
        )
      )
    }.mkString("[", ",", "]")
    val layer = AgentJson.objectFields(
      Vector(
        "index" -> AgentJson.number(0),
        "relativePath" -> AgentJson.quote(s"assembly:$assemblyId"),
        "changeId" -> "null",
        "message" -> AgentJson.quote("assembled Squeak image"),
        "author" -> AgentJson.quote("assembly"),
        "dependencies" -> "[]",
        "status" -> AgentJson.quote("ok"),
        "error" -> "null",
        "ops" -> "[]",
        "graph" -> AgentJson.objectFields(
          Vector(
            "nodeCount" -> AgentJson.number(graph.nodes.size),
            "edgeCount" -> AgentJson.number(graph.edges.size),
            "entities" -> entities,
            "edges" -> edges,
            "roots" -> roots
          )
        ),
        "diff" -> AgentJson.objectFields(
          Vector(
            "addedEntities" -> "[]",
            "removedEntities" -> "[]",
            "replacedEntities" -> "[]",
            "addedRoots" -> "[]",
            "removedRoots" -> "[]"
          )
        )
      )
    )
    AgentJson.objectFields(Vector("layers" -> s"[$layer]", "unparsed" -> "[]", "unlinked" -> "[]"))

  def encodeRun(engine: String, run: trellis.engine.ExecutionResult, elapsedMs: Double): String =
    val trace = AgentJson.stringArray(run.trace)
    AgentJson.objectFields(
      Vector(
        "engine" -> AgentJson.quote(engine),
        "value" -> AgentJson.quote(run.value.toString),
        "reductions" -> AgentJson.number(run.reductions),
        "rounds" -> AgentJson.number(run.rounds),
        "elapsedMs" -> elapsedMs.toString,
        "trace" -> trace
      )
    )

  def parseEntityLimit(raw: Option[String]): Int =
    raw.flatMap(_.toIntOption).getOrElse(defaultEntityLimit).max(1).min(maxEntityResults)

  private def encodeStringMap(values: Map[String, String]): String =
    AgentJson.objectFields(values.toVector.sortBy(_._1).map { case (key, value) => key -> AgentJson.quote(value) })

  private def encodePorts(ports: Vector[Port]): String =
    ports.map { port =>
      AgentJson.objectFields(
        Vector(
          "name" -> AgentJson.quote(port.name),
          "direction" -> AgentJson.quote(port.direction.toString.toLowerCase),
          "ty" -> AgentJson.quote(Canon.encodeTy(port.ty))
        )
      )
    }.mkString("[", ",", "]")

  private def encodeIncidents(values: Vector[(String, ContentId, Edge)]): String =
    values.map { case (relation, edgeId, edge) =>
      AgentJson.objectFields(
        Vector(
          "relation" -> AgentJson.quote(relation),
          "edgeId" -> AgentJson.quote(edgeId.value),
          "fromNode" -> AgentJson.quote(edge.from.node.value),
          "fromPort" -> AgentJson.quote(edge.from.port),
          "toNode" -> AgentJson.quote(edge.to.node.value),
          "toPort" -> AgentJson.quote(edge.to.port),
          "role" -> AgentJson.quote(edge.role)
        )
      )
    }.mkString("[", ",", "]")
