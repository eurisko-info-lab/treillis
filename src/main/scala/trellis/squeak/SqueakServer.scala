package trellis.squeak

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Executors
import trellis.agent.{AgentApi, AgentJson, AgentRuntime}

/** Stateful loopback image for the Squeak browser and the local agent API. */
object SqueakServer:
  def main(args: Array[String]): Unit =
    val port = args.headOption.flatMap(_.toIntOption).getOrElse(8422)
    val workspace = args.drop(1).headOption.filter(_.nonEmpty).map(Path.of(_))
    val assemblyId = AgentRuntime.start(workspace).fold(error => throw IllegalStateException(error), identity)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/execute", execute)
    server.createContext("/entities", entities)
    server.createContext("/entity", entity)
    server.createContext("/navigate", navigate)
    server.createContext("/history", history)
    server.createContext("/lsp/document", lspDocument)
    server.createContext("/workspace/status", status)
    server.createContext("/workspace/graph", graph)
    server.createContext("/workspace/edit", edit)
    server.createContext("/workspace/ops", workspaceOps)
    server.createContext("/workspace/commit", commit)
    server.createContext("/workspace/publish", publishCurrent)
    server.setExecutor(Executors.newCachedThreadPool())
    server.start()
    println(s"Trellis agent API listening on http://127.0.0.1:$port ($assemblyId)")
    Thread.currentThread().join()

  private def execute(exchange: HttpExchange): Unit =
    handleGet(exchange) { params =>
      for
        workspace <- params.get("workspace").filter(_.nonEmpty).toRight("missing workspace")
        rawArgument <- params.get("arg").toRight("missing arg")
        argument <- rawArgument.toLongOption.map(BigInt.apply).toRight("arg must be a signed 64-bit integer")
        engine <- params.get("engine").toRight("missing engine")
        body <- AgentRuntime.execute(workspace, argument, engine)
      yield body
    }

  private def entities(exchange: HttpExchange): Unit =
    handleGet(exchange) { params =>
      AgentRuntime.listEntities(
        params.get("prefix").filter(_.nonEmpty),
        params.get("kind").filter(_.nonEmpty),
        AgentApi.parseEntityLimit(params.get("limit"))
      )
    }

  private def entity(exchange: HttpExchange): Unit =
    handleGet(exchange) { params =>
      params.get("path").filter(_.nonEmpty).toRight("missing path").flatMap(AgentRuntime.entity)
    }

  private def navigate(exchange: HttpExchange): Unit =
    handleGet(exchange) { params =>
      params.get("center").filter(_.nonEmpty).toRight("missing center").flatMap(AgentRuntime.navigate)
    }

  private def history(exchange: HttpExchange): Unit =
    handleGet(exchange) { params =>
      params.get("entity").filter(_.nonEmpty).toRight("missing entity").flatMap(AgentRuntime.history)
    }

  private def lspDocument(exchange: HttpExchange): Unit =
    handleGet(exchange) { _ => AgentRuntime.lspDocument }

  private def status(exchange: HttpExchange): Unit =
    handleGet(exchange) { _ => AgentRuntime.status }

  private def graph(exchange: HttpExchange): Unit =
    handleGet(exchange) { _ => AgentRuntime.graph }

  private def edit(exchange: HttpExchange): Unit =
    handle(exchange, allowGet = true, allowPost = true) { (method, params, body) =>
      val editParams =
        if method == "POST" && body.trim.nonEmpty then
          for
            json <- AgentJson.parse(body)
            entity <- AgentJson.field(json, "entity").flatMap(AgentJson.asString)
            kind <- AgentJson.field(json, "kind").flatMap(AgentJson.asString)
            source <- AgentJson.field(json, "source").flatMap(AgentJson.asString)
          yield (entity, kind, source)
        else
          for
            entity <- params.get("entity").filter(_.nonEmpty).toRight("missing entity")
            kind <- params.get("kind").filter(_.nonEmpty).toRight("missing kind")
            source <- params.get("source").toRight("missing source")
          yield (entity, kind, source)
      editParams.flatMap { case (entity, kind, source) =>
        AgentRuntime.replaceEntity(entity, kind, source)
      }
    }

  private def workspaceOps(exchange: HttpExchange): Unit =
    handle(exchange, allowGet = false, allowPost = true) { (_, _, body) =>
      AgentRuntime.applyOps(body)
    }

  private def commit(exchange: HttpExchange): Unit =
    handle(exchange, allowGet = true, allowPost = true) { (method, params, body) =>
      val message =
        if method == "POST" && body.trim.nonEmpty then AgentApi.decodeCommitRequest(body)
        else params.get("message").filter(_.trim.nonEmpty).toRight("missing message")
      message.flatMap(AgentRuntime.commit)
    }

  private def publishCurrent(exchange: HttpExchange): Unit =
    handle(exchange, allowGet = true, allowPost = true) { (method, _, body) =>
      val args =
        if method == "POST" then AgentApi.decodePublishRequest(body)
        else Right(("trellis/application/default", "workspace", "trellis-foundation"))
      args.flatMap { case (packageName, branch, publisher) =>
        AgentRuntime.publish(packageName, branch, publisher)
      }
    }

  private def handleGet(exchange: HttpExchange)(operation: Map[String, String] => Either[String, String]): Unit =
    handle(exchange, allowGet = true, allowPost = false) { (_, params, _) => operation(params) }

  private def handle(exchange: HttpExchange, allowGet: Boolean, allowPost: Boolean)(
      operation: (String, Map[String, String], String) => Either[String, String]
  ): Unit =
    val method = exchange.getRequestMethod
    if method == "GET" && allowGet then operation(method, parameters(exchange), "").fold(respondError(exchange, _), respondOk(exchange, _))
    else if method == "POST" && allowPost then
      val body = readBody(exchange)
      operation(method, parameters(exchange), body).fold(respondError(exchange, _), respondOk(exchange, _))
    else respond(exchange, 405, errorBody("method not allowed"))

  private def readBody(exchange: HttpExchange): String =
    val stream = exchange.getRequestBody
    try new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    finally stream.close()

  private def parameters(exchange: HttpExchange): Map[String, String] =
    Option(exchange.getRequestURI.getRawQuery).toVector.flatMap(_.split("&")).flatMap { field =>
      field.split("=", 2) match
        case Array(key, value) => Some(decode(key) -> decode(value))
        case _ => None
    }.toMap

  private def respondOk(exchange: HttpExchange, body: String): Unit = respond(exchange, 200, body)
  private def respondError(exchange: HttpExchange, error: String): Unit = respond(exchange, 400, errorBody(error))
  private def errorBody(error: String): String = AgentJson.objectFields(Vector("error" -> AgentJson.quote(error)))

  private def decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

  private def respond(exchange: HttpExchange, status: Int, body: String): Unit =
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.getResponseHeaders.set("Cache-Control", "no-store")
    exchange.sendResponseHeaders(status, bytes.length)
    val output = exchange.getResponseBody
    try output.write(bytes) finally output.close()
