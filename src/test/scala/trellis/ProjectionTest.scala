package trellis

import trellis.Core.*
import trellis.Delta.*
import trellis.TestSupport.*

object ProjectionTest:
  private def right[A](value: Either[String, A]): A = value.fold(err => throw new AssertionError(err), identity)

  val tests = Vector(
    Test("SVG carries semantic identifiers", () => {
      val svg = Project.Svg.render(Bootstrap.graph)
      check(svg.content.contains("data-trellis-node"))
      check(svg.marks.nonEmpty)
    }),
    Test("Typst projection is generated from graph", () => {
      val typ = Project.Typst.render(Bootstrap.graph)
      check(typ.content.contains("Trellis graph"))
      check(typ.content.contains("core.move"))
    }),
    Test("F5 rule-driven projections stay in parity with the pre-F5 direct oracle", () => {
      equal(Project.CodeView.render(Bootstrap.f5), Project.Direct.code(Bootstrap.f5))
      equal(Project.Svg.render(Bootstrap.f5), Project.Direct.svg(Bootstrap.f5))
      equal(Project.Typst.render(Bootstrap.f5), Project.Direct.typst(Bootstrap.f5))
    }),
    Test("changing F5 view data changes projection policy without changing the oracle", () => {
      val entity = EntityId("projection.code")
      val original = Bootstrap.f5.entity(entity).getOrElse(throw new AssertionError("missing F5 code view"))
      val altered = original.copy(attrs = original.attrs.updated("show-kind", "false"))
      val changed = right(Delta.applyChange(
        Bootstrap.f5,
        Change(Set.empty, Vector(Op.ReplaceEntity(entity, altered)), "alter F5 projection policy for test")
      ))

      val driven = Project.CodeView.render(changed)
      val oracle = Project.Direct.code(changed)
      check(driven.content != oracle.content)
      check(!driven.content.linesIterator.next().contains(" = "))
      check(oracle.content.linesIterator.next().contains(" = "))
    }),
    Test("specialized SVG views are graph-filtered semantic projections", () => {
      val machine = Project.MachineSvg.render(Bootstrap.f5)
      check(machine.content.contains("machine.control"))
      check(!machine.content.contains(">core.move<"))
      check(machine.content.contains("data-trellis-node"))
      check(machine.marks.nonEmpty)

      val process = Project.ProcessSvg.render(Bootstrap.f5)
      check(process.content.contains("process.send"))
      check(!process.content.contains(">machine.control<"))
    })
  )
