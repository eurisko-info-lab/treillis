package trellis

import trellis.Core.*

/** Graph well-formedness plus tiny generic interpreters for graph-defined foundation policies. */
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


  enum MachineAction:
    case AllocateOwned
    case MoveOwner
    case BeginSharedLoan
    case BeginMutableLoan
    case EndLoan
    case DropOwned
    case ProcessDispatch

  final case class MachineRule(
      entity: EntityId,
      instruction: String,
      operation: String,
      action: MachineAction
  )

  /**
   * F4 maps machine instruction kinds to a tiny trusted set of state-transition
   * primitives. Scala executes those primitives; the Trellis graph owns the
   * dispatch table and its semantic operation links.
   */
  object MachineRules:
    def rules(graph: Graph): Vector[MachineRule] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "machine.rule").flatMap(node => decodeRule(entity, node).toOption)
      }

    def definitionErrors(graph: Graph): Vector[String] =
      val decoded = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "machine.rule").toVector.map(node => entity -> decodeRule(entity, node))
      }
      val malformed = decoded.flatMap { case (entity, result) =>
        result match
          case Left(error) => Vector(s"invalid machine rule ${entity.value}: $error")
          case Right(rule) =>
            if graph.entity(EntityId(rule.operation)).isDefined then Vector.empty
            else Vector(s"invalid machine rule ${entity.value}: missing operation ${rule.operation}")
      }
      val ambiguous = decoded.collect { case (_, Right(rule)) => rule }
        .groupBy(_.instruction)
        .toVector
        .sortBy(_._1)
        .flatMap { case (instruction, rs) =>
          if rs.size <= 1 then Vector.empty
          else Vector(s"ambiguous machine rules for instruction $instruction")
        }
      malformed ++ ambiguous

    def forInstruction(graph: Graph, instruction: String): Option[MachineRule] =
      rules(graph).find(_.instruction == instruction)

    def decision(graph: Graph, instruction: String): Option[MachineAction] =
      forInstruction(graph, instruction).map(_.action)

    private def decodeRule(entity: EntityId, node: Node): Either[String, MachineRule] =
      for
        instruction <- node.attrs.get("instruction").toRight(s"${entity.value} lacks instruction")
        operation <- node.attrs.get("operation").toRight(s"${entity.value} lacks operation")
        action <- node.attrs.get("action") match
          case Some("allocate-owned") => Right(MachineAction.AllocateOwned)
          case Some("move-owner") => Right(MachineAction.MoveOwner)
          case Some("begin-shared-loan") => Right(MachineAction.BeginSharedLoan)
          case Some("begin-mutable-loan") => Right(MachineAction.BeginMutableLoan)
          case Some("end-loan") => Right(MachineAction.EndLoan)
          case Some("drop-owned") => Right(MachineAction.DropOwned)
          case Some("process-dispatch") => Right(MachineAction.ProcessDispatch)
          case Some(other) => Left(s"${entity.value} has unknown action $other")
          case None => Left(s"${entity.value} lacks action")
      yield MachineRule(entity, instruction, operation, action)


  final case class EqualityTerm(
      operator: String,
      mode: Mode = Mode.Unrestricted,
      children: Vector[EqualityTerm] = Vector.empty
  )

  final case class EClass(
      terms: Set[EqualityTerm],
      saturated: Boolean,
      iterations: Int
  )

  final case class EqualityPolicy(
      requiredPreserve: Set[String],
      proofRequired: Boolean,
      maxIterations: Int,
      maxTerms: Int
  )

  final case class EqualityRule(
      entity: EntityId,
      lhs: String,
      rhs: String,
      mode: Option[Mode],
      preserves: Set[String],
      evidence: String
  )

  final case class EqualityCostModel(weights: Map[String, Int])

  final case class EqualityOperator(
      entity: EntityId,
      operator: String,
      metrics: Map[String, Int]
  )

  /**
   * F6 owns equality admission and extraction policy as Trellis graph data.
   *
   * The host provides only bounded closure, recursive congruence traversal, and
   * weighted arithmetic. A rewrite is admitted only when its declared
   * preservation evidence covers every invariant required by the F6 policy and
   * its optional structural-mode guard matches the rewritten term.
   */
  object EqualityRules:
    val dimensions: Vector[String] = Vector(
      "nodes",
      "allocations",
      "replication",
      "interactions",
      "peak-memory",
      "communication",
      "critical-path"
    )

    private val PolicyEntity = EntityId("equality.policy.rewrite")
    private val CostModelEntity = EntityId("equality.cost-model.default")

    def invariantKeys(graph: Graph): Set[String] =
      graph.entities.toVector.flatMap { case (_, nodeId) =>
        graph.nodes.get(nodeId)
          .filter(_.kind == "equality.invariant")
          .flatMap(_.attrs.get("key"))
      }.toSet

    def costDimensionKeys(graph: Graph): Set[String] =
      graph.entities.toVector.flatMap { case (_, nodeId) =>
        graph.nodes.get(nodeId)
          .filter(_.kind == "equality.cost-dimension")
          .flatMap(_.attrs.get("key"))
      }.toSet

    def policy(graph: Graph): Either[String, EqualityPolicy] =
      graph.entity(PolicyEntity) match
        case Some(node) if node.kind == "equality.policy" =>
          for
            requiredRaw <- node.attrs.get("required-preserve").toRight("equality rewrite policy lacks required-preserve")
            required <- parseSet(requiredRaw, "required-preserve")
            proofRequired <- parseBoolean(node.attrs.get("proof-required"), "proof-required")
            maxIterations <- parsePositiveInt(node.attrs.get("max-iterations"), "max-iterations")
            maxTerms <- parsePositiveInt(node.attrs.get("max-terms"), "max-terms")
          yield EqualityPolicy(required, proofRequired, maxIterations, maxTerms)
        case Some(node) => Left(s"${PolicyEntity.value} is ${node.kind}, not equality.policy")
        case None => Left(s"missing ${PolicyEntity.value}")

    def costModel(graph: Graph): Either[String, EqualityCostModel] =
      graph.entity(CostModelEntity) match
        case Some(node) if node.kind == "equality.cost-model" =>
          dimensions.foldLeft[Either[String, Map[String, Int]]](Right(Map.empty)) { (acc, dimension) =>
            for
              current <- acc
              weight <- parseNonNegativeInt(node.attrs.get(dimension), s"cost weight $dimension")
            yield current.updated(dimension, weight)
          }.map(EqualityCostModel.apply)
        case Some(node) => Left(s"${CostModelEntity.value} is ${node.kind}, not equality.cost-model")
        case None => Left(s"missing ${CostModelEntity.value}")

    def rules(graph: Graph): Vector[EqualityRule] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId)
          .filter(_.kind == "equality.rewrite")
          .flatMap(node => decodeRule(entity, node).toOption)
      }

    def operators(graph: Graph): Vector[EqualityOperator] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId)
          .filter(_.kind == "equality.enode")
          .flatMap(node => decodeOperator(entity, node).toOption)
      }

    def definitionErrors(graph: Graph): Vector[String] =
      val errors = Vector.newBuilder[String]

      if graph.entity(PolicyEntity).isDefined then
        policy(graph) match
          case Left(error) => errors += s"invalid equality policy: $error"
          case Right(value) =>
            val unknown = value.requiredPreserve -- invariantKeys(graph)
            if unknown.nonEmpty then
              errors += s"equality policy references unknown invariants: ${unknown.toVector.sorted.mkString(",")}"

      if graph.entity(CostModelEntity).isDefined then
        costModel(graph) match
          case Left(error) => errors += s"invalid equality cost model: $error"
          case Right(_) =>
            val missing = dimensions.toSet -- costDimensionKeys(graph)
            if missing.nonEmpty then
              errors += s"equality cost model lacks dimension declarations: ${missing.toVector.sorted.mkString(",")}"

      val knownInvariants = invariantKeys(graph)
      graph.entities.toVector.sortBy(_._1.value).foreach { case (entity, nodeId) =>
        graph.nodes.get(nodeId).foreach { node =>
          if node.kind == "equality.rewrite" then
            decodeRule(entity, node) match
              case Left(error) => errors += s"invalid equality rewrite ${entity.value}: $error"
              case Right(rule) =>
                val unknown = rule.preserves -- knownInvariants
                if unknown.nonEmpty then
                  errors += s"invalid equality rewrite ${entity.value}: unknown preserved invariants ${unknown.toVector.sorted.mkString(",")}"
          else if node.kind == "equality.enode" then
            decodeOperator(entity, node) match
              case Left(error) => errors += s"invalid equality enode ${entity.value}: $error"
              case Right(_) => ()
        }
      }

      val duplicates = operators(graph).groupBy(_.operator).toVector.collect {
        case (operator, defs) if defs.size > 1 => s"ambiguous equality enode cost definition for $operator"
      }
      errors ++= duplicates.sorted
      errors.result()

    def admitted(graph: Graph, rule: EqualityRule, mode: Mode): Either[String, Boolean] =
      policy(graph).map { value =>
        rule.mode.forall(_ == mode) &&
        value.requiredPreserve.subsetOf(rule.preserves) &&
        (!value.proofRequired || rule.evidence.nonEmpty)
      }

    def saturate(graph: Graph, seed: EqualityTerm): Either[String, EClass] =
      val definitionProblems = definitionErrors(graph)
      if definitionProblems.nonEmpty then Left(definitionProblems.mkString("; "))
      else
        for
          value <- policy(graph)
        yield
          val available = rules(graph)
          var seen = Set(seed)
          var frontier = Vector(seed)
          var iterations = 0
          while frontier.nonEmpty && iterations < value.maxIterations && seen.size < value.maxTerms do
            val room = value.maxTerms - seen.size
            val next = frontier
              .flatMap(term => oneStep(term, available, value))
              .distinct
              .filterNot(seen.contains)
              .sortBy(termKey)
              .take(room)
            seen = seen ++ next
            frontier = next
            iterations += 1
          EClass(seen, frontier.isEmpty, iterations)

    def equivalent(graph: Graph, left: EqualityTerm, right: EqualityTerm): Either[String, Boolean] =
      saturate(graph, left).map(_.terms.contains(right))

    def score(graph: Graph, term: EqualityTerm): Either[String, Long] =
      val definitionProblems = definitionErrors(graph)
      if definitionProblems.nonEmpty then Left(definitionProblems.mkString("; "))
      else
        costModel(graph).map { model =>
          val definitions = operators(graph).map(op => op.operator -> op).toMap
          scoreTerm(term, model, definitions)
        }

    def extract(graph: Graph, eclass: EClass): Either[String, EqualityTerm] =
      if eclass.terms.isEmpty then Left("cannot extract from an empty equality class")
      else
        val definitionProblems = definitionErrors(graph)
        if definitionProblems.nonEmpty then Left(definitionProblems.mkString("; "))
        else
          costModel(graph).map { model =>
            val definitions = operators(graph).map(op => op.operator -> op).toMap
            eclass.terms.toVector
              .map(term => (scoreTerm(term, model, definitions), termKey(term), term))
              .sortBy { case (score, key, _) => (score, key) }
              .head
              ._3
          }

    private def oneStep(term: EqualityTerm, available: Vector[EqualityRule], policy: EqualityPolicy): Vector[EqualityTerm] =
      val root = available.flatMap { rule =>
        if isAdmitted(rule, policy, term.mode) then
          if term.operator == rule.lhs then Vector(term.copy(operator = rule.rhs))
          else if term.operator == rule.rhs then Vector(term.copy(operator = rule.lhs))
          else Vector.empty
        else Vector.empty
      }

      val children = term.children.zipWithIndex.flatMap { case (child, index) =>
        oneStep(child, available, policy).map { replacement =>
          term.copy(children = term.children.updated(index, replacement))
        }
      }

      (root ++ children).distinct

    private def isAdmitted(rule: EqualityRule, policy: EqualityPolicy, mode: Mode): Boolean =
      rule.mode.forall(_ == mode) &&
      policy.requiredPreserve.subsetOf(rule.preserves) &&
      (!policy.proofRequired || rule.evidence.nonEmpty)

    private def scoreTerm(
        term: EqualityTerm,
        model: EqualityCostModel,
        definitions: Map[String, EqualityOperator]
    ): Long =
      val metrics = definitions.get(term.operator) match
        case Some(definition) => definition.metrics
        case None => Map("nodes" -> 1)
      val own = dimensions.iterator.map { dimension =>
        model.weights.getOrElse(dimension, 0).toLong * metrics.getOrElse(dimension, 0).toLong
      }.sum
      own + term.children.iterator.map(child => scoreTerm(child, model, definitions)).sum

    private def termKey(term: EqualityTerm): String =
      val children = term.children.map(termKey).mkString("(", ",", ")")
      s"${term.operator}@${Canon.encodeMode(term.mode)}$children"

    private def decodeRule(entity: EntityId, node: Node): Either[String, EqualityRule] =
      for
        lhs <- node.attrs.get("lhs").filter(_.nonEmpty).toRight(s"${entity.value} lacks lhs")
        rhs <- node.attrs.get("rhs").filter(_.nonEmpty).toRight(s"${entity.value} lacks rhs")
        mode <- decodeModeConstraint(node.attrs.getOrElse("mode", "any"))
        preserveRaw <- node.attrs.get("preserve").toRight(s"${entity.value} lacks preserve")
        preserves <- parseSet(preserveRaw, "preserve")
        evidence <- node.attrs.get("evidence").toRight(s"${entity.value} lacks evidence")
      yield EqualityRule(entity, lhs, rhs, mode, preserves, evidence)

    private def decodeOperator(entity: EntityId, node: Node): Either[String, EqualityOperator] =
      for
        operator <- node.attrs.get("operator").filter(_.nonEmpty).toRight(s"${entity.value} lacks operator")
        metrics <- dimensions.foldLeft[Either[String, Map[String, Int]]](Right(Map.empty)) { (acc, dimension) =>
          node.attrs.get(dimension) match
            case None => acc
            case Some(raw) =>
              for
                current <- acc
                value <- raw.toIntOption.filter(_ >= 0).toRight(s"${entity.value} has invalid $dimension metric $raw")
              yield current.updated(dimension, value)
        }
      yield EqualityOperator(entity, operator, metrics)

    private def decodeModeConstraint(value: String): Either[String, Option[Mode]] = value match
      case "any" => Right(None)
      case "unrestricted" => Right(Some(Mode.Unrestricted))
      case "affine" => Right(Some(Mode.Affine))
      case "linear" => Right(Some(Mode.Linear))
      case other => Left(s"unknown equality rewrite mode $other")

    private def parseSet(raw: String, label: String): Either[String, Set[String]] =
      val values = raw.split(";", -1).toVector.filter(_.nonEmpty)
      if values.isEmpty then Left(s"$label must contain at least one key")
      else if values.distinct.size != values.size then Left(s"$label contains duplicate keys")
      else Right(values.toSet)

    private def parseBoolean(value: Option[String], label: String): Either[String, Boolean] = value match
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(other) => Left(s"$label must be true or false, found $other")
      case None => Left(s"missing $label")

    private def parsePositiveInt(value: Option[String], label: String): Either[String, Int] = value match
      case Some(raw) => raw.toIntOption.filter(_ > 0).toRight(s"$label must be a positive integer, found $raw")
      case None => Left(s"missing $label")

    private def parseNonNegativeInt(value: Option[String], label: String): Either[String, Int] = value match
      case Some(raw) => raw.toIntOption.filter(_ >= 0).toRight(s"$label must be a non-negative integer, found $raw")
      case None => Left(s"missing $label")


  final case class DeltaNetPolicy(
      requiredPreserve: Set[String],
      proofRequired: Boolean,
      maxInteractions: Int,
      scheduler: String,
      readback: String
  )

  final case class DeltaNetStructuralPolicy(
      duplicateAgent: EntityId,
      eraseAgent: EntityId,
      unrestrictedDiscard: String,
      affineDiscard: String,
      linearDiscard: String
  )

  final case class DeltaNetLowering(
      entity: EntityId,
      instruction: String,
      operation: EntityId,
      agent: EntityId,
      preserves: Set[String],
      evidence: String
  )

  enum DeltaNetAction:
    case Duplicate
    case Erase
    case Drop
    case Enqueue
    case DequeueOrBlock
    case SplitContext
    case TransferResult

  final case class DeltaNetInteraction(
      entity: EntityId,
      left: EntityId,
      right: EntityId,
      mode: Option[Mode],
      action: DeltaNetAction
  )

  /**
   * F7 defines DeltaNet lowering, structural agents, local active-pair
   * interactions, scheduling, and readback policy as Trellis graph data.
   *
   * Scala only interprets this small declarative vocabulary. The first F7
   * reducer deliberately delegates machine-operation primitives to CESK-R
   * after graph-defined lowering; structural replicator/eraser interactions
   * are executed directly as local net interactions.
   */
  object DeltaNetRules:
    private val PolicyEntity = EntityId("deltanet.policy.execution")
    private val StructuralPolicyEntity = EntityId("deltanet.policy.structural")

    def agentKinds(graph: Graph): Set[EntityId] =
      graph.entities.toVector.flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "deltanet.agent-kind").map(_ => entity)
      }.toSet

    def policy(graph: Graph): Either[String, DeltaNetPolicy] =
      graph.entity(PolicyEntity) match
        case Some(node) if node.kind == "deltanet.policy" =>
          for
            requiredRaw <- node.attrs.get("required-preserve").toRight("DeltaNet policy lacks required-preserve")
            required <- parseSet(requiredRaw, "DeltaNet required-preserve")
            proofRequired <- parseBoolean(node.attrs.get("proof-required"), "DeltaNet proof-required")
            maxInteractions <- parsePositiveInt(node.attrs.get("max-interactions"), "DeltaNet max-interactions")
            scheduler <- node.attrs.get("scheduler").filter(_.nonEmpty).toRight("DeltaNet policy lacks scheduler")
            readback <- node.attrs.get("readback").filter(_.nonEmpty).toRight("DeltaNet policy lacks readback")
          yield DeltaNetPolicy(required, proofRequired, maxInteractions, scheduler, readback)
        case Some(node) => Left(s"${PolicyEntity.value} is ${node.kind}, not deltanet.policy")
        case None => Left(s"missing ${PolicyEntity.value}")

    def structuralPolicy(graph: Graph): Either[String, DeltaNetStructuralPolicy] =
      graph.entity(StructuralPolicyEntity) match
        case Some(node) if node.kind == "deltanet.structural-policy" =>
          for
            duplicate <- node.attrs.get("duplicate-agent").filter(_.nonEmpty).toRight("DeltaNet structural policy lacks duplicate-agent")
            erase <- node.attrs.get("erase-agent").filter(_.nonEmpty).toRight("DeltaNet structural policy lacks erase-agent")
            unrestricted <- parseDiscard(node.attrs.get("unrestricted-discard"), "unrestricted-discard")
            affine <- parseDiscard(node.attrs.get("affine-discard"), "affine-discard")
            linear <- parseDiscard(node.attrs.get("linear-discard"), "linear-discard")
          yield DeltaNetStructuralPolicy(EntityId(duplicate), EntityId(erase), unrestricted, affine, linear)
        case Some(node) => Left(s"${StructuralPolicyEntity.value} is ${node.kind}, not deltanet.structural-policy")
        case None => Left(s"missing ${StructuralPolicyEntity.value}")

    def lowerings(graph: Graph): Vector[DeltaNetLowering] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId)
          .filter(_.kind == "deltanet.lowering-rule")
          .flatMap(node => decodeLowering(entity, node).toOption)
      }

    def interactions(graph: Graph): Vector[DeltaNetInteraction] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId)
          .filter(_.kind == "deltanet.interaction-rule")
          .flatMap(node => decodeInteraction(entity, node).toOption)
      }

    def loweringForInstruction(graph: Graph, instruction: String): Option[DeltaNetLowering] =
      lowerings(graph).find(_.instruction == instruction)

    def admittedLowering(graph: Graph, lowering: DeltaNetLowering): Either[String, Boolean] =
      policy(graph).map { p =>
        p.requiredPreserve.subsetOf(lowering.preserves) &&
        (!p.proofRequired || lowering.evidence.nonEmpty)
      }

    def interaction(
        graph: Graph,
        left: EntityId,
        right: EntityId,
        mode: Option[Mode]
    ): Option[DeltaNetAction] =
      interactions(graph).find { rule =>
        val pair =
          (rule.left == left && rule.right == right) ||
          (rule.left == right && rule.right == left)
        pair && (rule.mode.isEmpty || rule.mode == mode)
      }.map(_.action)

    def definitionErrors(graph: Graph): Vector[String] =
      val errors = Vector.newBuilder[String]
      val knownInvariants = EqualityRules.invariantKeys(graph)
      val knownAgents = agentKinds(graph)

      if graph.entity(PolicyEntity).isDefined then
        policy(graph) match
          case Left(error) => errors += s"invalid DeltaNet policy: $error"
          case Right(p) =>
            val unknown = p.requiredPreserve -- knownInvariants
            if unknown.nonEmpty then
              errors += s"DeltaNet policy references unknown invariants: ${unknown.toVector.sorted.mkString(",")}"

      if graph.entity(StructuralPolicyEntity).isDefined then
        structuralPolicy(graph) match
          case Left(error) => errors += s"invalid DeltaNet structural policy: $error"
          case Right(p) =>
            if !knownAgents.contains(p.duplicateAgent) then
              errors += s"DeltaNet structural policy references unknown duplicate agent ${p.duplicateAgent.value}"
            if !knownAgents.contains(p.eraseAgent) then
              errors += s"DeltaNet structural policy references unknown erase agent ${p.eraseAgent.value}"

      val decodedLowerings = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "deltanet.lowering-rule").toVector.map(node => entity -> decodeLowering(entity, node))
      }
      decodedLowerings.foreach {
        case (entity, Left(error)) => errors += s"invalid DeltaNet lowering ${entity.value}: $error"
        case (entity, Right(rule)) =>
          if graph.entity(rule.operation).isEmpty then
            errors += s"invalid DeltaNet lowering ${entity.value}: missing operation ${rule.operation.value}"
          if !knownAgents.contains(rule.agent) then
            errors += s"invalid DeltaNet lowering ${entity.value}: missing agent ${rule.agent.value}"
          val unknown = rule.preserves -- knownInvariants
          if unknown.nonEmpty then
            errors += s"invalid DeltaNet lowering ${entity.value}: unknown preserved invariants ${unknown.toVector.sorted.mkString(",")}"
          policy(graph) match
            case Right(p) if !p.requiredPreserve.subsetOf(rule.preserves) =>
              errors += s"invalid DeltaNet lowering ${entity.value}: does not preserve required invariants"
            case Right(p) if p.proofRequired && rule.evidence.isEmpty =>
              errors += s"invalid DeltaNet lowering ${entity.value}: preservation evidence required"
            case _ => ()
      }
      decodedLowerings.collect { case (_, Right(rule)) => rule }
        .groupBy(_.instruction).toVector.sortBy(_._1).foreach { case (instruction, rules) =>
          if rules.size > 1 then errors += s"ambiguous DeltaNet lowerings for instruction $instruction"
        }

      val decodedInteractions = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "deltanet.interaction-rule").toVector.map(node => entity -> decodeInteraction(entity, node))
      }
      decodedInteractions.foreach {
        case (entity, Left(error)) => errors += s"invalid DeltaNet interaction ${entity.value}: $error"
        case (entity, Right(rule)) =>
          if !knownAgents.contains(rule.left) then
            errors += s"invalid DeltaNet interaction ${entity.value}: missing left agent ${rule.left.value}"
          if !knownAgents.contains(rule.right) then
            errors += s"invalid DeltaNet interaction ${entity.value}: missing right agent ${rule.right.value}"
      }
      decodedInteractions.collect { case (_, Right(rule)) => rule }
        .groupBy(rule => (orderedPair(rule.left, rule.right), rule.mode))
        .toVector.foreach { case ((pair, mode), rules) =>
          if rules.size > 1 then
            errors += s"ambiguous DeltaNet interaction for ${pair._1.value}/${pair._2.value}/${mode.map(Canon.encodeMode).getOrElse("any")}"
        }

      errors.result()

    private def decodeLowering(entity: EntityId, node: Node): Either[String, DeltaNetLowering] =
      for
        instruction <- node.attrs.get("instruction").filter(_.nonEmpty).toRight(s"${entity.value} lacks instruction")
        operation <- node.attrs.get("operation").filter(_.nonEmpty).toRight(s"${entity.value} lacks operation")
        agent <- node.attrs.get("agent").filter(_.nonEmpty).toRight(s"${entity.value} lacks agent")
        preserveRaw <- node.attrs.get("preserves").toRight(s"${entity.value} lacks preserves")
        preserves <- parseSet(preserveRaw, s"${entity.value} preserves")
        evidence = node.attrs.getOrElse("evidence", "")
      yield DeltaNetLowering(entity, instruction, EntityId(operation), EntityId(agent), preserves, evidence)

    private def decodeInteraction(entity: EntityId, node: Node): Either[String, DeltaNetInteraction] =
      for
        left <- node.attrs.get("left").filter(_.nonEmpty).toRight(s"${entity.value} lacks left agent")
        right <- node.attrs.get("right").filter(_.nonEmpty).toRight(s"${entity.value} lacks right agent")
        mode <- node.attrs.get("mode") match
          case None => Right(None)
          case Some(value) => decodeMode(value).map(Some(_))
        action <- node.attrs.get("action") match
          case Some("duplicate") => Right(DeltaNetAction.Duplicate)
          case Some("erase") => Right(DeltaNetAction.Erase)
          case Some("drop") => Right(DeltaNetAction.Drop)
          case Some("enqueue") => Right(DeltaNetAction.Enqueue)
          case Some("dequeue-or-block") => Right(DeltaNetAction.DequeueOrBlock)
          case Some("split-context") => Right(DeltaNetAction.SplitContext)
          case Some("transfer-result") => Right(DeltaNetAction.TransferResult)
          case Some(other) => Left(s"${entity.value} has unknown action $other")
          case None => Left(s"${entity.value} lacks action")
      yield DeltaNetInteraction(entity, EntityId(left), EntityId(right), mode, action)

    private def orderedPair(a: EntityId, b: EntityId): (EntityId, EntityId) =
      if a.value <= b.value then (a, b) else (b, a)

    private def decodeMode(value: String): Either[String, Mode] = value match
      case "unrestricted" => Right(Mode.Unrestricted)
      case "affine" => Right(Mode.Affine)
      case "linear" => Right(Mode.Linear)
      case other => Left(s"unknown DeltaNet structural mode $other")

    private def parseSet(raw: String, label: String): Either[String, Set[String]] =
      val values = raw.split(";", -1).toVector.filter(_.nonEmpty)
      if values.isEmpty then Left(s"$label must contain at least one key")
      else if values.distinct.size != values.size then Left(s"$label contains duplicate keys")
      else Right(values.toSet)

    private def parseBoolean(value: Option[String], label: String): Either[String, Boolean] = value match
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(other) => Left(s"$label must be true or false, found $other")
      case None => Left(s"missing $label")

    private def parsePositiveInt(value: Option[String], label: String): Either[String, Int] = value match
      case Some(raw) => raw.toIntOption.filter(_ > 0).toRight(s"$label must be a positive integer, found $raw")
      case None => Left(s"missing $label")

    private def parseDiscard(value: Option[String], label: String): Either[String, String] = value match
      case Some("erase") => Right("erase")
      case Some("drop") => Right("drop")
      case Some("forbid") => Right("forbid")
      case Some(other) => Left(s"$label must be erase, drop, or forbid, found $other")
      case None => Left(s"missing $label")

  final case class DeltaNetRuntimePolicy(
      requiredPreserve: Set[String],
      proofRequired: Boolean,
      maxReductions: Int,
      scheduler: String,
      readback: String,
      executor: String,
      delegate: Boolean,
      oracle: String
  )

  enum DeltaNetPrimitive:
    case AllocateOwned
    case MoveOwner
    case BeginSharedLoan
    case BeginMutableLoan
    case EndLoan
    case DropOwned
    case CreateChannel
    case Send
    case Receive
    case Spawn
    case Terminate
    case Join

  final case class DeltaNetReduction(
      entity: EntityId,
      agent: EntityId,
      operation: EntityId,
      primitive: DeltaNetPrimitive,
      preserves: Set[String],
      evidence: String
  )

  /**
   * F8 makes DeltaNet independently executable. F7 still defines lowering and
   * active-pair policy; F8 adds an agent-to-primitive reduction table and an
   * explicit no-delegation runtime policy. CESK-R is retained only as a
   * differential oracle.
   */
  object DeltaNetRuntimeRules:
    private val PolicyEntity = EntityId("deltanet.policy.runtime")

    def enabled(graph: Graph): Boolean = graph.entity(PolicyEntity).isDefined

    def policy(graph: Graph): Either[String, DeltaNetRuntimePolicy] =
      graph.entity(PolicyEntity) match
        case Some(node) if node.kind == "deltanet.runtime-policy" =>
          for
            requiredRaw <- node.attrs.get("required-preserve").toRight("DeltaNet runtime policy lacks required-preserve")
            required <- parseSet(requiredRaw, "DeltaNet runtime required-preserve")
            proofRequired <- parseBoolean(node.attrs.get("proof-required"), "DeltaNet runtime proof-required")
            maxReductions <- parsePositiveInt(node.attrs.get("max-reductions"), "DeltaNet runtime max-reductions")
            scheduler <- node.attrs.get("scheduler").filter(_.nonEmpty).toRight("DeltaNet runtime policy lacks scheduler")
            readback <- node.attrs.get("readback").filter(_.nonEmpty).toRight("DeltaNet runtime policy lacks readback")
            executor <- node.attrs.get("executor").filter(_.nonEmpty).toRight("DeltaNet runtime policy lacks executor")
            delegate <- parseBoolean(node.attrs.get("delegate"), "DeltaNet runtime delegate")
            oracle <- node.attrs.get("oracle").filter(_.nonEmpty).toRight("DeltaNet runtime policy lacks oracle")
          yield DeltaNetRuntimePolicy(required, proofRequired, maxReductions, scheduler, readback, executor, delegate, oracle)
        case Some(node) => Left(s"${PolicyEntity.value} is ${node.kind}, not deltanet.runtime-policy")
        case None => Left(s"missing ${PolicyEntity.value}")

    def rules(graph: Graph): Vector[DeltaNetReduction] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId)
          .filter(_.kind == "deltanet.reduction-rule")
          .flatMap(node => decodeReduction(entity, node).toOption)
      }

    def ruleForAgent(graph: Graph, agent: EntityId): Option[DeltaNetReduction] =
      rules(graph).find(_.agent == agent)

    def admitted(graph: Graph, rule: DeltaNetReduction): Either[String, Boolean] =
      policy(graph).map { p =>
        p.requiredPreserve.subsetOf(rule.preserves) &&
        (!p.proofRequired || rule.evidence.nonEmpty)
      }

    def definitionErrors(graph: Graph): Vector[String] =
      val errors = Vector.newBuilder[String]
      val policyPresent = graph.entity(PolicyEntity).isDefined
      val reductionNodes = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "deltanet.reduction-rule").toVector.map(node => entity -> decodeReduction(entity, node))
      }

      if reductionNodes.nonEmpty && !policyPresent then
        errors += s"DeltaNet runtime reductions exist without ${PolicyEntity.value}"

      val decodedPolicy = if policyPresent then Some(policy(graph)) else None
      decodedPolicy.foreach {
        case Left(error) => errors += s"invalid DeltaNet runtime policy: $error"
        case Right(p) =>
          val unknown = p.requiredPreserve -- EqualityRules.invariantKeys(graph)
          if unknown.nonEmpty then errors += s"DeltaNet runtime policy references unknown invariants: ${unknown.toVector.sorted.mkString(",")}" 
          if p.executor != "independent" then errors += s"DeltaNet runtime executor must be independent, found ${p.executor}"
          if p.delegate then errors += "DeltaNet runtime delegation must be disabled in F8"
      }

      val knownAgents = DeltaNetRules.agentKinds(graph)
      val loweringsByAgent = DeltaNetRules.lowerings(graph).groupBy(_.agent)
      reductionNodes.foreach {
        case (entity, Left(error)) => errors += s"invalid DeltaNet reduction ${entity.value}: $error"
        case (entity, Right(rule)) =>
          if !knownAgents.contains(rule.agent) then errors += s"invalid DeltaNet reduction ${entity.value}: missing agent ${rule.agent.value}"
          if graph.entity(rule.operation).isEmpty then errors += s"invalid DeltaNet reduction ${entity.value}: missing operation ${rule.operation.value}"
          val unknown = rule.preserves -- EqualityRules.invariantKeys(graph)
          if unknown.nonEmpty then errors += s"invalid DeltaNet reduction ${entity.value}: unknown preserved invariants ${unknown.toVector.sorted.mkString(",")}" 
          decodedPolicy.collect { case Right(p) => p }.foreach { p =>
            if !p.requiredPreserve.subsetOf(rule.preserves) then errors += s"invalid DeltaNet reduction ${entity.value}: does not preserve required invariants"
            if p.proofRequired && rule.evidence.isEmpty then errors += s"invalid DeltaNet reduction ${entity.value}: preservation evidence required"
          }
          loweringsByAgent.get(rule.agent) match
            case Some(lowerings) if lowerings.size == 1 && lowerings.head.operation != rule.operation =>
              errors += s"invalid DeltaNet reduction ${entity.value}: operation ${rule.operation.value} disagrees with lowering ${lowerings.head.operation.value}"
            case Some(lowerings) if lowerings.size > 1 =>
              errors += s"invalid DeltaNet reduction ${entity.value}: agent ${rule.agent.value} has ambiguous lowerings"
            case None => errors += s"invalid DeltaNet reduction ${entity.value}: agent ${rule.agent.value} is not produced by a lowering"
            case _ => ()
      }

      val goodRules = reductionNodes.collect { case (_, Right(rule)) => rule }
      goodRules.groupBy(_.agent).foreach { case (agent, xs) =>
        if xs.size > 1 then errors += s"ambiguous DeltaNet reduction for agent ${agent.value}"
      }

      if policyPresent then
        val expected = DeltaNetRules.lowerings(graph).map(_.agent).toSet
        val actual = goodRules.map(_.agent).toSet
        val missing = expected -- actual
        val extra = actual -- expected
        if missing.nonEmpty then errors += s"DeltaNet runtime lacks reductions for ${missing.toVector.map(_.value).sorted.mkString(",")}" 
        if extra.nonEmpty then errors += s"DeltaNet runtime has reductions for unlowered agents ${extra.toVector.map(_.value).sorted.mkString(",")}" 

      errors.result()

    private def decodeReduction(entity: EntityId, node: Node): Either[String, DeltaNetReduction] =
      for
        agent <- node.attrs.get("agent").filter(_.nonEmpty).toRight(s"${entity.value} lacks agent")
        operation <- node.attrs.get("operation").filter(_.nonEmpty).toRight(s"${entity.value} lacks operation")
        primitive <- node.attrs.get("primitive") match
          case Some("allocate-owned") => Right(DeltaNetPrimitive.AllocateOwned)
          case Some("move-owner") => Right(DeltaNetPrimitive.MoveOwner)
          case Some("begin-shared-loan") => Right(DeltaNetPrimitive.BeginSharedLoan)
          case Some("begin-mutable-loan") => Right(DeltaNetPrimitive.BeginMutableLoan)
          case Some("end-loan") => Right(DeltaNetPrimitive.EndLoan)
          case Some("drop-owned") => Right(DeltaNetPrimitive.DropOwned)
          case Some("create-channel") => Right(DeltaNetPrimitive.CreateChannel)
          case Some("send") => Right(DeltaNetPrimitive.Send)
          case Some("receive") => Right(DeltaNetPrimitive.Receive)
          case Some("spawn") => Right(DeltaNetPrimitive.Spawn)
          case Some("terminate") => Right(DeltaNetPrimitive.Terminate)
          case Some("join") => Right(DeltaNetPrimitive.Join)
          case Some(other) => Left(s"${entity.value} has unknown primitive $other")
          case None => Left(s"${entity.value} lacks primitive")
        preserveRaw <- node.attrs.get("preserves").toRight(s"${entity.value} lacks preserves")
        preserves <- parseSet(preserveRaw, s"${entity.value} preserves")
        evidence = node.attrs.getOrElse("evidence", "")
      yield DeltaNetReduction(entity, EntityId(agent), EntityId(operation), primitive, preserves, evidence)

    private def parseSet(raw: String, label: String): Either[String, Set[String]] =
      val values = raw.split(";", -1).toVector.filter(_.nonEmpty)
      if values.isEmpty then Left(s"$label must contain at least one key")
      else if values.distinct.size != values.size then Left(s"$label contains duplicate keys")
      else Right(values.toSet)

    private def parseBoolean(value: Option[String], label: String): Either[String, Boolean] = value match
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(other) => Left(s"$label must be true or false, found $other")
      case None => Left(s"missing $label")

    private def parsePositiveInt(value: Option[String], label: String): Either[String, Int] = value match
      case Some(raw) => raw.toIntOption.filter(_ > 0).toRight(s"$label must be a positive integer, found $raw")
      case None => Left(s"missing $label")


  final case class DeltaNetParallelPolicy(
      requiredPreserve: Set[String],
      proofRequired: Boolean,
      maxRounds: Int,
      scheduler: String,
      tieBreak: String,
      conflict: String,
      independence: String,
      oracle: String,
      confluence: String
  )

  final case class DeltaNetParallelProfile(
      entity: EntityId,
      agent: EntityId,
      operation: EntityId,
      touches: Vector[String],
      preserves: Set[String],
      evidence: String
  )

  /**
   * F9 makes deterministic parallelism semantic data. The host discovers a
   * maximal non-conflicting round from dynamic touch keys, but the scheduler,
   * conflict relation, preservation contract, oracle, and per-agent footprint
   * selectors all live in the Trellis graph.
   */
  object DeltaNetParallelRules:
    private val PolicyEntity = EntityId("deltanet.policy.parallel")

    def enabled(graph: Graph): Boolean = graph.entity(PolicyEntity).isDefined

    def policy(graph: Graph): Either[String, DeltaNetParallelPolicy] =
      graph.entity(PolicyEntity) match
        case Some(node) if node.kind == "deltanet.parallel-policy" =>
          for
            requiredRaw <- node.attrs.get("required-preserve").toRight("DeltaNet parallel policy lacks required-preserve")
            required <- parseSet(requiredRaw, "DeltaNet parallel required-preserve")
            proofRequired <- parseBoolean(node.attrs.get("proof-required"), "DeltaNet parallel proof-required")
            maxRounds <- parsePositiveInt(node.attrs.get("max-rounds"), "DeltaNet parallel max-rounds")
            scheduler <- node.attrs.get("scheduler").filter(_.nonEmpty).toRight("DeltaNet parallel policy lacks scheduler")
            tieBreak <- node.attrs.get("tie-break").filter(_.nonEmpty).toRight("DeltaNet parallel policy lacks tie-break")
            conflict <- node.attrs.get("conflict").filter(_.nonEmpty).toRight("DeltaNet parallel policy lacks conflict")
            independence <- node.attrs.get("independence").filter(_.nonEmpty).toRight("DeltaNet parallel policy lacks independence")
            oracle <- node.attrs.get("oracle").filter(_.nonEmpty).toRight("DeltaNet parallel policy lacks oracle")
            confluence <- node.attrs.get("confluence").filter(_.nonEmpty).toRight("DeltaNet parallel policy lacks confluence")
          yield DeltaNetParallelPolicy(required, proofRequired, maxRounds, scheduler, tieBreak, conflict, independence, oracle, confluence)
        case Some(node) => Left(s"${PolicyEntity.value} is ${node.kind}, not deltanet.parallel-policy")
        case None => Left(s"missing ${PolicyEntity.value}")

    def profiles(graph: Graph): Vector[DeltaNetParallelProfile] =
      graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId)
          .filter(_.kind == "deltanet.parallel-profile")
          .flatMap(node => decodeProfile(entity, node).toOption)
      }

    def profileForAgent(graph: Graph, agent: EntityId): Option[DeltaNetParallelProfile] =
      profiles(graph).find(_.agent == agent)

    def admitted(graph: Graph, profile: DeltaNetParallelProfile): Either[String, Boolean] =
      policy(graph).map { p =>
        p.requiredPreserve.subsetOf(profile.preserves) &&
        (!p.proofRequired || profile.evidence.nonEmpty)
      }

    def definitionErrors(graph: Graph): Vector[String] =
      val errors = Vector.newBuilder[String]
      val policyPresent = graph.entity(PolicyEntity).isDefined
      val profileNodes = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, nodeId) =>
        graph.nodes.get(nodeId).filter(_.kind == "deltanet.parallel-profile").toVector.map(node => entity -> decodeProfile(entity, node))
      }

      if profileNodes.nonEmpty && !policyPresent then
        errors += s"DeltaNet parallel profiles exist without ${PolicyEntity.value}"

      val decodedPolicy = if policyPresent then Some(policy(graph)) else None
      decodedPolicy.foreach {
        case Left(error) => errors += s"invalid DeltaNet parallel policy: $error"
        case Right(p) =>
          val unknown = p.requiredPreserve -- EqualityRules.invariantKeys(graph)
          if unknown.nonEmpty then errors += s"DeltaNet parallel policy references unknown invariants: ${unknown.toVector.sorted.mkString(",")}" 
          if !Set("maximal-nonconflicting", "singleton").contains(p.scheduler) then
            errors += s"DeltaNet parallel scheduler must be maximal-nonconflicting or singleton, found ${p.scheduler}"
          if p.tieBreak != "stable-agent-id" then errors += s"DeltaNet parallel tie-break must be stable-agent-id, found ${p.tieBreak}"
          if p.conflict != "touch-overlap" then errors += s"DeltaNet parallel conflict must be touch-overlap, found ${p.conflict}"
          if p.independence != "disjoint-touch" then errors += s"DeltaNet parallel independence must be disjoint-touch, found ${p.independence}"
          if p.oracle != "sequential-f8" then errors += s"DeltaNet parallel oracle must be sequential-f8, found ${p.oracle}"
          if p.confluence != "readback-equality" then errors += s"DeltaNet parallel confluence must be readback-equality, found ${p.confluence}"
      }

      val runtimeByAgent = DeltaNetRuntimeRules.rules(graph).groupBy(_.agent)
      profileNodes.foreach {
        case (entity, Left(error)) => errors += s"invalid DeltaNet parallel profile ${entity.value}: $error"
        case (entity, Right(profile)) =>
          if graph.entity(profile.agent).isEmpty then errors += s"invalid DeltaNet parallel profile ${entity.value}: missing agent ${profile.agent.value}"
          if graph.entity(profile.operation).isEmpty then errors += s"invalid DeltaNet parallel profile ${entity.value}: missing operation ${profile.operation.value}"
          val unknown = profile.preserves -- EqualityRules.invariantKeys(graph)
          if unknown.nonEmpty then errors += s"invalid DeltaNet parallel profile ${entity.value}: unknown preserved invariants ${unknown.toVector.sorted.mkString(",")}" 
          decodedPolicy.collect { case Right(p) => p }.foreach { p =>
            if !p.requiredPreserve.subsetOf(profile.preserves) then errors += s"invalid DeltaNet parallel profile ${entity.value}: does not preserve required invariants"
            if p.proofRequired && profile.evidence.isEmpty then errors += s"invalid DeltaNet parallel profile ${entity.value}: commutation evidence required"
          }
          runtimeByAgent.get(profile.agent) match
            case Some(rules) if rules.size == 1 && rules.head.operation != profile.operation =>
              errors += s"invalid DeltaNet parallel profile ${entity.value}: operation ${profile.operation.value} disagrees with runtime reduction ${rules.head.operation.value}"
            case Some(rules) if rules.size > 1 =>
              errors += s"invalid DeltaNet parallel profile ${entity.value}: agent ${profile.agent.value} has ambiguous runtime reductions"
            case None => errors += s"invalid DeltaNet parallel profile ${entity.value}: agent ${profile.agent.value} has no F8 runtime reduction"
            case _ => ()
      }

      val goodProfiles = profileNodes.collect { case (_, Right(profile)) => profile }
      goodProfiles.groupBy(_.agent).foreach { case (agent, xs) =>
        if xs.size > 1 then errors += s"ambiguous DeltaNet parallel profiles for agent ${agent.value}"
      }

      if policyPresent then
        val expected = DeltaNetRuntimeRules.rules(graph).map(_.agent).toSet
        val actual = goodProfiles.map(_.agent).toSet
        val missing = expected -- actual
        val extra = actual -- expected
        if missing.nonEmpty then errors += s"DeltaNet parallel policy lacks profiles for ${missing.toVector.map(_.value).sorted.mkString(",")}" 
        if extra.nonEmpty then errors += s"DeltaNet parallel policy profiles unknown runtime agents ${extra.toVector.map(_.value).sorted.mkString(",")}" 

      errors.result()

    private def decodeProfile(entity: EntityId, node: Node): Either[String, DeltaNetParallelProfile] =
      for
        agent <- node.attrs.get("agent").filter(_.nonEmpty).toRight(s"${entity.value} lacks agent")
        operation <- node.attrs.get("operation").filter(_.nonEmpty).toRight(s"${entity.value} lacks operation")
        touchesRaw <- node.attrs.get("touches").filter(_.nonEmpty).toRight(s"${entity.value} lacks touches")
        touches <- parseTouches(touchesRaw, s"${entity.value} touches")
        preserveRaw <- node.attrs.get("preserves").toRight(s"${entity.value} lacks preserves")
        preserves <- parseSet(preserveRaw, s"${entity.value} preserves")
        evidence = node.attrs.getOrElse("evidence", "")
      yield DeltaNetParallelProfile(entity, EntityId(agent), EntityId(operation), touches, preserves, evidence)

    private def parseTouches(raw: String, label: String): Either[String, Vector[String]] =
      val values = raw.split(";", -1).toVector.filter(_.nonEmpty)
      if values.isEmpty then Left(s"$label must contain at least one selector")
      else if values.distinct.size != values.size then Left(s"$label contains duplicate selectors")
      else
        values.find { selector =>
          val i = selector.indexOf(':')
          i <= 0 || i == selector.length - 1
        } match
          case Some(bad) => Left(s"$label contains invalid selector $bad")
          case None => Right(values)

    private def parseSet(raw: String, label: String): Either[String, Set[String]] =
      val values = raw.split(";", -1).toVector.filter(_.nonEmpty)
      if values.isEmpty then Left(s"$label must contain at least one key")
      else if values.distinct.size != values.size then Left(s"$label contains duplicate keys")
      else Right(values.toSet)

    private def parseBoolean(value: Option[String], label: String): Either[String, Boolean] = value match
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(other) => Left(s"$label must be true or false, found $other")
      case None => Left(s"missing $label")

    private def parsePositiveInt(value: Option[String], label: String): Either[String, Int] = value match
      case Some(raw) => raw.toIntOption.filter(_ > 0).toRight(s"$label must be a positive integer, found $raw")
      case None => Left(s"missing $label")

  final case class DeltaNetEvidencePolicy(
      encoding: String,
      hash: String,
      stateRoot: String,
      roundOrder: String,
      agentOrder: String,
      verification: String,
      requireFootprints: Boolean,
      requireConfluence: Boolean,
      bindFoundationRoot: Boolean,
      bindPolicyContent: Boolean
  )

  /**
   * F10 turns deterministic parallel execution into replayable canonical
   * evidence. The certificate shape remains a runtime value, while its
   * encoding, hashing, ordering, replay, and semantic-binding policy are
   * constitutional Trellis graph data.
   */
  object DeltaNetEvidenceRules:
    private val PolicyEntity = EntityId("deltanet.policy.evidence")
    private val ComponentEntities = Set(
      EntityId("deltanet.execution-certificate"),
      EntityId("deltanet.round-certificate"),
      EntityId("deltanet.redex-certificate"),
      EntityId("deltanet.net-root"),
      EntityId("deltanet.state-root"),
      EntityId("deltanet.readback-root"),
      EntityId("deltanet.replay"),
      EntityId("deltanet.verifier")
    )

    def enabled(graph: Graph): Boolean = graph.entity(PolicyEntity).isDefined

    def policy(graph: Graph): Either[String, DeltaNetEvidencePolicy] =
      graph.entity(PolicyEntity) match
        case Some(node) if node.kind == "deltanet.evidence-policy" =>
          for
            encoding <- required(node, "encoding")
            hash <- required(node, "hash")
            stateRoot <- required(node, "state-root")
            roundOrder <- required(node, "round-order")
            agentOrder <- required(node, "agent-order")
            verification <- required(node, "verification")
            requireFootprints <- parseBoolean(node.attrs.get("require-footprints"), "DeltaNet evidence require-footprints")
            requireConfluence <- parseBoolean(node.attrs.get("require-confluence"), "DeltaNet evidence require-confluence")
            bindFoundationRoot <- parseBoolean(node.attrs.get("bind-foundation-root"), "DeltaNet evidence bind-foundation-root")
            bindPolicyContent <- parseBoolean(node.attrs.get("bind-policy-content"), "DeltaNet evidence bind-policy-content")
          yield DeltaNetEvidencePolicy(
            encoding,
            hash,
            stateRoot,
            roundOrder,
            agentOrder,
            verification,
            requireFootprints,
            requireConfluence,
            bindFoundationRoot,
            bindPolicyContent
          )
        case Some(node) => Left(s"${PolicyEntity.value} is ${node.kind}, not deltanet.evidence-policy")
        case None => Left(s"missing ${PolicyEntity.value}")

    def policyContentId(graph: Graph): Either[String, ContentId] =
      graph.entities.get(PolicyEntity).toRight(s"missing ${PolicyEntity.value}")

    def definitionErrors(graph: Graph): Vector[String] =
      val errors = Vector.newBuilder[String]
      val policyPresent = graph.entity(PolicyEntity).isDefined
      val evidenceComponentsPresent = ComponentEntities.exists(entity => graph.entity(entity).isDefined)

      if evidenceComponentsPresent && !policyPresent then
        errors += s"DeltaNet evidence components exist without ${PolicyEntity.value}"

      if policyPresent then
        policy(graph) match
          case Left(error) => errors += s"invalid DeltaNet evidence policy: $error"
          case Right(p) =>
            if p.encoding != "canonical-v1" then errors += s"DeltaNet evidence encoding must be canonical-v1, found ${p.encoding}"
            if p.hash != "sha256" then errors += s"DeltaNet evidence hash must be sha256, found ${p.hash}"
            if p.stateRoot != "observable-state-v1" then errors += s"DeltaNet evidence state-root must be observable-state-v1, found ${p.stateRoot}"
            if p.roundOrder != "stable-index" then errors += s"DeltaNet evidence round-order must be stable-index, found ${p.roundOrder}"
            if p.agentOrder != "stable-agent-id" then errors += s"DeltaNet evidence agent-order must be stable-agent-id, found ${p.agentOrder}"
            if p.verification != "replay-exact" then errors += s"DeltaNet evidence verification must be replay-exact, found ${p.verification}"
            if !p.requireFootprints then errors += "DeltaNet evidence must require footprints"
            if !p.requireConfluence then errors += "DeltaNet evidence must require confluence"
            if !p.bindFoundationRoot then errors += "DeltaNet evidence must bind the foundation root"
            if !p.bindPolicyContent then errors += "DeltaNet evidence must bind the policy content id"

        ComponentEntities.toVector.sortBy(_.value).foreach { entity =>
          graph.entity(entity) match
            case Some(node) if node.kind == "deltanet.evidence-component" => ()
            case Some(node) => errors += s"DeltaNet evidence component ${entity.value} has kind ${node.kind}"
            case None => errors += s"DeltaNet evidence policy lacks component ${entity.value}"
        }

        if !DeltaNetParallelRules.enabled(graph) then errors += "DeltaNet evidence requires F9 parallel scheduling"
        if !DeltaNetRuntimeRules.enabled(graph) then errors += "DeltaNet evidence requires F8 independent reduction"

      errors.result()

    private def required(node: Node, key: String): Either[String, String] =
      node.attrs.get(key).filter(_.nonEmpty).toRight(s"DeltaNet evidence policy lacks $key")

    private def parseBoolean(value: Option[String], label: String): Either[String, Boolean] = value match
      case Some("true") => Right(true)
      case Some("false") => Right(false)
      case Some(other) => Left(s"$label must be true or false, found $other")
      case None => Left(s"missing $label")

  def validate(graph: Graph): Vector[String] =
    val errors = Vector.newBuilder[String]
    errors ++= ResourceRules.definitionErrors(graph)
    errors ++= ProcessRules.definitionErrors(graph)
    errors ++= MachineRules.definitionErrors(graph)
    errors ++= EqualityRules.definitionErrors(graph)
    errors ++= DeltaNetRules.definitionErrors(graph)
    errors ++= DeltaNetRuntimeRules.definitionErrors(graph)
    errors ++= DeltaNetParallelRules.definitionErrors(graph)
    errors ++= DeltaNetEvidenceRules.definitionErrors(graph)

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
