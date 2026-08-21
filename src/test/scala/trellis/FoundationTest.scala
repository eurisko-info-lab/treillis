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
    }),
    Test("F2 delta is canonical data depending exactly on F1", () => {
      val change = Bootstrap.f2Change
      equal(Change.id(change).value, Bootstrap.F2ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F1ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F2 is derived only from F1 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f1, Bootstrap.f2Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f2)))
      equal(Canon.graphId(derived).value, Bootstrap.F2Root)
    }),
    Test("F2 modes, capabilities, operations, and rules are Trellis graph data", () => {
      val graph = Bootstrap.f2
      check(Bootstrap.f2ModeEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f2CapabilityEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f2OperationEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f2RuleEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f2ModeEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("resource.mode")))
      Bootstrap.f2CapabilityEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("resource.capability")))
      Bootstrap.f2OperationEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("resource.operation")))
      Bootstrap.f2RuleEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("resource.rule")))
      equal(Check.ResourceRules.rules(graph).size, 10)
    }),
    Test("F2 resource relationships are first-class typed graph edges", () => {
      check(Check.validate(Bootstrap.f2).isEmpty)
      val roles = Bootstrap.f2.edges.values.map(_.role).toSet
      check(Set("resource.operation", "resource.mode", "resource.capability").subsetOf(roles))
      equal(Bootstrap.f2.edges.size, 33)
    }),
    Test("F2 structural permissions come from mode graph data", () => {
      val u = Check.ResourceRules.structural(Bootstrap.f2, Mode.Unrestricted)
      val a = Check.ResourceRules.structural(Bootstrap.f2, Mode.Affine)
      val l = Check.ResourceRules.structural(Bootstrap.f2, Mode.Linear)
      equal((u.duplicate, u.discard), ("allow", "allow"))
      equal((a.duplicate, a.discard), ("forbid", "drop"))
      equal((l.duplicate, l.discard), ("forbid", "forbid"))
    })
  )
