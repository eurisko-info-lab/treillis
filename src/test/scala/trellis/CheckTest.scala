package trellis

import trellis.Core.*
import trellis.TestSupport.*

object CheckTest:
  private val text = Ty.Atom("Text")
  private val ownText = Ty.Cap(Capability.Own, Mode.Affine, text, None)

  private def withNode(base: Graph, node: Node): Graph = Canon.addNode(base, node)._1

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
    })
  )
