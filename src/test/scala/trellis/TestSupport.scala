package trellis

import java.util.Arrays
import trellis.Core.*
import trellis.Delta.*
import trellis.storage.ProductCatalog

object TestSupport:
  final case class Test(name: String, run: () => Unit)

  def check(condition: Boolean, clue: => String = "assertion failed"): Unit =
    if !condition then throw new AssertionError(clue)

  def equal[A](obtained: A, expected: A): Unit =
    if obtained != expected then throw new AssertionError(s"obtained: $obtained\nexpected: $expected")

  final case class FeatureStage(entity: EntityId):
    lazy val product = ProductCatalog.introducing(entity)
    lazy val before: Graph = ProductCatalog.predecessor(product)
    lazy val graph: Graph = product.graph
    def verifyReplay(): Unit =
      check(product.postActions.nonEmpty, s"${entity.value} has no declared post-actions")
      equal(product.change.dependencies, Set(ProductCatalog.predecessorChangeId(product)))
      val replayed = Delta.applyChange(before, product.change).fold(error => throw new AssertionError(error), identity)
      equal(Canon.graphId(replayed), product.root)
      check(Arrays.equals(Canon.encodeGraphBytes(replayed), Canon.encodeGraphBytes(graph)))
      check(!before.entities.contains(entity))
      check(graph.entities.contains(entity))
