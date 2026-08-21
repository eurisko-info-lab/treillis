package trellis

import trellis.Core.*
import trellis.TestSupport.*

object CheckTest:
  private val text = Ty.Atom("Text")
  private val ownText = Ty.Cap(Capability.Own, Mode.Affine, text, None)

  val tests = Vector(
    Test("affine output cannot fan out implicitly", () => {
      val source = Node("source", Vector(Port("out", Direction.Out, ownText)))
      val sink = Node("sink", Vector(Port("in", Direction.In, ownText)))
      val (g1, sid) = Canon.addNode(Graph(), source)
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
      val (g1, sid) = Canon.addNode(Graph(), source)
      val (g2, a) = Canon.addNode(g1, sink.copy(attrs = Map("name" -> "a")))
      val (g3, b) = Canon.addNode(g2, sink.copy(attrs = Map("name" -> "b")))
      val (g4, _) = Canon.addEdge(g3, Edge(PortRef(sid, "out"), PortRef(a, "in")))
      val (g5, _) = Canon.addEdge(g4, Edge(PortRef(sid, "out"), PortRef(b, "in")))
      equal(Check.validate(g5), Vector.empty)
    })
  )
