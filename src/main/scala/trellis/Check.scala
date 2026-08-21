package trellis

import trellis.Core.*

/** Graph well-formedness plus the tiny generic interpreter for graph-defined resource rules. */
object Check:
  enum ResourceDisposition:
    case Allow, LowerDrop

  enum ProcessDisposition:
    case CreateChannel
    case CopyToChannel
    case TransferToChannel
    case TransferToProcess
    case ShareWithChild
    case TransferToChild
    case TransferToJoiner
    case StructuralDiscard

  final case class StructuralPolicy(duplicate: String, discard: String):
    def duplicateAllowed: Boolean = duplicate == "allow"

  final case class ResourceRule(
      entity: EntityId,
      operation: String,
      portConstraints: Map[(String, String), String],
      sameInner: Vector[(String, String)],
      disposition: ResourceDisposition
  )

  /**
   * F2 defines the actual resource table as Trellis graph data. Scala only
   * interprets a small, generic constraint vocabulary.
   */
  object ResourceRules:
    private val PortPrefix = "port."

    def rules(graph: Graph): Vector[ResourceRule] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "resource.rule").flatMap(node => decodeRule(entity, node).toOption)
      }

    def definitionErrors(graph: Graph): Vector[String] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "resource.rule").toVector.flatMap { node =>
          decodeRule(entity, node) match
            case Left(error) => Vector(s"invalid resource rule ${entity.value}: $error")
            case Right(_) => Vector.empty
        }
      }

    def forOperation(graph: Graph, operation: String): Vector[ResourceRule] =
      rules(graph).filter(_.operation == operation)

    def structural(graph: Graph, mode: Mode): StructuralPolicy =
      val name = Canon.encodeMode(mode)
      graph.entity(EntityId(s"resource.mode.$name")) match
        case Some(node) if node.kind == "resource.mode" =>
          StructuralPolicy(
            node.attrs.getOrElse("duplicate", fallbackDuplicate(mode)),
            node.attrs.getOrElse("discard", fallbackDiscard(mode))
          )
        case _ => StructuralPolicy(fallbackDuplicate(mode), fallbackDiscard(mode))

    def decision(graph: Graph, node: Node): Option[ResourceDisposition] =
      forOperation(graph, node.kind).find(matches(node, _)).map(_.disposition)

    def explainMismatch(graph: Graph, node: Node): Vector[String] =
      val candidates = forOperation(graph, node.kind)
      if candidates.isEmpty then Vector.empty
      else
        val failures = candidates.flatMap(rule => mismatchReasons(node, rule).map(reason => s"${rule.entity.value}: $reason"))
        if failures.isEmpty then Vector(s"no graph-defined resource rule admits ${node.kind}") else failures

    private def decodeRule(entity: EntityId, node: Node): Either[String, ResourceRule] =
      for
        operation <- node.attrs.get("operation").toRight(s"${entity.value} lacks operation")
        disposition <- node.attrs.get("result") match
          case Some("allow") => Right(ResourceDisposition.Allow)
          case Some("lower-drop") => Right(ResourceDisposition.LowerDrop)
          case Some(other) => Left(s"${entity.value} has unknown result $other")
          case None => Left(s"${entity.value} lacks result")
        constraints <- decodePortConstraints(node.attrs)
        sameInner <- decodeSameInner(node.attrs.get("same-inner"))
      yield ResourceRule(entity, operation, constraints, sameInner, disposition)

    private def decodePortConstraints(attrs: Map[String, String]): Either[String, Map[(String, String), String]] =
      attrs.toVector.filter(_._1.startsWith(PortPrefix)).foldLeft[Either[String, Map[(String, String), String]]](Right(Map.empty)) {
        case (acc, (key, value)) =>
          acc.flatMap { current =>
            key.stripPrefix(PortPrefix).split("\\.", -1).toVector match
              case Vector(portName, property)
                  if portName.nonEmpty && Set("mode", "capability", "direction").contains(property) =>
                Right(current.updated((portName, property), value))
              case _ => Left(s"invalid resource-rule constraint key: $key")
          }
      }

    private def decodeSameInner(value: Option[String]): Either[String, Vector[(String, String)]] = value match
      case None => Right(Vector.empty)
      case Some(raw) =>
        raw.split(";", -1).toVector.filter(_.nonEmpty).foldLeft[Either[String, Vector[(String, String)]]](Right(Vector.empty)) {
          (acc, pair) =>
            acc.flatMap { current =>
              pair.split(",", -1).toVector match
                case Vector(a, b) if a.nonEmpty && b.nonEmpty => Right(current :+ (a -> b))
                case _ => Left(s"invalid same-inner pair: $pair")
            }
        }

    private def matches(node: Node, rule: ResourceRule): Boolean = mismatchReasons(node, rule).isEmpty

    private def mismatchReasons(node: Node, rule: ResourceRule): Vector[String] =
      val ports = node.ports.map(p => p.name -> p).toMap
      val constraintErrors = rule.portConstraints.toVector.sortBy { case ((port, property), _) => (port, property) }.flatMap {
        case ((portName, property), expected) =>
          ports.get(portName) match
            case None => Vector(s"missing port $portName")
            case Some(port) =>
              val actual = property match
                case "mode" => Some(Canon.encodeMode(port.ty.mode))
                case "capability" => capabilityOf(port.ty).map(Canon.encodeCapability)
                case "direction" => Some(Canon.encodeDirection(port.direction))
                case _ => None
              if actual.contains(expected) then Vector.empty
              else Vector(s"$portName.$property expected $expected, found ${actual.getOrElse("none")}")
      }
      val innerErrors = rule.sameInner.flatMap { case (a, b) =>
        (ports.get(a).flatMap(p => innerOf(p.ty)), ports.get(b).flatMap(p => innerOf(p.ty))) match
          case (Some(left), Some(right)) if left == right => Vector.empty
          case (Some(_), Some(_)) => Vector(s"$a and $b do not carry the same inner type")
          case _ => Vector(s"$a and $b must both carry capability inner types")
      }
      constraintErrors ++ innerErrors

    private def capabilityOf(ty: Ty): Option[Capability] = ty match
      case Ty.Cap(kind, _, _, _) => Some(kind)
      case _ => None

    private def innerOf(ty: Ty): Option[Ty] = ty match
      case Ty.Cap(_, _, inner, _) => Some(inner)
      case _ => None

    private def fallbackDuplicate(mode: Mode): String = mode match
      case Mode.Unrestricted => "allow"
      case Mode.Affine | Mode.Linear => "forbid"

    private def fallbackDiscard(mode: Mode): String = mode match
      case Mode.Unrestricted => "allow"
      case Mode.Affine => "drop"
      case Mode.Linear => "forbid"

  final case class ProcessRule(
      entity: EntityId,
      operation: String,
      mode: Option[Mode],
      disposition: ProcessDisposition
  )

  /** F3 process/channel semantics are graph-defined; Scala interprets this tiny table vocabulary. */
  object ProcessRules:
    def rules(graph: Graph): Vector[ProcessRule] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "process.rule").flatMap(node => decodeRule(entity, node).toOption)
      }

    def definitionErrors(graph: Graph): Vector[String] =
      val decoded = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "process.rule").toVector.map(node => entity -> decodeRule(entity, node))
      }
      val malformed = decoded.flatMap { case (entity, result) =>
        result match
          case Left(error) => Vector(s"invalid process rule ${entity.value}: $error")
          case Right(_) => Vector.empty
      }
      val ambiguous = decoded.collect { case (_, Right(rule)) => rule }
        .groupBy(rule => (rule.operation, rule.mode))
        .toVector
        .sortBy { case ((operation, mode), _) => (operation, mode.map(Canon.encodeMode).getOrElse("")) }
        .flatMap { case ((operation, mode), rs) =>
          if rs.size <= 1 then Vector.empty
          else Vector(s"ambiguous process rules for $operation/${mode.map(Canon.encodeMode).getOrElse("any")}")
        }
      malformed ++ ambiguous

    def forOperation(graph: Graph, operation: String): Vector[ProcessRule] =
      rules(graph).filter(_.operation == operation)

    def decision(graph: Graph, operation: String, mode: Option[Mode] = None): Option[ProcessDisposition] =
      forOperation(graph, operation)
        .find(rule => rule.mode.isEmpty || rule.mode == mode)
        .map(_.disposition)

    def endpointMode(graph: Graph, entity: EntityId): Either[String, Mode] =
      graph.entity(entity) match
        case Some(node) if node.kind == "process.capability" =>
          node.attrs.get("mode").toRight(s"${entity.value} lacks mode").flatMap(decodeMode)
        case Some(node) => Left(s"${entity.value} is ${node.kind}, expected process.capability")
        case None => Left(s"missing process capability ${entity.value}")

    private def decodeRule(entity: EntityId, node: Node): Either[String, ProcessRule] =
      for
        operation <- node.attrs.get("operation").toRight(s"${entity.value} lacks operation")
        mode <- node.attrs.get("mode") match
          case None => Right(None)
          case Some(value) => decodeMode(value).map(Some(_))
        disposition <- node.attrs.get("result") match
          case Some("create-channel") => Right(ProcessDisposition.CreateChannel)
          case Some("copy-to-channel") => Right(ProcessDisposition.CopyToChannel)
          case Some("transfer-to-channel") => Right(ProcessDisposition.TransferToChannel)
          case Some("transfer-to-process") => Right(ProcessDisposition.TransferToProcess)
          case Some("share-with-child") => Right(ProcessDisposition.ShareWithChild)
          case Some("transfer-to-child") => Right(ProcessDisposition.TransferToChild)
          case Some("transfer-to-joiner") => Right(ProcessDisposition.TransferToJoiner)
          case Some("structural-discard") => Right(ProcessDisposition.StructuralDiscard)
          case Some(other) => Left(s"${entity.value} has unknown result $other")
          case None => Left(s"${entity.value} lacks result")
      yield ProcessRule(entity, operation, mode, disposition)

    private def decodeMode(value: String): Either[String, Mode] = value match
      case "unrestricted" => Right(Mode.Unrestricted)
      case "affine" => Right(Mode.Affine)
      case "linear" => Right(Mode.Linear)
      case other => Left(s"unknown structural mode $other")

  def validate(graph: Graph): Vector[String] =
    val errors = Vector.newBuilder[String]
    errors ++= ResourceRules.definitionErrors(graph)
    errors ++= ProcessRules.definitionErrors(graph)

    graph.edges.foreach { case (edgeId, edge) =>
      val fromNode = graph.nodes.get(edge.from.node)
      val toNode = graph.nodes.get(edge.to.node)
      if fromNode.isEmpty then errors += s"edge ${edgeId.value.take(8)} source node missing"
      if toNode.isEmpty then errors += s"edge ${edgeId.value.take(8)} target node missing"
      for
        fn <- fromNode
        fp <- fn.port(edge.from.port)
        tn <- toNode
        tp <- tn.port(edge.to.port)
      do
        if fp.direction != Direction.Out then errors += s"edge ${edgeId.value.take(8)} source port is not output"
        if tp.direction != Direction.In then errors += s"edge ${edgeId.value.take(8)} target port is not input"
        if fp.ty != tp.ty then errors += s"edge ${edgeId.value.take(8)} type mismatch: ${fp.ty} != ${tp.ty}"
      if fromNode.exists(_.port(edge.from.port).isEmpty) then errors += s"edge ${edgeId.value.take(8)} source port missing"
      if toNode.exists(_.port(edge.to.port).isEmpty) then errors += s"edge ${edgeId.value.take(8)} target port missing"
    }

    // One producer per input port.
    graph.nodes.foreach { case (id, node) =>
      node.ports.filter(_.direction == Direction.In).foreach { p =>
        if graph.incoming(PortRef(id, p.name)).size > 1 then errors += s"multiple producers for ${id.value.take(8)}.${p.name}"
      }
    }

    // Structural contraction permission is read from F2 mode definitions when present.
    graph.nodes.foreach { case (id, node) =>
      node.ports.filter(_.direction == Direction.Out).foreach { p =>
        val fanout = graph.outgoing(PortRef(id, p.name)).size
        if fanout > 1 && !ResourceRules.structural(graph, p.ty.mode).duplicateAllowed then
          errors += s"illegal duplication of ${p.ty.mode} capability at ${id.value.take(8)}.${p.name}"
      }
    }

    graph.nodes.foreach { case (id, node) =>
      val rules = ResourceRules.forOperation(graph, node.kind)
      if rules.nonEmpty && ResourceRules.decision(graph, node).isEmpty then
        val details = ResourceRules.explainMismatch(graph, node).mkString("; ")
        errors += s"resource operation ${id.value.take(8)} (${node.kind}) violates F2 rules: $details"
      if node.kind == "core.hole" && !node.attrs.contains("expected") then
        errors += s"hole ${id.value.take(8)} lacks expected boundary description"
    }

    errors.result()
