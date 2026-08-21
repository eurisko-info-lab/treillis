package trellis

import trellis.Core.*
import trellis.Navigate.Selection

/** SVG, Typst, and code-like views are projections, never canonical source. */
object Project:
  final case class SemanticMark(renderedId: String, selection: Selection)
  final case class Rendered(content: String, marks: Vector[SemanticMark])

  final case class ViewSpec(entity: EntityId, format: String, attrs: Map[String, String]):
    def text(name: String, default: String): String = attrs.getOrElse(name, default)
    def int(name: String, default: Int): Int = attrs.get(name).flatMap(_.toIntOption).getOrElse(default)
    def bool(name: String, default: Boolean): Boolean = attrs.get(name).flatMap(_.toBooleanOption).getOrElse(default)

  final case class ProjectionRule(
      entity: EntityId,
      view: EntityId,
      subject: String,
      primitive: String,
      anchor: String
  )

  /**
   * F5 projection policy interpreted from graph data.
   *
   * Scala retains only rendering primitives and XML/Typst escaping. Which view
   * exists, its format/layout/filter policy, and which primitive applies to a
   * semantic subject are selected by F5 nodes.
   */
  object ProjectionRules:
    def views(graph: Graph): Vector[ViewSpec] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "projection.view").flatMap { node =>
          node.attrs.get("format").map(format => ViewSpec(entity, format, node.attrs))
        }
      }

    def view(graph: Graph, entity: EntityId): Either[String, ViewSpec] =
      graph.entity(entity) match
        case Some(node) if node.kind == "projection.view" =>
          node.attrs.get("format") match
            case Some(format) => Right(ViewSpec(entity, format, node.attrs))
            case None => Left(s"projection view ${entity.value} has no format")
        case Some(node) => Left(s"entity ${entity.value} is ${node.kind}, not a projection.view")
        case None => Left(s"unknown projection view ${entity.value}")

    def rules(graph: Graph): Vector[ProjectionRule] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "projection.rule").flatMap { node =>
          for
            view <- node.attrs.get("view")
            subject <- node.attrs.get("subject")
            primitive <- node.attrs.get("primitive")
            anchor <- node.attrs.get("anchor")
          yield ProjectionRule(entity, EntityId(view), subject, primitive, anchor)
        }
      }

    def forView(graph: Graph, view: EntityId): Vector[ProjectionRule] =
      rules(graph).filter(_.view == view)

    def hasPrimitive(graph: Graph, view: EntityId, subject: String, primitive: String): Boolean =
      forView(graph, view).exists(rule => rule.subject == subject && rule.primitive == primitive)

  trait Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered

  object CodeView extends Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered =
      orThrow(renderView(graph, EntityId("projection.code"), selection))

  object Svg extends Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered =
      orThrow(renderView(graph, EntityId("projection.svg"), selection))

  object OwnershipSvg extends Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered =
      orThrow(renderView(graph, EntityId("projection.svg.ownership"), selection))

  object ProcessSvg extends Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered =
      orThrow(renderView(graph, EntityId("projection.svg.process"), selection))

  object MachineSvg extends Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered =
      orThrow(renderView(graph, EntityId("projection.svg.machine"), selection))

  object Typst extends Renderer:
    def render(graph: Graph, selection: Option[Selection] = None): Rendered =
      orThrow(renderView(graph, EntityId("projection.typst"), selection))

  /** Render any graph-defined F5 view. */
  def renderView(graph: Graph, view: EntityId, selection: Option[Selection] = None): Either[String, Rendered] =
    ProjectionRules.view(graph, view).flatMap { spec =>
      spec.format match
        case "code" =>
          requirePrimitive(graph, view, "node", "code-node").map(_ => renderCode(graph, spec))
        case "svg" =>
          requirePrimitive(graph, view, "node", "svg-node").map(_ => renderSvg(graph, spec))
        case "typst" =>
          requirePrimitive(graph, view, "entity", "typst-entity").map(_ => renderTypst(graph, spec))
        case other => Left(s"unsupported projection format $other for ${view.value}")
    }

  private def requirePrimitive(graph: Graph, view: EntityId, subject: String, primitive: String): Either[String, Unit] =
    if ProjectionRules.hasPrimitive(graph, view, subject, primitive) then Right(())
    else Left(s"projection view ${view.value} has no $subject/$primitive render rule")

  private def renderCode(graph: Graph, spec: ViewSpec): Rendered =
    val ordered = filteredNodes(graph, spec)
    val showKind = spec.bool("show-kind", true)
    val rows = ordered.map { case (id, node, names) =>
      val name = names.headOption.getOrElse("#" + id.value.take(8))
      val ins = node.ports.filter(_.direction == Direction.In).map(p => s"${p.name}: ${showTy(p.ty)}").mkString(", ")
      val outs = node.ports.filter(_.direction == Direction.Out).map(p => s"${p.name}: ${showTy(p.ty)}").mkString(", ")
      if showKind then s"$name = ${node.kind}($ins) -> ($outs)"
      else s"$name($ins) -> ($outs)"
    }
    val included = ordered.map(_._1).toSet
    val marks = graph.entities.toVector.sortBy(_._1.value).collect {
      case (entity, nodeId) if included(nodeId) => SemanticMark(entity.value, Selection.Entity(entity))
    }
    Rendered(rows.mkString("\n"), marks)

  private def renderSvg(graph: Graph, spec: ViewSpec): Rendered =
    val ordered = filteredNodes(graph, spec)
    val included = ordered.map(_._1).toSet
    val x = spec.int("x", 40)
    val y0 = spec.int("y", 40)
    val width = spec.int("width", 640)
    val nodeWidth = spec.int("node-width", 260)
    val nodeHeight = spec.int("node-height", 50)
    val rowHeight = spec.int("row-height", 90)
    val pos = ordered.zipWithIndex.map { case ((id, _, _), i) => id -> (x, y0 + i * rowHeight) }.toMap
    val edgeRule = ProjectionRules.hasPrimitive(graph, spec.entity, "edge", "svg-edge")
    val visibleEdges =
      if edgeRule then graph.edges.toVector.sortBy(_._1.value).filter { case (_, edge) => included(edge.from.node) && included(edge.to.node) }
      else Vector.empty
    val edges = visibleEdges.flatMap { case (edgeId, edge) =>
      for
        (x1, y1) <- pos.get(edge.from.node)
        (x2, y2) <- pos.get(edge.to.node)
      yield s"<path id=\"edge-${edgeId.value}\" data-trellis-edge=\"${edgeId.value}\" d=\"M ${x1 + nodeWidth} ${y1 + nodeHeight / 2} L $x2 ${y2 + nodeHeight / 2}\" stroke=\"currentColor\" fill=\"none\"/>"
    }
    val nodes = ordered.map { case (id, node, names) =>
      val (nx, ny) = pos(id)
      val label = xmlEscape(names.headOption.getOrElse(node.kind))
      s"""<g id="node-${id.value}" data-trellis-node="${id.value}" data-kind="${xmlEscape(node.kind)}">
         |  <rect x="$nx" y="$ny" width="$nodeWidth" height="$nodeHeight" rx="8" fill="none" stroke="currentColor"/>
         |  <text x="${nx + 12}" y="${ny + 30}" font-family="monospace" font-size="14">$label</text>
         |</g>""".stripMargin
    }
    val height = math.max(120, ordered.size * rowHeight + y0)
    val body = (edges ++ nodes).mkString("\n")
    val svg = s"""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">
                 |$body
                 |</svg>""".stripMargin
    val marks = ordered.map { case (id, _, _) => SemanticMark("node-" + id.value, Selection.Node(id)) } ++
      visibleEdges.map { case (id, _) => SemanticMark("edge-" + id.value, Selection.Edge(id)) }
    Rendered(svg, marks)

  private def renderTypst(graph: Graph, spec: ViewSpec): Rendered =
    val included = filteredNodes(graph, spec).map(_._1).toSet
    val entities = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
      graph.nodes.get(nodeId).filter(_ => included(nodeId)).map { node =>
        val ports = node.ports.map(p => s"`${p.name}: ${showTy(p.ty)}`").mkString(", ")
        s"== ${entity.value}\n#smallcaps[${node.kind}]\n\n$ports\n"
      }
    }
    val title = spec.text("title", "Trellis semantic projection")
    val font = spec.text("font", "Libertinus Serif")
    val heading = spec.text("heading", "Trellis graph")
    val doc = s"#set document(title: \"$title\")\n#set text(font: \"$font\")\n\n= $heading\n\n" + entities.mkString("\n")
    val marks = graph.entities.toVector.sortBy(_._1.value).collect {
      case (entity, nodeId) if included(nodeId) => SemanticMark(entity.value, Selection.Entity(entity))
    }
    Rendered(doc, marks)

  private def filteredNodes(graph: Graph, spec: ViewSpec): Vector[(ContentId, Node, Vector[String])] =
    val filter = spec.text("node-filter", "*")
    orderedNodes(graph).filter { case (_, node, names) => matchesFilter(node, names, filter) }

  private def matchesFilter(node: Node, names: Vector[String], filter: String): Boolean =
    filter == "*" || filter.split(';').iterator.map(_.trim).filter(_.nonEmpty).exists {
      case clause if clause.startsWith("entity-prefix:") =>
        val prefix = clause.stripPrefix("entity-prefix:")
        names.exists(_.startsWith(prefix))
      case clause if clause.startsWith("kind-prefix:") =>
        node.kind.startsWith(clause.stripPrefix("kind-prefix:"))
      case _ => false
    }

  private def orderedNodes(graph: Graph): Vector[(ContentId, Node, Vector[String])] =
    val entitiesByNode = graph.entities.toVector.groupMap(_._2)(_._1.value)
    graph.nodes.toVector.map { case (id, node) =>
      val names = entitiesByNode.getOrElse(id, Vector.empty).sorted
      (id, node, names)
    }.sortBy { case (id, _, names) => (names.headOption.getOrElse("~"), id.value) }

  /** Pre-F5 projection policy retained temporarily as a differential oracle. */
  object Direct:
    def code(graph: Graph): Rendered =
      val rows = orderedNodes(graph).map { case (id, node, names) =>
        val name = names.headOption.getOrElse("#" + id.value.take(8))
        val ins = node.ports.filter(_.direction == Direction.In).map(p => s"${p.name}: ${showTy(p.ty)}").mkString(", ")
        val outs = node.ports.filter(_.direction == Direction.Out).map(p => s"${p.name}: ${showTy(p.ty)}").mkString(", ")
        s"$name = ${node.kind}($ins) -> ($outs)"
      }
      val marks = graph.entities.toVector.sortBy(_._1.value).map { case (entity, _) => SemanticMark(entity.value, Selection.Entity(entity)) }
      Rendered(rows.mkString("\n"), marks)

    def svg(graph: Graph): Rendered =
      val ordered = orderedNodes(graph)
      val pos = ordered.zipWithIndex.map { case ((id, _, _), i) => id -> (40, 40 + i * 90) }.toMap
      val edges = graph.edges.toVector.sortBy(_._1.value).flatMap { case (edgeId, edge) =>
        for
          (x1, y1) <- pos.get(edge.from.node)
          (x2, y2) <- pos.get(edge.to.node)
        yield s"<path id=\"edge-${edgeId.value}\" data-trellis-edge=\"${edgeId.value}\" d=\"M ${x1 + 260} ${y1 + 25} L $x2 ${y2 + 25}\" stroke=\"currentColor\" fill=\"none\"/>"
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

    def typst(graph: Graph): Rendered =
      val entities = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).map { node =>
          val ports = node.ports.map(p => s"`${p.name}: ${showTy(p.ty)}`").mkString(", ")
          s"== ${entity.value}\n#smallcaps[${node.kind}]\n\n$ports\n"
        }
      }
      val doc = "#set document(title: \"Trellis semantic projection\")\n#set text(font: \"Libertinus Serif\")\n\n= Trellis graph\n\n" + entities.mkString("\n")
      val marks = graph.entities.keys.toVector.sortBy(_.value).map(entity => SemanticMark(entity.value, Selection.Entity(entity)))
      Rendered(doc, marks)

  private def orThrow(value: Either[String, Rendered]): Rendered =
    value.fold(error => throw new IllegalStateException(error), identity)

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
