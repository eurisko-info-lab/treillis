package trellis

import trellis.TestSupport.*

object ProjectionTest:
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
    })
  )
