package trellis

import java.util.Arrays
import trellis.Core.*
import trellis.Delta.*
import trellis.TestSupport.*

object FoundationTest:
  private def right[A](value: Either[String, A]): A = value.fold(err => throw new AssertionError(err), identity)

  val tests = Vector(
    Test("F1 delta is canonical data with a frozen content id", () => {
      val change = Bootstrap.f1Change
      equal(Change.id(change).value, Bootstrap.F1ChangeId)
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
      check(Arrays.equals(Delta.encodeChangeBytes(change), Delta.encodeChange(change).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
    }),
    Test("F1 is derived only from F0 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f0, Bootstrap.f1Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f1)))
      equal(Canon.graphId(derived).value, Bootstrap.F1Root)
    }),
    Test("F1 semantic schema is graph data rather than Scala node cases", () => {
      check(Bootstrap.f1SchemaEntities.subsetOf(Bootstrap.f1.entities.keySet))
      Bootstrap.f1SchemaEntities.foreach { entity =>
        val node = Bootstrap.f1.entity(entity).getOrElse(throw new AssertionError(s"missing ${entity.value}"))
        equal(node.kind, "meta.schema")
        equal(node.attrs.get("foundation"), Some("F1"))
        check(node.port("self").exists(_.direction == Direction.Out), s"${entity.value} lacks schema self output")
      }
    }),
    Test("F1 schema relationships are first-class typed graph edges", () => {
      equal(Bootstrap.f1.edges.size, 12)
      check(Check.validate(Bootstrap.f1).isEmpty)
      val roles = Bootstrap.f1.edges.values.map(_.role).toSet
      check(Set("schema.type", "schema.mode", "schema.capability", "schema.port", "schema.node-kind", "schema.edge-kind", "schema.graph", "schema.change").subsetOf(roles))
    }),
    Test("replacing an entity prunes obsolete unreferenced semantic content", () => {
      val old = Node("old")
      val oldId = Canon.nodeId(old)
      val entity = EntityId("app.x")
      val base = Graph(nodes = Map(oldId -> old), entities = Map(entity -> oldId))
      val next = right(Delta.applyChange(base, Change(Set.empty, Vector(Op.ReplaceEntity(entity, Node("new"))), "replace")))
      check(!next.nodes.contains(oldId))
      equal(next.entity(entity).map(_.kind), Some("new"))
    })
  )
