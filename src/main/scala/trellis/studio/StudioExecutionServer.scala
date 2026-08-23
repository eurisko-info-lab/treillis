package trellis.studio

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.{InetSocketAddress, URLDecoder}
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import trellis.Core.*
import trellis.engine.*
import trellis.storage.RepositoryProducts

/** Loopback engine-registry service used by delta-web; all evaluation stays in Scala. */
object StudioExecutionServer:
  def main(args: Array[String]): Unit =
    val port = args.headOption.flatMap(_.toIntOption).getOrElse(8422)
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    server.createContext("/execute", handle)
    server.setExecutor(Executors.newCachedThreadPool())
    server.start()
    println(s"Studio execution server listening on http://127.0.0.1:$port")
    Thread.currentThread().join()

  private def handle(exchange: HttpExchange): Unit =
    if exchange.getRequestMethod != "GET" then respond(exchange, 405, "{\"error\":\"method not allowed\"}")
    else
      val params = Option(exchange.getRequestURI.getRawQuery).toVector.flatMap(_.split("&")).flatMap { field =>
        field.split("=", 2) match
          case Array(key, value) => Some(decode(key) -> decode(value))
          case _ => None
      }.toMap
      val result = for
        workspace <- params.get("workspace").filter(_.nonEmpty).toRight("missing workspace")
        rawArgument <- params.get("arg").toRight("missing arg")
        argument <- rawArgument.toLongOption.map(BigInt.apply).toRight("arg must be a signed 64-bit integer")
        _ <- Either.cond(argument >= 0, (), "arg must be a Nat")
        engine <- params.get("engine").toRight("missing engine").flatMap(Engines.named)
        started = System.nanoTime()
        run <- engine.execute(ExecutionRequest(trellis.storage.RepositoryProducts.graph, EntityId(workspace), Vector(argument)))
        elapsed = (System.nanoTime() - started).toDouble / 1000000.0
      yield encodeRun(engine.id, run, elapsed)
      result match
        case Right(body) => respond(exchange, 200, body)
        case Left(error) => respond(exchange, 400, "{\"error\":" + quote(error) + "}")

  private def encodeRun(engine: String, run: ExecutionResult, elapsed: Double): String =
    val trace = run.trace.map(quote).mkString("[", ",", "]")
    "{\"engine\":" + quote(engine) + ",\"value\":" + quote(run.value.toString) +
      s",\"reductions\":${run.reductions},\"rounds\":${run.rounds},\"elapsedMs\":$elapsed,\"trace\":$trace}"

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
