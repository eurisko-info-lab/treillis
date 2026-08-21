package trellis

import trellis.Core.*
import trellis.TestSupport.*

object CheckTest:
  private val text = Ty.Atom("Text")
  private val ownText = Ty.Cap(Capability.Own, Mode.Affine, text, None)

  private def withNode(base: Graph, node: Node): Graph = Canon.addNode(base, node)._1

  private def bind(base: Graph, entity: String, node: Node): Graph =
    val (next, id) = Canon.addNode(base, node)
    next.copy(entities = next.entities.updated(EntityId(entity), id))

  private def rewrite(
      lhs: String,
      rhs: String,
      mode: String = "any",
      preserve: String = "type;resource;effect;protocol",
      evidence: String = "test-law"
  ): Node =
    Node(
      "equality.rewrite",
      attrs = Map(
        "lhs" -> lhs,
        "rhs" -> rhs,
        "mode" -> mode,
        "preserve" -> preserve,
        "evidence" -> evidence
      )
    )

  private def enode(operator: String, metrics: (String, String)*): Node =
    Node("equality.enode", attrs = Map("operator" -> operator) ++ metrics)

  private def right[A](value: Either[String, A]): A =
    value.fold(error => throw new AssertionError(error), identity)

  val tests = Vector(
    Test("affine output cannot fan out implicitly", () => {
      val source = Node("source", Vector(Port("out", Direction.Out, ownText)))
      val sink = Node("sink", Vector(Port("in", Direction.In, ownText)))
      val (g1, sid) = Canon.addNode(Bootstrap.f2, source)
      val (g2, a) = Canon.addNode(g1, sink.copy(attrs = Map("name" -> "a")))
      val (g3, b) = Canon.addNode(g2, sink.copy(attrs = Map("name" -> "b")))
      val (g4, _) = Canon.addEdge(g3, Edge(PortRef(sid, "out"), PortRef(a, "in")))
      val (g5, _) = Canon.addEdge(g4, Edge(PortRef(sid, "out"), PortRef(b, "in")))
      check(Check.validate(g5).exists(_.contains("illegal duplication")))
    }),
    Test("unrestricted output may fan out", () => {
      val int = Ty.Atom("Int")
      val source = Node("source", Vector(Port("out", Direction.Out, int)))
      val sink = Node("sink", Vector(Port("in", Direction.In, int)))
      val (g1, sid) = Canon.addNode(Bootstrap.f2, source)
      val (g2, a) = Canon.addNode(g1, sink.copy(attrs = Map("name" -> "a")))
      val (g3, b) = Canon.addNode(g2, sink.copy(attrs = Map("name" -> "b")))
      val (g4, _) = Canon.addEdge(g3, Edge(PortRef(sid, "out"), PortRef(a, "in")))
      val (g5, _) = Canon.addEdge(g4, Edge(PortRef(sid, "out"), PortRef(b, "in")))
      equal(Check.validate(g5), Vector.empty)
    }),
    Test("replicate legality is interpreted from F2 rule data", () => {
      val unrestricted = Ty.Cap(Capability.Pure, Mode.Unrestricted, text, None)
      val good = Node("core.replicate", Vector(Port("in", Direction.In, unrestricted)))
      val bad = Node("core.replicate", Vector(Port("in", Direction.In, ownText)))
      check(Check.validate(withNode(Bootstrap.f2, good)).isEmpty)
      check(Check.validate(withNode(Bootstrap.f2, bad)).exists(_.contains("violates F2 rules")))
    }),
    Test("affine erase is admitted as graph-defined lower-to-drop", () => {
      val erase = Node("core.erase", Vector(Port("in", Direction.In, ownText)))
      equal(Check.ResourceRules.decision(Bootstrap.f2, erase), Some(Check.ResourceDisposition.LowerDrop))
      check(Check.validate(withNode(Bootstrap.f2, erase)).isEmpty)
    }),
    Test("shared borrow shape is interpreted from F2 rule data", () => {
      val readText = Ty.Cap(Capability.Read, Mode.Unrestricted, text, Some("l"))
      val writeText = Ty.Cap(Capability.Write, Mode.Affine, text, Some("l"))
      val good = Node(
        "core.borrow.shared",
        Vector(
          Port("owner", Direction.In, ownText),
          Port("loan", Direction.Out, readText)
        )
      )
      val bad = good.copy(ports = Vector(Port("owner", Direction.In, ownText), Port("loan", Direction.Out, writeText)))
      check(Check.validate(withNode(Bootstrap.f2, good)).isEmpty)
      check(Check.validate(withNode(Bootstrap.f2, bad)).exists(_.contains("loan.capability expected read")))
    }),
    Test("equality saturation is transitive and recursively congruent from F6 rule data", () => {
      val g1 = bind(Bootstrap.f6, "test.eq.a-b", rewrite("a", "b"))
      val graph = bind(g1, "test.eq.b-c", rewrite("b", "c"))
      val seed = Check.EqualityTerm(
        "wrap",
        Mode.Affine,
        Vector(Check.EqualityTerm("a", Mode.Affine))
      )
      val eclass = right(Check.EqualityRules.saturate(graph, seed))
      check(eclass.saturated)
      check(eclass.terms.contains(Check.EqualityTerm("wrap", Mode.Affine, Vector(Check.EqualityTerm("b", Mode.Affine)))))
      check(eclass.terms.contains(Check.EqualityTerm("wrap", Mode.Affine, Vector(Check.EqualityTerm("c", Mode.Affine)))))
    }),
    Test("F6 resource preservation policy gates otherwise well-formed equality rewrites", () => {
      val unsafe = rewrite("owned-a", "owned-b", preserve = "type;effect;protocol")
      val graph = bind(Bootstrap.f6, "test.eq.unsafe", unsafe)
      val seed = Check.EqualityTerm("owned-a", Mode.Affine)
      check(!right(Check.EqualityRules.equivalent(graph, seed, Check.EqualityTerm("owned-b", Mode.Affine))))

      val policyEntity = EntityId("equality.policy.rewrite")
      val policy = graph.entity(policyEntity).getOrElse(throw new AssertionError("missing equality policy"))
      val relaxed = policy.copy(attrs = policy.attrs.updated("required-preserve", "type;effect;protocol"))
      val relaxedGraph = bind(graph, policyEntity.value, relaxed)
      check(right(Check.EqualityRules.equivalent(relaxedGraph, seed, Check.EqualityTerm("owned-b", Mode.Affine))))
    }),
    Test("equality rewrite mode guards come from graph data", () => {
      val graph = bind(Bootstrap.f6, "test.eq.unrestricted", rewrite("copy-a", "copy-b", mode = "unrestricted"))
      val unrestricted = Check.EqualityTerm("copy-a", Mode.Unrestricted)
      val affine = Check.EqualityTerm("copy-a", Mode.Affine)
      check(right(Check.EqualityRules.equivalent(graph, unrestricted, Check.EqualityTerm("copy-b", Mode.Unrestricted))))
      check(!right(Check.EqualityRules.equivalent(graph, affine, Check.EqualityTerm("copy-b", Mode.Affine))))
    }),
    Test("equality extraction uses graph-defined multi-objective cost weights", () => {
      val g1 = bind(Bootstrap.f6, "test.eq.fast", enode("fast", "nodes" -> "1", "allocations" -> "5"))
      val graph = bind(g1, "test.eq.small", enode("small", "nodes" -> "3", "allocations" -> "0"))
      val alternatives = Check.EClass(
        Set(Check.EqualityTerm("fast"), Check.EqualityTerm("small")),
        saturated = true,
        iterations = 0
      )
      equal(right(Check.EqualityRules.extract(graph, alternatives)).operator, "small")

      val costEntity = EntityId("equality.cost-model.default")
      val cost = graph.entity(costEntity).getOrElse(throw new AssertionError("missing equality cost model"))
      val altered = cost.copy(attrs = cost.attrs.updated("allocations", "0"))
      val alteredGraph = bind(graph, costEntity.value, altered)
      equal(right(Check.EqualityRules.extract(alteredGraph, alternatives)).operator, "fast")
    }),
    Test("F6 proof policy rejects equality rewrites without evidence", () => {
      val noEvidence = bind(Bootstrap.f6, "test.eq.no-evidence", rewrite("p", "q", evidence = ""))
      val seed = Check.EqualityTerm("p")
      check(!right(Check.EqualityRules.equivalent(noEvidence, seed, Check.EqualityTerm("q"))))

      val withEvidence = bind(Bootstrap.f6, "test.eq.no-evidence", rewrite("p", "q", evidence = "proof:test"))
      check(right(Check.EqualityRules.equivalent(withEvidence, seed, Check.EqualityTerm("q"))))
    })
  )
