package trellis.agent

import java.nio.file.Path
import scala.io.Source

/** Stdio MCP server exposing the local Trellis graph agent API. */
object AgentMcp:
  private val protocolVersion = "2024-11-05"

  private val toolsJson =
    """{"tools":[""" +
      tool(
        "list_entities",
        "List graph entities by optional prefix, kind, and limit without dumping the full graph.",
        """{"type":"object","properties":{"prefix":{"type":"string"},"kind":{"type":"string"},"limit":{"type":"integer"}}}"""
      ) + "," +
      tool(
        "get_entity",
        "Fetch one entity path with attrs, ports, and incident edges.",
        """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
      ) + "," +
      tool(
        "navigate",
        "Semantic breadth-first navigation from entity:PATH, node:CONTENT_ID, or edge:CONTENT_ID.",
        """{"type":"object","properties":{"center":{"type":"string"}},"required":["center"]}"""
      ) + "," +
      tool(
        "entity_history",
        "Closed change ids touching an entity footprint on the local branch.",
        """{"type":"object","properties":{"entity":{"type":"string"}},"required":["entity"]}"""
      ) + "," +
      tool(
        "workspace_status",
        "Branch metadata, dirty flag, open operation count, transcript, and graph root.",
        """{"type":"object","properties":{}}"""
      ) + "," +
      tool(
        "apply_ops",
        "Append Delta.Op values to the open workspace delta. Body matches POST /workspace/ops.",
        """{"type":"object","properties":{"operations":{"type":"array"},"transcript":{"type":"array","items":{"type":"string"}}},"required":["operations"]}"""
      ) + "," +
      tool(
        "commit_workspace",
        "Validate and seal the open workspace delta as one immutable change.",
        """{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}"""
      ) + "," +
      tool(
        "publish_workspace",
        "Publish the closed workspace frontier when the open delta is clean.",
        """{"type":"object","properties":{"package":{"type":"string"},"branch":{"type":"string"},"publisher":{"type":"string"}}}"""
      ) + "," +
      tool(
        "execute",
        "Evaluate stored Trellis IR for a workspace entity with a Nat argument.",
        """{"type":"object","properties":{"workspace":{"type":"string"},"arg":{"type":"integer"},"engine":{"type":"string","enum":["deltanet","ceskr"]}},"required":["workspace","arg","engine"]}"""
      ) +
      "]}"

  def main(args: Array[String]): Unit =
    val workspace = args.headOption.filter(_.nonEmpty).map(Path.of(_))
    AgentRuntime.start(workspace).fold(
      err =>
        System.err.println(err)
        sys.exit(1),
      id => System.err.println(s"Trellis MCP ready ($id)")
    )
    val source = Source.stdin
    try source.getLines().foreach(line => if line.trim.nonEmpty then handleLine(line.trim))
    finally source.close()

  private def tool(name: String, description: String, inputSchema: String): String =
    AgentJson.objectFields(
      Vector(
        "name" -> AgentJson.quote(name),
        "description" -> AgentJson.quote(description),
        "inputSchema" -> inputSchema
      )
    )

  private def handleLine(line: String): Unit =
    AgentJson.parse(line) match
      case Left(error) => emitError(None, -32700, s"parse error: $error")
      case Right(json) =>
        val method = fieldString(json, "method")
        val id = fieldId(json)
        if method.contains("notifications/") then ()
        else method match
          case "initialize" => emitResult(id, initializeResult)
          case "tools/list" => emitResult(id, toolsJson)
          case "tools/call" => handleToolCall(id, json)
          case "ping" => emitResult(id, "{}")
          case other if id.isDefined => emitError(id, -32601, s"method not found: $other")
          case _ => ()

  private def initializeResult: String =
    AgentJson.objectFields(
      Vector(
        "protocolVersion" -> AgentJson.quote(protocolVersion),
        "capabilities" -> """{"tools":{}}""",
        "serverInfo" -> AgentJson.objectFields(Vector("name" -> AgentJson.quote("trellis"), "version" -> AgentJson.quote("0.1.0")))
      )
    )

  private def handleToolCall(id: Option[String], json: AgentJson.Json): Unit =
    val result = for
      params <- fieldObject(json, "params")
      name <- params.get("name").toRight("missing tool name").flatMap(AgentJson.asString)
      args <- params.get("arguments").fold(Right(Map.empty[String, AgentJson.Json]): Either[String, Map[String, AgentJson.Json]]) { value =>
        AgentJson.asObject(value)
      }
      body <- invokeTool(name, args)
    yield toolResult(body)
    result.fold(error => emitToolError(id, error), body => emitResult(id, body))

  private def invokeTool(name: String, args: Map[String, AgentJson.Json]): Either[String, String] = name match
    case "list_entities" =>
      AgentRuntime.listEntities(
        optionalArg(args, "prefix"),
        optionalArg(args, "kind"),
        intArg(args, "limit").getOrElse(AgentApi.parseEntityLimit(None))
      )
    case "get_entity" =>
      requiredArg(args, "path").flatMap(AgentRuntime.entity)
    case "navigate" =>
      requiredArg(args, "center").flatMap(AgentRuntime.navigate)
    case "entity_history" =>
      requiredArg(args, "entity").flatMap(AgentRuntime.history)
    case "workspace_status" =>
      AgentRuntime.status
    case "apply_ops" =>
      for
        operations <- args.get("operations").toRight("missing operations")
        transcript = args.get("transcript").map(AgentJson.render).getOrElse("[]")
        body <- AgentRuntime.applyOps(s"""{"operations":${AgentJson.render(operations)},"transcript":$transcript}""")
      yield body
    case "commit_workspace" =>
      requiredArg(args, "message").flatMap(AgentRuntime.commit)
    case "publish_workspace" =>
      AgentRuntime.publish(
        optionalArg(args, "package").getOrElse("trellis/application/default"),
        optionalArg(args, "branch").getOrElse("workspace"),
        optionalArg(args, "publisher").getOrElse("trellis-foundation")
      )
    case "execute" =>
      for
        workspace <- requiredArg(args, "workspace")
        engine <- requiredArg(args, "engine")
        arg <- intArg(args, "arg").toRight("missing arg")
        body <- AgentRuntime.execute(workspace, BigInt(arg), engine)
      yield body
    case other => Left(s"unknown tool $other")

  private def optionalArg(args: Map[String, AgentJson.Json], name: String): Option[String] =
    args.get(name).flatMap {
      case AgentJson.Json.Str(value) => Some(value)
      case _ => None
    }

  private def requiredArg(args: Map[String, AgentJson.Json], name: String): Either[String, String] =
    optionalArg(args, name).toRight(s"missing $name")

  private def intArg(args: Map[String, AgentJson.Json], name: String): Option[Int] =
    args.get(name).flatMap {
      case AgentJson.Json.Num(value) => Some(value)
      case AgentJson.Json.Str(value) => value.toIntOption
      case _ => None
    }

  private def fieldString(json: AgentJson.Json, name: String): String =
    AgentJson.field(json, name).flatMap(AgentJson.asString).getOrElse("")

  private def fieldId(json: AgentJson.Json): Option[String] =
    AgentJson.field(json, "id") match
      case Right(AgentJson.Json.Num(value)) => Some(value.toString)
      case Right(AgentJson.Json.Str(value)) => Some(value)
      case _ => None

  private def fieldObject(json: AgentJson.Json, name: String): Either[String, Map[String, AgentJson.Json]] =
    AgentJson.field(json, name).flatMap(AgentJson.asObject)

  private def toolResult(text: String): String =
    AgentJson.objectFields(
      Vector(
        "content" -> s"""[{"type":${AgentJson.quote("text")},"text":${AgentJson.quote(text)}}]""",
        "isError" -> AgentJson.boolean(false)
      )
    )

  private def emitToolError(id: Option[String], message: String): Unit =
    emitResult(
      id,
      AgentJson.objectFields(
        Vector(
          "content" -> s"""[{"type":${AgentJson.quote("text")},"text":${AgentJson.quote(message)}}]""",
          "isError" -> AgentJson.boolean(true)
        )
      )
    )

  private def emitResult(id: Option[String], result: String): Unit =
    id.foreach { value =>
      Console.out.println(
        AgentJson.objectFields(Vector("jsonrpc" -> AgentJson.quote("2.0"), "id" -> value, "result" -> result))
      )
      Console.out.flush()
    }

  private def emitError(id: Option[String], code: Int, message: String): Unit =
    id.foreach { value =>
      val error = AgentJson.objectFields(Vector("code" -> code.toString, "message" -> AgentJson.quote(message)))
      Console.out.println(AgentJson.objectFields(Vector("jsonrpc" -> AgentJson.quote("2.0"), "id" -> value, "error" -> error)))
      Console.out.flush()
    }
