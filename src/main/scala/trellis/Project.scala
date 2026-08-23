package trellis

import trellis.Core.*
import trellis.Delta.*
import trellis.Navigate.Selection

/** SVG, Typst, and code-like views are projections, never canonical source. */
object Project:
  final case class SemanticMark(renderedId: String, selection: Selection)
  final case class Rendered(content: String, marks: Vector[SemanticMark])
  final case class SynchronizedView(view: EntityId, rendered: Rendered, selectedIds: Vector[String])
  final case class InteractionTarget(renderedId: String, selection: Selection, actions: Vector[String], selected: Boolean)
  final case class InteractiveSvg(rendered: Rendered, targets: Vector[InteractionTarget])
  final case class PreviewAnchor(entity: EntityId, page: Int, renderedId: String)
  final case class PreviewPage(index: Int, entities: Vector[EntityId])
  final case class TypstPreview(rendered: Rendered, pages: Vector[PreviewPage], anchors: Vector[PreviewAnchor], selectedPage: Option[Int])
  final case class ReviewEntry(index: Int, operation: String, subject: String, before: Option[ContentId], after: Option[ContentId], selection: Option[Selection])
  final case class DeltaReview(change: ChangeId, message: String, author: String, footprint: Vector[String], entries: Vector[ReviewEntry], successor: Graph)
  final case class CodeAnchor(entity: EntityId, line: Int)
  final case class CodePreview(enabled: Boolean, rendered: Option[Rendered], anchors: Vector[CodeAnchor], selectedLine: Option[Int])
  final case class LspPosition(line: Int, character: Int)
  final case class LspSymbol(name: String, entity: EntityId, position: LspPosition)
  final case class LspDocument(uri: String, text: String, symbols: Vector[LspSymbol])

  def lspDocument(graph: Graph): Either[String, LspDocument] =
    for
      _ <- lspPolicy(graph)
      preview <- codePreview(graph)
      rendered <- preview.rendered.toRight("LSP requires enabled Code View")
      symbols = preview.anchors.sortBy(anchor => (anchor.line, anchor.entity.value)).map(anchor =>
        LspSymbol(anchor.entity.value, anchor.entity, LspPosition(anchor.line - 1, 0))
      )
    yield LspDocument(s"trellis://graph/${Canon.graphId(graph).value}/code", rendered.content, symbols)

  def lspDefinition(graph: Graph, entity: EntityId): Either[String, LspPosition] =
    lspDocument(graph).flatMap(_.symbols.find(_.entity == entity).map(_.position).toRight(s"unknown LSP symbol ${entity.value}"))

  private def lspPolicy(graph: Graph): Either[String, Unit] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) => graph.nodes.get(id).filter(_.kind == "squeak.lsp-policy").map(entity -> _) }
    candidates match
      case Vector((entity, node)) =>
        val supported = node.attrs.get("document").contains("code-view") && node.attrs.get("positions").contains("zero-based-utf16") && node.attrs.get("symbols").contains("semantic-entities") && node.attrs.get("definition").contains("exact-anchor") && node.attrs.get("failure").contains("strict")
        Either.cond(supported, (), s"unsupported LSP policy ${entity.value}")
      case Vector() => Left("missing Squeak LSP policy")
      case _ => Left("multiple Squeak LSP policies")

  def codePreview(graph: Graph, selection: Option[Selection] = None): Either[String, CodePreview] =
    for
      policy <- codeViewPolicy(graph)
      preview <-
        if !policy._1 then Right(CodePreview(false, None, Vector.empty, None))
        else
          for
            spec <- ProjectionRules.view(graph, EntityId("projection.code"))
            rendered <- renderView(graph, EntityId("projection.code"), selection)
            ordered = filteredNodes(graph, spec)
            anchors = ordered.zipWithIndex.flatMap { case ((_, _, names), index) => names.map(name => CodeAnchor(EntityId(name), index + 1)) }
            selectedIds <- selection match
              case None => Right(Vector.empty[String])
              case Some(value) => synchronizeSelection(graph, value).map(_.find(_.view == EntityId("projection.code")).toVector.flatMap(_.selectedIds))
            selectedLine = anchors.find(anchor => selectedIds.contains(anchor.entity.value)).map(_.line)
            _ <- if selection.nonEmpty && policy._2 == "reveal" && selectedLine.isEmpty then Left("selected semantic item has no Code View line") else Right(())
          yield CodePreview(true, Some(rendered), anchors, selectedLine)
    yield preview

  private def codeViewPolicy(graph: Graph): Either[String, (Boolean, String)] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) => graph.nodes.get(id).filter(_.kind == "squeak.code-view-policy").map(entity -> _) }
    candidates match
      case Vector((entity, node)) =>
        for
          enabled <- node.attrs.get("enabled").flatMap(_.toBooleanOption).toRight(s"${entity.value} lacks boolean enabled")
          lines <- node.attrs.get("lines").toRight(s"${entity.value} lacks lines")
          selection <- node.attrs.get("selection").toRight(s"${entity.value} lacks selection")
          failure <- node.attrs.get("failure").toRight(s"${entity.value} lacks failure")
          _ <- if lines == "one-based-semantic" && selection == "reveal" && failure == "strict" then Right(()) else Left(s"unsupported Code View policy ${entity.value}")
        yield enabled -> selection
      case Vector() => Left("missing Squeak Code View policy")
      case _ => Left("multiple Squeak Code View policies")

  def reviewDelta(graph: Graph, change: Change): Either[String, DeltaReview] =
    for
      _ <- reviewPolicy(graph)
      successor <- Delta.applyChange(graph, change)
      entries <- sequence(change.operations.zipWithIndex.map { case (operation, index) => reviewEntry(graph, successor, operation, index) })
    yield DeltaReview(Change.id(change), change.message, change.author, Delta.footprint(change).toVector.sorted, entries, successor)

  private def reviewPolicy(graph: Graph): Either[String, Unit] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) => graph.nodes.get(id).filter(_.kind == "squeak.delta-review-policy").map(entity -> _) }
    candidates match
      case Vector((entity, node)) =>
        val supported = node.attrs.get("order").contains("operation-order") && node.attrs.get("snapshots").contains("content-id") && node.attrs.get("impacts").contains("semantic-selection") && node.attrs.get("failure").contains("strict")
        Either.cond(supported, (), s"unsupported delta review policy ${entity.value}")
      case Vector() => Left("missing Squeak delta review policy")
      case _ => Left("multiple Squeak delta review policies")

  private def reviewEntry(beforeGraph: Graph, afterGraph: Graph, operation: Op, index: Int): Either[String, ReviewEntry] = operation match
    case Op.AddNode(node) =>
      val id = Canon.nodeId(node)
      Right(ReviewEntry(index, "add-node", id.value, None, Some(id), Some(Selection.Node(id))))
    case Op.BindEntity(entity, node) =>
      Right(ReviewEntry(index, "bind-entity", entity.value, beforeGraph.entities.get(entity), Some(node), Some(Selection.Entity(entity))))
    case Op.ReplaceEntity(entity, node) =>
      val after = Canon.nodeId(node)
      beforeGraph.entities.get(entity).toRight(s"review replacement names unknown entity ${entity.value}").map(old => ReviewEntry(index, "replace-entity", entity.value, Some(old), Some(after), Some(Selection.Entity(entity))))
    case Op.RemoveEntity(entity) =>
      beforeGraph.entities.get(entity).toRight(s"review removal names unknown entity ${entity.value}").map(old => ReviewEntry(index, "remove-entity", entity.value, Some(old), None, Some(Selection.Entity(entity))))
    case Op.Connect(edge) =>
      val id = Canon.edgeId(edge)
      Right(ReviewEntry(index, "connect", id.value, None, Some(id), Some(Selection.Edge(id))))
    case Op.Disconnect(edge) =>
      Either.cond(beforeGraph.edges.contains(edge), ReviewEntry(index, "disconnect", edge.value, Some(edge), None, Some(Selection.Edge(edge))), s"review disconnect names unknown edge ${edge.value}")
    case Op.AddRoot(name, node) => Right(ReviewEntry(index, "add-root", name, beforeGraph.roots.get(name), Some(node), None))
    case Op.RemoveRoot(name) => beforeGraph.roots.get(name).toRight(s"review removal names unknown root $name").map(old => ReviewEntry(index, "remove-root", name, Some(old), None, None))
    case Op.RefineHole(entity, node) =>
      val after = Canon.nodeId(node)
      beforeGraph.entities.get(entity).toRight(s"review refinement names unknown entity ${entity.value}").map(old => ReviewEntry(index, "refine-hole", entity.value, Some(old), Some(after), Some(Selection.Entity(entity))))

  def typstPreview(graph: Graph, selection: Option[Selection] = None): Either[String, TypstPreview] =
    for
      policy <- previewPolicy(graph)
      rendered <- renderView(graph, EntityId("projection.typst"), selection)
      entities = rendered.marks.collect { case SemanticMark(renderedId, Selection.Entity(entity)) => entity -> renderedId }
      pages = entities.grouped(policy._1).zipWithIndex.map { case (items, index) => PreviewPage(index + 1, items.map(_._1)) }.toVector
      anchors = pages.flatMap(page => page.entities.map(entity => PreviewAnchor(entity, page.index, entity.value)))
      selectedIds <- selection match
        case None => Right(Vector.empty[String])
        case Some(value) => synchronizeSelection(graph, value).map(_.find(_.view == EntityId("projection.typst")).toVector.flatMap(_.selectedIds))
      selectedPage = anchors.find(anchor => selectedIds.contains(anchor.renderedId)).map(_.page)
      _ <- if selection.nonEmpty && policy._2 == "reveal" && selectedPage.isEmpty then Left("selected semantic item has no Typst preview page") else Right(())
    yield TypstPreview(rendered, pages, anchors, selectedPage)

  private def previewPolicy(graph: Graph): Either[String, (Int, String)] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) => graph.nodes.get(id).filter(_.kind == "squeak.typst-preview-policy").map(entity -> _) }
    candidates match
      case Vector((entity, node)) =>
        for
          pageSize <- node.attrs.get("entities-per-page").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer entities-per-page")
          numbering <- node.attrs.get("numbering").toRight(s"${entity.value} lacks numbering")
          selection <- node.attrs.get("selection").toRight(s"${entity.value} lacks selection")
          failure <- node.attrs.get("failure").toRight(s"${entity.value} lacks failure")
          _ <- if pageSize > 0 && pageSize <= 256 && numbering == "one-based" && selection == "reveal" && failure == "strict" then Right(()) else Left(s"unsupported Typst preview policy ${entity.value}")
        yield pageSize -> selection
      case Vector() => Left("missing Squeak Typst preview policy")
      case _ => Left("multiple Squeak Typst preview policies")

  def interactiveSvg(graph: Graph, selection: Selection, view: EntityId = EntityId("projection.svg")): Either[String, InteractiveSvg] =
    for
      policy <- interactionPolicy(graph)
      synchronized <- synchronizeSelection(graph, selection)
      selected = synchronized.find(_.view == view).map(_.selectedIds.toSet).getOrElse(Set.empty)
      rendered <- renderView(graph, view, Some(selection))
      targets = rendered.marks.sortBy(_.renderedId).map(mark => InteractionTarget(mark.renderedId, mark.selection, policy, selected.contains(mark.renderedId)))
      _ <- if targets.nonEmpty then Right(()) else Left(s"interactive SVG view ${view.value} has no semantic targets")
    yield InteractiveSvg(rendered, targets)

  private def interactionPolicy(graph: Graph): Either[String, Vector[String]] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) => graph.nodes.get(id).filter(_.kind == "squeak.svg-interaction-policy").map(entity -> _) }
    candidates match
      case Vector((entity, node)) =>
        for
          actions <- node.attrs.get("actions").toRight(s"${entity.value} lacks actions")
          focus <- node.attrs.get("focus").toRight(s"${entity.value} lacks focus")
          selected <- node.attrs.get("selected-state").toRight(s"${entity.value} lacks selected-state")
          failure <- node.attrs.get("failure").toRight(s"${entity.value} lacks failure")
          decoded = actions.split(";", -1).toVector.filter(_.nonEmpty)
          _ <- if decoded == Vector("select", "activate") && focus == "semantic-targets" && selected == "aria-selected" && failure == "strict" then Right(()) else Left(s"unsupported SVG interaction policy ${entity.value}")
        yield decoded
      case Vector() => Left("missing Squeak SVG interaction policy")
      case _ => Left("multiple Squeak SVG interaction policies")

  def synchronizeSelection(graph: Graph, selection: Selection): Either[String, Vector[SynchronizedView]] =
    for
      policy <- selectionPolicy(graph)
      keys <- equivalentSelectionKeys(graph, selection)
      views <- sequence(policy._1.map { view =>
        renderView(graph, view, None).map { rendered =>
          val selected = rendered.marks.filter(mark => keys.contains(Navigate.selectionKey(mark.selection))).map(_.renderedId).distinct.sorted
          SynchronizedView(view, rendered, selected)
        }
      })
      _ <- if policy._2 == "require-visible" && views.exists(_.selectedIds.isEmpty) then Left("semantic selection is not visible in every synchronized view") else Right(())
    yield views

  private def selectionPolicy(graph: Graph): Either[String, (Vector[EntityId], String)] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) => graph.nodes.get(id).filter(_.kind == "squeak.selection-policy").map(entity -> _) }
    candidates match
      case Vector((entity, node)) =>
        for
          views <- node.attrs.get("views").toRight(s"${entity.value} lacks views")
          visibility <- node.attrs.get("visibility").toRight(s"${entity.value} lacks visibility")
          identity <- node.attrs.get("identity").toRight(s"${entity.value} lacks identity")
          failure <- node.attrs.get("failure").toRight(s"${entity.value} lacks failure")
          decoded = views.split(";", -1).toVector.filter(_.nonEmpty).map(EntityId.apply)
          _ <- if decoded.nonEmpty && identity == "entity-node-equivalence" && Set("allow-hidden", "require-visible").contains(visibility) && failure == "strict" then Right(()) else Left(s"unsupported selection policy ${entity.value}")
          _ <- sequence(decoded.map(view => ProjectionRules.view(graph, view).map(_ => ()))).map(_ => ())
        yield decoded -> visibility
      case Vector() => Left("missing Squeak selection policy")
      case _ => Left("multiple Squeak selection policies")

  private def equivalentSelectionKeys(graph: Graph, selection: Selection): Either[String, Set[String]] = selection match
    case Selection.Entity(entity) => graph.entities.get(entity).toRight(s"unknown entity ${entity.value}").map(node => Set(Navigate.selectionKey(selection), Navigate.selectionKey(Selection.Node(node))))
    case Selection.Node(node) => graph.nodes.get(node).toRight(s"unknown node ${node.value}").map(_ => Set(Navigate.selectionKey(selection)) ++ graph.entities.collect { case (entity, `node`) => Navigate.selectionKey(Selection.Entity(entity)) })
    case Selection.Edge(edge) => Either.cond(graph.edges.contains(edge), Set(Navigate.selectionKey(selection)), s"unknown edge ${edge.value}")
    case other => Left(s"unsupported synchronized selection ${Navigate.selectionKey(other)}")

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
          requirePrimitive(graph, view, "node", "svg-node").flatMap(_ => renderSvg(graph, spec, selection))
        case "typst" =>
          requirePrimitive(graph, view, "entity", "typst-entity").map(_ => renderTypst(graph, spec))
        case other => Left(s"unsupported projection format $other for ${view.value}")
    }

  private def sequence[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty))((acc, value) => acc.flatMap(items => value.map(items :+ _)))

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

  private def renderSvg(graph: Graph, spec: ViewSpec, selection: Option[Selection]): Either[String, Rendered] =
    val selectedKeys = selection.map(equivalentSelectionKeys(graph, _)).getOrElse(Right(Set.empty))
    selectedKeys.map { keys =>
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
      yield
        val selected = keys.contains(Navigate.selectionKey(Selection.Edge(edgeId)))
        val interaction = if selection.nonEmpty then s" tabindex=\"0\" role=\"button\" aria-selected=\"$selected\"" else ""
        s"<path id=\"edge-${edgeId.value}\" data-trellis-edge=\"${edgeId.value}\"$interaction d=\"M ${x1 + nodeWidth} ${y1 + nodeHeight / 2} L $x2 ${y2 + nodeHeight / 2}\" stroke=\"currentColor\" fill=\"none\"/>"
    }
    val nodes = ordered.map { case (id, node, names) =>
      val (nx, ny) = pos(id)
      val label = xmlEscape(names.headOption.getOrElse(node.kind))
      val selected = keys.contains(Navigate.selectionKey(Selection.Node(id)))
      val interaction = if selection.nonEmpty then s" tabindex=\"0\" role=\"button\" aria-selected=\"$selected\"" else ""
      s"""<g id="node-${id.value}" data-trellis-node="${id.value}" data-kind="${xmlEscape(node.kind)}"$interaction>
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
    }

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
