package trellis

import trellis.Core.*
import trellis.Navigate.Selection

/** SVG, Typst, and code-like views are projections, never canonical source. */
object Project:
  final case class SemanticMark(renderedId: String, selection: Selection)
  final case class Rendered(content: String, marks: Vector[SemanticMark])

  trait Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered

  object CodeView extends Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered =
      val rows = orderedNodes(graph).map { case (id, node, names) =>
        val name = names.headOption.getOrElse("#" + id.value.take(8))
        val ins = node.ports.filter(_.direction == Direction.In).map(p => s"${p.name}: ${showTy(p.ty)}").mkString(", ")
        val outs = node.ports.filter(_.direction == Direction.Out).map(p => s"${p.name}: ${showTy(p.ty)}").mkString(", ")
        s"$name = ${node.kind}($ins) -> ($outs)"
      }
      val marks = graph.entities.toVector.sortBy(_._1.value).map { case (e, _) => SemanticMark(e.value, Selection.Entity(e)) }
      Rendered(rows.mkString("\n"), marks)

  object Svg extends Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered =
      val ordered = orderedNodes(graph)
      val pos = ordered.zipWithIndex.map { case ((id, _, _), i) => id -> (40, 40 + i * 90) }.toMap
      val edges = graph.edges.toVector.sortBy(_._1.value).flatMap { case (eid, e) =>
        for
          (x1, y1) <- pos.get(e.from.node)
          (x2, y2) <- pos.get(e.to.node)
        yield s"<path id=\"edge-${eid.value}\" data-trellis-edge=\"${eid.value}\" d=\"M ${x1 + 260} ${y1 + 25} L $x2 ${y2 + 25}\" stroke=\"currentColor\" fill=\"none\"/>"
      }
      val nodes = ordered.map { case (id, node, names) =>
        val (x, y) = pos(id)
        val label = xmlEscape(names.headOption.getOrElse(node.kind))
        s"""<g id="node-${id.value}" data-trellis-node="${id.value}" data-kind="${xmlEscape(node.kind)}">
           |  <rect x="$x" y="$y" width="260" height="50" rx="8" fill="none" stroke="currentColor"/>
           |  <text x="${x + 12}" y="${y + 30}" font-family="monospace" font-size="14">$label</text>
           |</g>""".stripMargin
      }
      val height = math.max(120, ordered.size * 90 + 40)
      val body = (edges ++ nodes).mkString("\n")
      val svg = s"""<svg xmlns="http://www.w3.org/2000/svg" width="640" height="$height" viewBox="0 0 640 $height">
                   |$body
                   |</svg>""".stripMargin
      val marks = ordered.map { case (id, _, _) => SemanticMark("node-" + id.value, Selection.Node(id)) } ++
        graph.edges.keys.toVector.sortBy(_.value).map(id => SemanticMark("edge-" + id.value, Selection.Edge(id)))
      Rendered(svg, marks)

  object Typst extends Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered =
      val entities = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).map { node =>
          val ports = node.ports.map(p => s"`${p.name}: ${showTy(p.ty)}`").mkString(", ")
          s"== ${entity.value}\n#smallcaps[${node.kind}]\n\n$ports\n"
        }
      }
      val doc = "#set document(title: \"Trellis semantic projection\")\n#set text(font: \"Libertinus Serif\")\n\n= Trellis graph\n\n" + entities.mkString("\n")
      val marks = graph.entities.keys.toVector.sortBy(_.value).map(e => SemanticMark(e.value, Selection.Entity(e)))
      Rendered(doc, marks)

  private def orderedNodes(graph: Graph): Vector[(ContentId, Node, Vector[String])] =
    val entitiesByNode = graph.entities.toVector.groupMap(_._2)(_._1.value)
    graph.nodes.toVector.map { case (id, node) =>
      val names = entitiesByNode.getOrElse(id, Vector.empty).sorted
      (id, node, names)
    }.sortBy { case (id, _, names) => (names.headOption.getOrElse("~"), id.value) }

  private def xmlEscape(s: String): String =
    s.flatMap {
      case '&' => "&amp;"
      case '<' => "&lt;"
      case '>' => "&gt;"
      case '"' => "&quot;"
      case '\'' => "&apos;"
      case c => c.toString
    }

  def showTy(t: Ty): String = t match
    case Ty.Atom(name) => name
    case Ty.Tuple(items) => items.map(showTy).mkString("(", ", ", ")")
    case Ty.Cap(kind, mode, inner, state) =>
      val st = state.fold("")(s => s"@$s")
      s"${kind.toString}[${mode.toString}]<${showTy(inner)}>$st"
