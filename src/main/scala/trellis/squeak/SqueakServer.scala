package trellis.squeak

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import trellis.{Bootstrap, Canon, Check}
import trellis.Core.*
import trellis.Delta.Op
import trellis.engine.*
import trellis.storage.{AssemblyCatalog, CompositionCatalog, PostActions}
import trellis.storage.RepositoryProducts.*

/** Stateful loopback image for the Squeak-like browser, transcript, inspector, and evaluator. */
object SqueakServer:
  private final case class Image(store: Store, session: WorkspaceSession, ledger: Ledger)

  private val assembly = AssemblyCatalog.named("squeak-debug").fold(error => throw IllegalStateException(error), identity)
  private val validating: Map[String, PostActions.Handler] = Map("validate-graph" -> { graph =>
    val errors = Check.validate(graph)
    if errors.isEmpty then Right(()) else Left(errors.mkString("; "))
  })
  private val compiled = CompositionCatalog.compileAssembly(assembly, Bootstrap.graph, Set(ChangeId(Bootstrap.F11ChangeId)), validating)
    .fold(error => throw IllegalStateException(error), identity)
  private val branchId = BranchId("squeak/local")
  private var image = Image(Store().addBranch(Branch(branchId, compiled.graph, Set.empty, None)), WorkspaceSession(branchId), Ledger())

  def main(args: Array[String]): Unit =
    val port = args.headOption.flatMap(_.toIntOption).getOrElse(8422)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/execute", execute)
    server.createContext("/workspace/status", status)
    server.createContext("/workspace/graph", graph)
    server.createContext("/workspace/edit", edit)
    server.createContext("/workspace/commit", commit)
    server.createContext("/workspace/publish", publishCurrent)
    server.setExecutor(Executors.newCachedThreadPool())
    server.start()
    println(s"Trellis Squeak image listening on http://127.0.0.1:$port (${assembly.id})")
    Thread.currentThread().join()

  private def execute(exchange: HttpExchange): Unit = handle(exchange) { params =>
    for
      workspace <- params.get("workspace").filter(_.nonEmpty).toRight("missing workspace")
      rawArgument <- params.get("arg").toRight("missing arg")
      argument <- rawArgument.toLongOption.map(BigInt.apply).toRight("arg must be a signed 64-bit integer")
      _ <- Either.cond(argument >= 0, (), "arg must be a Nat")
      engine <- params.get("engine").toRight("missing engine").flatMap(Engines.named)
      graph <- synchronized(previewWorkspace(image.store, image.session))
      started = System.nanoTime()
      run <- engine.execute(ExecutionRequest(graph, EntityId(workspace), Vector(argument)))
      elapsed = (System.nanoTime() - started).toDouble / 1000000.0
    yield encodeRun(engine.id, run, elapsed)
  }

  private def status(exchange: HttpExchange): Unit = handle(exchange) { _ => synchronized {
    previewWorkspace(image.store, image.session).map { graph =>
      val branch = image.store.branches(branchId)
      "{" + s"\"assembly\":${quote(assembly.id)},\"branch\":${quote(branchId.value)}," +
        s"\"dirty\":${image.session.isDirty},\"openOperations\":${image.session.operations.size}," +
        s"\"closedChanges\":${branch.frontier.size},\"graphRoot\":${quote(Canon.graphId(graph).value)}," +
        s"\"transcript\":${image.session.transcript.map(quote).mkString("[", ",", "]")}" + "}"
    }
  }}

  private def graph(exchange: HttpExchange): Unit = handle(exchange) { _ => synchronized {
    previewWorkspace(image.store, image.session).map(encodeGraph)
  }}

  private def edit(exchange: HttpExchange): Unit = handle(exchange) { params => synchronized {
    for
      entity <- params.get("entity").filter(_.nonEmpty).toRight("missing entity")
      kind <- params.get("kind").filter(_.nonEmpty).toRight("missing kind")
      source <- params.get("source").toRight("missing source")
      entry = s"$entity := $source"
      next = editWorkspace(image.session, Op.ReplaceEntity(EntityId(entity), Node(kind, attrs = Map("source" -> source))), entry)
      graph <- previewWorkspace(image.store, next)
    yield
      image = image.copy(session = next)
      s"{\"dirty\":true,\"graphRoot\":${quote(Canon.graphId(graph).value)}}"
  }}

  private def commit(exchange: HttpExchange): Unit = handle(exchange) { params => synchronized {
    commitWorkspace(image.store, image.session, params.getOrElse("message", "Squeak transcript change")).map { committed =>
      image = image.copy(store = committed.store, session = committed.session)
      s"{\"change\":${quote(committed.changeId.value)},\"graphRoot\":${quote(Canon.graphId(committed.graph).value)}}"
    }
  }}

  private def publishCurrent(exchange: HttpExchange): Unit = handle(exchange) { params => synchronized {
    publishWorkspace(compiled.graph, image.ledger, image.store, image.session,
      params.getOrElse("package", "trellis/application/default"), params.getOrElse("branch", "workspace"),
      params.getOrElse("publisher", "trellis-foundation")).map { case (ledger, publication) =>
      image = image.copy(ledger = ledger)
      s"{\"publication\":${quote(publication.id.value)},\"graphRoot\":${quote(publication.graphRoot.value)}}"
    }
  }}

  private def handle(exchange: HttpExchange)(operation: Map[String, String] => Either[String, String]): Unit =
    if exchange.getRequestMethod != "GET" then respond(exchange, 405, "{\"error\":\"method not allowed\"}")
    else operation(parameters(exchange)) match
      case Right(body) => respond(exchange, 200, body)
      case Left(error) => respond(exchange, 400, "{\"error\":" + quote(error) + "}")

  private def parameters(exchange: HttpExchange): Map[String, String] =
    Option(exchange.getRequestURI.getRawQuery).toVector.flatMap(_.split("&")).flatMap { field =>
      field.split("=", 2) match
        case Array(key, value) => Some(decode(key) -> decode(value))
        case _ => None
    }.toMap

  private def encodeRun(engine: String, run: ExecutionResult, elapsed: Double): String =
    val trace = run.trace.map(quote).mkString("[", ",", "]")
    "{\"engine\":" + quote(engine) + ",\"value\":" + quote(run.value.toString) +
      s",\"reductions\":${run.reductions},\"rounds\":${run.rounds},\"elapsedMs\":$elapsed,\"trace\":$trace}"

  private def encodeGraph(graph: Graph): String =
    val entityNames = graph.entities.toVector.groupMap(_._2)(_._1.value).view.mapValues(_.sorted).toMap
    val entities = graph.entities.toVector.sortBy(_._1.value).map { case (entity, id) =>
      val node = graph.nodes(id)
      val attrs = node.attrs.toVector.sortBy(_._1).map { case (key, value) => s"[${quote(key)},${quote(value)}]" }.mkString("[", ",", "]")
      val ports = node.ports.map(port => s"{\"name\":${quote(port.name)},\"direction\":${quote(port.direction.toString.toLowerCase)},\"ty\":${quote(Canon.encodeTy(port.ty))}}").mkString("[", ",", "]")
      s"{\"entity\":${quote(entity.value)},\"contentId\":${quote(id.value)},\"kind\":${quote(node.kind)},\"attrs\":$attrs,\"ports\":$ports}"
    }.mkString("[", ",", "]")
    val edges = graph.edges.toVector.sortBy(_._1.value).map { case (id, edge) =>
      val fromEntities = entityNames.getOrElse(edge.from.node, Vector.empty).map(quote).mkString("[", ",", "]")
      val toEntities = entityNames.getOrElse(edge.to.node, Vector.empty).map(quote).mkString("[", ",", "]")
      s"{\"contentId\":${quote(id.value)},\"fromNode\":${quote(edge.from.node.value)},\"fromEntities\":$fromEntities,\"fromPort\":${quote(edge.from.port)},\"toNode\":${quote(edge.to.node.value)},\"toEntities\":$toEntities,\"toPort\":${quote(edge.to.port)},\"role\":${quote(edge.role)}}"
    }.mkString("[", ",", "]")
    val roots = graph.roots.toVector.sortBy(_._1).map { case (name, id) =>
      val names = entityNames.getOrElse(id, Vector.empty).map(quote).mkString("[", ",", "]")
      s"{\"name\":${quote(name)},\"contentId\":${quote(id.value)},\"entities\":$names}"
    }.mkString("[", ",", "]")
    val layer = s"{\"index\":0,\"relativePath\":${quote("assembly:" + assembly.id)},\"changeId\":null,\"message\":${quote("assembled Squeak image")},\"author\":${quote("assembly")},\"dependencies\":[],\"status\":\"ok\",\"error\":null,\"ops\":[],\"graph\":{\"nodeCount\":${graph.nodes.size},\"edgeCount\":${graph.edges.size},\"entities\":$entities,\"edges\":$edges,\"roots\":$roots},\"diff\":{\"addedEntities\":[],\"removedEntities\":[],\"replacedEntities\":[],\"addedRoots\":[],\"removedRoots\":[]}}"
    s"{\"layers\":[$layer],\"unparsed\":[],\"unlinked\":[]}"

  private def quote(value: String): String =
    "\"" + value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    } + "\""

  private def decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.getResponseHeaders.set("Cache-Control", "no-store")
    exchange.sendResponseHeaders(status, bytes.length)
    val output = exchange.getResponseBody
    try output.write(bytes) finally output.close()
