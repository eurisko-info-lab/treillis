package trellis

import java.util.Arrays
import trellis.agent.{AgentApi, AgentJson}
import trellis.Core.*
import trellis.Delta.*
import trellis.Navigate.Selection
import trellis.storage.RepositoryProducts.*
import trellis.TestSupport.*

object AgentApiTest:
  private def right[A](value: Either[String, A]): A = value.fold(err => throw new AssertionError(err), identity)

  private def sampleImage: AgentApi.Image =
    val (image, _) = right(AgentApi.openSqueakImage)
    image

  val tests = Vector(
    Test("agent API lists entities by prefix without dumping the whole graph", () => {
      val graph = right(AgentApi.preview(sampleImage))
      val rows = AgentApi.listEntities(graph, Some("example."), None, 32)
      check(rows.exists(_._1 == EntityId("example.tailrec.fibonacci")))
      check(!rows.exists(_._1 == EntityId("ceskr-transitions.schema")))
      val encoded = AgentApi.encodeEntities(graph, Some("example."), None, 32)
      check(encoded.contains("example.tailrec.fibonacci"))
    }),
    Test("agent API returns entity detail and navigates semantically", () => {
      val graph = right(AgentApi.preview(sampleImage))
      val detail = right(AgentApi.encodeEntityDetail(graph, "example.tailrec.fibonacci"))
      check(detail.contains("example.tailrec.fibonacci"))
      val view = right(Navigate.graphView(graph, Selection.Entity(EntityId("example.tailrec.fibonacci"))))
      val encoded = AgentApi.encodeNavigate(view)
      check(encoded.contains("entity:example.tailrec.fibonacci"))
    }),
    Test("agent API decodes structured and canon operations", () => {
      val structured =
        """{"operations":[{"replaceEntity":{"entity":"workspace.scratch","kind":"app.function","attrs":{"source":"42"}}}]}"""
      val (structuredOps, transcript) = right(AgentApi.decodeOpsRequest(structured))
      equal(transcript, Vector.empty)
      equal(structuredOps.size, 1)
      equal(structuredOps.head, Op.ReplaceEntity(EntityId("workspace.scratch"), Node("app.function", attrs = Map("source" -> "42"))))

      val canonOp = Delta.encodeOp(structuredOps.head)
      val (canonOps, _) = right(AgentApi.decodeOpsRequest(s"""{"operations":["$canonOp"]}"""))
      equal(canonOps, structuredOps)
    }),
    Test("agent API stages, commits, and records entity history", () => {
      val initial = sampleImage
      val staged = right(AgentApi.stageOperations(
        initial,
        Vector(Op.ReplaceEntity(EntityId("workspace.scratch"), Node("app.function", attrs = Map("source" -> "42")))),
        Vector("workspace.scratch := 42")
      ))
      check(staged._1.session.isDirty)
      check(right(AgentApi.preview(staged._1)).entities.contains(EntityId("workspace.scratch")))

      val committed = right(AgentApi.commit(staged._1, "agent change"))
      check(!committed._1.session.isDirty)
      val history = AgentApi.encodeHistory(committed._1.store, "workspace.scratch")
      check(history.contains(committed._2.changeId.value))
    }),
    Test("agent JSON parser round-trips simple objects and arrays", () => {
      val parsed = right(AgentJson.parse("""{"message":"commit","operations":[{"removeEntity":{"entity":"x.y"}}]}"""))
      equal(right(AgentJson.field(parsed, "message").flatMap(AgentJson.asString)), "commit")
      val operations = right(AgentJson.field(parsed, "operations").flatMap(AgentJson.asArray))
      equal(operations.size, 1)
    })
  )
