package trellis

import trellis.Core.*
import trellis.Delta.*
import trellis.Repo.*
import trellis.TestSupport.*

object RepoTest:
  val tests = Vector(
    Test("independent changes commute semantically", () => {
      val base = Bootstrap.graph
      val a = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.a"), Node("app.function", attrs = Map("name" -> "a")))), "a")
      val b = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.b"), Node("app.function", attrs = Map("name" -> "b")))), "b")
      val (s1, aid) = Store().put(a)
      val (s2, bid) = s1.put(b)
      val branch = Branch(BranchId("t"), base, Set(aid, bid), None)
      val g = materialize(s2, branch).fold(err => throw new AssertionError(err), _.graph)
      check(g.entities.contains(EntityId("app.a")))
      check(g.entities.contains(EntityId("app.b")))
    }),
    Test("concurrent edits to same entity conflict", () => {
      val a = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.x"), Node("a"))), "a")
      val b = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.x"), Node("b"))), "b")
      val (s1, aid) = Store().put(a)
      val (s2, bid) = s1.put(b)
      check(materialize(s2, Branch(BranchId("t"), Bootstrap.graph, Set(aid, bid), None)).isLeft)
    })
  )
