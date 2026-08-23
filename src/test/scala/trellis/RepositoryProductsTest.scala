package trellis

import java.util.Arrays
import trellis.Core.*
import trellis.Delta.*
import trellis.storage.RepositoryProducts.*
import trellis.TestSupport.*

object RepositoryProductsTest:
  private def right[A](value: Either[String, A]): A = value.fold(err => throw new AssertionError(err), identity)

  val tests = Vector(
    Test("DeltaTrellis change ids are canonical across dependency set order", () => {
      val a = ChangeId("a" * 64)
      val b = ChangeId("b" * 64)
      val op = Op.ReplaceEntity(EntityId("app.x"), Node("app.function", attrs = Map("note" -> "a|b:c")))
      val c1 = Change(Set(a, b), Vector(op), "message|with:delimiters", "ai")
      val c2 = Change(Set(b, a), Vector(op), "message|with:delimiters", "ai")
      equal(Delta.encodeChange(c1), Delta.encodeChange(c2))
      equal(Change.id(c1), Change.id(c2))
    }),
    Test("independent changes replay byte-identically in either order", () => {
      val base = Bootstrap.graph
      val a = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.a"), Node("app.function", attrs = Map("name" -> "a")))), "a")
      val b = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.b"), Node("app.function", attrs = Map("name" -> "b")))), "b")

      val ab = right(Delta.applyChange(right(Delta.applyChange(base, a)), b))
      val ba = right(Delta.applyChange(right(Delta.applyChange(base, b)), a))
      check(Arrays.equals(Canon.encodeGraphBytes(ab), Canon.encodeGraphBytes(ba)))

      val (s1, aid) = Store().put(a)
      val (s2, bid) = s1.put(b)
      val branch = Branch(BranchId("t"), base, Set(aid, bid), None)
      val materialized = right(materialize(s2, branch)).graph
      check(Arrays.equals(Canon.encodeGraphBytes(ab), Canon.encodeGraphBytes(materialized)))
    }),
    Test("successors form a predecessor-plus-delta derivation staircase", () => {
      val g0 = Bootstrap.graph
      val d1 = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.one"), Node("app.function", attrs = Map("step" -> "1")))), "step 1")
      val (s1, id1) = Store().put(d1)
      val d2 = Change(Set(id1), Vector(Op.ReplaceEntity(EntityId("app.two"), Node("app.function", attrs = Map("step" -> "2")))), "step 2")
      val (s2, id2) = s1.put(d2)
      val d3 = Change(Set(id2), Vector(Op.ReplaceEntity(EntityId("app.three"), Node("app.function", attrs = Map("step" -> "3")))), "step 3")
      val (store, id3) = s2.put(d3)

      val g1 = right(Delta.applyChange(g0, d1))
      val g2 = right(Delta.applyChange(g1, d2))
      val g3 = right(Delta.applyChange(g2, d3))

      val m1 = right(materialize(store, Branch(BranchId("s1"), g0, Set(id1), None))).graph
      val m2 = right(materialize(store, Branch(BranchId("s2"), g0, Set(id2), None))).graph
      val m3 = right(materialize(store, Branch(BranchId("s3"), g0, Set(id3), None))).graph

      check(Arrays.equals(Canon.encodeGraphBytes(g1), Canon.encodeGraphBytes(m1)))
      check(Arrays.equals(Canon.encodeGraphBytes(g2), Canon.encodeGraphBytes(m2)))
      check(Arrays.equals(Canon.encodeGraphBytes(g3), Canon.encodeGraphBytes(m3)))
    }),
    Test("branch provenance separates upstream basis from local frontier", () => {
      val base = Bootstrap.graph
      val upstreamFrontier = Set(ChangeId("1" * 64))
      val publication = Publication(
        PublicationId("foundation-publication"),
        "trellis/application/default",
        "stable",
        upstreamFrontier,
        Canon.graphId(base),
        "trellis-foundation",
        "signature"
      )
      val branchId = BranchId("local/provenance")
      val branch = right(branchFromPublication(branchId, base, publication))
      check(branch.frontier.isEmpty)
      val initial = provenance(branch).get
      equal(initial.basisRoot, publication.graphRoot)
      equal(initial.basisFrontier, upstreamFrontier)
      check(initial.localFrontier.isEmpty)

      val local = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.local"), Node("app.function"))), "local")
      val store = right(advance(Store().addBranch(branch), branchId, local))
      val updated = store.branches(branchId)
      val p = provenance(updated).get
      equal(p.basisFrontier, upstreamFrontier)
      equal(p.localFrontier, updated.frontier)
      check(updated.frontier.nonEmpty)
      check(right(materialize(store, updated)).graph.entities.contains(EntityId("app.local")))
    }),
    Test("publication basis hash mismatch is rejected", () => {
      val bad = Publication(
        PublicationId("bad"),
        "trellis/application/default",
        "stable",
        Set.empty,
        ContentId("0" * 64),
        "publisher",
        "signature"
      )
      check(branchFromPublication(BranchId("bad"), Bootstrap.graph, bad).isLeft)
    }),
    Test("concurrent edits to same entity conflict", () => {
      val a = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.x"), Node("a"))), "a")
      val b = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.x"), Node("b"))), "b")
      val (s1, aid) = Store().put(a)
      val (s2, bid) = s1.put(b)
      check(materialize(s2, Branch(BranchId("t"), Bootstrap.graph, Set(aid, bid), None)).isLeft)
    })
  )
