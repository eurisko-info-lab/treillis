package trellis

import trellis.Core.*
import trellis.TestSupport.*

object CanonTest:
  val tests = Vector(
    Test("canonical maps ignore insertion order", () => {
      val n1 = Node("x", attrs = Map("b" -> "2", "a" -> "1"))
      val n2 = Node("x", attrs = Map("a" -> "1", "b" -> "2"))
      equal(Canon.nodeId(n1), Canon.nodeId(n2))
    }),
    Test("bootstrap graph hash is stable within process", () => {
      equal(Canon.graphId(Bootstrap.graph), Canon.graphId(Bootstrap.graph.copy()))
    })
  )
