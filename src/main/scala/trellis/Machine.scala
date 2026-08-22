package trellis

import scala.collection.immutable.Queue
import trellis.Core.*
import trellis.Check.{MachineAction, ProcessDisposition}

/**
 * Small CESK-R-flavoured reference resource/process machine.
 *
 * Scala provides generic process tables, queues, resource bookkeeping, and a
 * tiny trusted transition primitive set. F4 graph data dispatches machine
 * instructions to those primitives; F2/F3 continue to define resource/process
 * policy beneath them.
 */
object Machine:
  enum Owner:
    case Process(name: String)
    case Channel(name: String)
    case Shared(processes: Set[String])

  enum ProcessStatus:
    case Running
    case Blocked(channel: String)
    case Terminated(result: Option[String])

  final case class Resource(
      name: String,
      mode: Mode,
      owner: Owner,
      kind: String = "resource",
      sharedLoans: Set[String] = Set.empty,
      mutableLoan: Option[String] = None
  )

  enum Instr:
    case Alloc(name: String, mode: Mode)
    case Move(name: String, toProcess: String)
    case BorrowShared(name: String, loan: String)
    case BorrowMut(name: String, loan: String)
    case EndBorrow(loan: String)
    case Drop(name: String)
    case NewChannel(name: String)
    case Send(channel: String, resource: String)
    case Recv(channel: String, intoProcess: String)
    case Spawn(child: String, captures: Vector[String])
    case Terminate(process: String, result: Option[String])
    case Join(child: String, intoProcess: String)

  final case class State(
      process: String = "main",
      processes: Map[String, ProcessStatus] = Map("main" -> ProcessStatus.Running),
      resources: Map[String, Resource] = Map.empty,
      channels: Map[String, Queue[String]] = Map.empty,
      waiting: Map[String, Queue[String]] = Map.empty,
      trace: Vector[String] = Vector.empty
  )

  def run(program: Vector[Instr], initial: State = State(), graph: Graph = Bootstrap.graph): Either[String, State] =
    program.foldLeft[Either[String, State]](Right(initial))((s, i) => s.flatMap(step(_, i, graph)))

  /**
   * F4 rule-driven dispatcher. The graph selects a trusted primitive action for
   * each instruction kind; Scala only executes that small primitive set.
   */
  def step(s: State, i: Instr, graph: Graph = Bootstrap.graph): Either[String, State] =
    for
      rule <- Check.MachineRules.forInstruction(graph, instructionKey(i)).toRight(
        s"no F4 machine rule for ${instructionKey(i)}"
      )
      next <- executeRule(s, i, rule.action, graph)
    yield next

  /** Pre-F4 direct dispatcher retained as a parity oracle for bootstrap closure. */
  def runDirect(program: Vector[Instr], initial: State = State(), graph: Graph = Bootstrap.f3): Either[String, State] =
    program.foldLeft[Either[String, State]](Right(initial))((s, i) => s.flatMap(stepDirect(_, i, graph)))

  def stepDirect(s: State, i: Instr, graph: Graph = Bootstrap.f3): Either[String, State] = i match
    case Instr.Alloc(name, mode) =>
      if s.resources.contains(name) then Left(s"resource $name already exists")
      else Right(s.copy(
        resources = s.resources.updated(name, Resource(name, mode, Owner.Process(s.process))),
        trace = s.trace :+ s"alloc $name -> ${s.process}"
      ))

    case Instr.Move(name, to) =>
      owned(s, name).flatMap { r =>
        ensureNoLoans(r).map { _ =>
          s.copy(
            resources = s.resources.updated(name, r.copy(owner = Owner.Process(to))),
            processes = ensureProcess(s.processes, to),
            trace = s.trace :+ s"move $name -> $to"
          )
        }
      }

    case Instr.BorrowShared(name, loan) =>
      owned(s, name).flatMap { r =>
        if r.mutableLoan.nonEmpty then Left(s"cannot shared-borrow $name during mutable loan")
        else Right(s.copy(
          resources = s.resources.updated(name, r.copy(sharedLoans = r.sharedLoans + loan)),
          trace = s.trace :+ s"borrow-shared $name as $loan"
        ))
      }

    case Instr.BorrowMut(name, loan) =>
      owned(s, name).flatMap { r =>
        if r.mutableLoan.nonEmpty || r.sharedLoans.nonEmpty then Left(s"cannot mutably borrow $name while borrowed")
        else Right(s.copy(
          resources = s.resources.updated(name, r.copy(mutableLoan = Some(loan))),
          trace = s.trace :+ s"borrow-mut $name as $loan"
        ))
      }

    case Instr.EndBorrow(loan) =>
      s.resources.find { case (_, r) => r.mutableLoan.contains(loan) || r.sharedLoans.contains(loan) } match
        case None => Left(s"unknown loan $loan")
        case Some((name, r)) =>
          val next = r.copy(sharedLoans = r.sharedLoans - loan, mutableLoan = r.mutableLoan.filterNot(_ == loan))
          Right(s.copy(resources = s.resources.updated(name, next), trace = s.trace :+ s"end-borrow $loan"))

    case Instr.Drop(name) =>
      owned(s, name).flatMap { r =>
        ensureNoLoans(r).flatMap { _ =>
          r.owner match
            case Owner.Shared(_) => Left(s"cannot uniquely drop shared resource $name")
            case _ if r.mode == Mode.Linear => Left(s"linear resource $name requires explicit protocol completion")
            case _ => Right(s.copy(resources = s.resources - name, trace = s.trace :+ s"drop $name"))
        }
      }

    case Instr.NewChannel(name) =>
      for
        disposition <- processDecision(graph, "process.new-channel", None)
        _ <- expect(disposition == ProcessDisposition.CreateChannel, "F3 process.new-channel rule must create a channel")
        _ <- expect(!s.channels.contains(name), s"channel $name already exists")
        sendMode <- Check.ProcessRules.endpointMode(graph, EntityId("process.capability.send"))
        recvMode <- Check.ProcessRules.endpointMode(graph, EntityId("process.capability.recv"))
        handleNames = Set(s"$name.send", s"$name.recv")
        _ <- expect(handleNames.forall(n => !s.resources.contains(n)), s"channel endpoint resource collision for $name")
      yield s.copy(
        channels = s.channels.updated(name, Queue.empty),
        waiting = s.waiting.updated(name, Queue.empty),
        resources = s.resources
          .updated(s"$name.send", Resource(s"$name.send", sendMode, Owner.Process(s.process), "process.send"))
          .updated(s"$name.recv", Resource(s"$name.recv", recvMode, Owner.Process(s.process), "process.recv")),
        trace = s.trace :+ s"channel $name"
      )

    case Instr.Send(channel, resource) =>
      for
        r <- owned(s, resource)
        _ <- ensureNoLoans(r)
        _ <- s.channels.get(channel).toRight(s"unknown channel $channel")
        disposition <- processDecision(graph, "process.send", Some(r.mode))
        next <- send(s, channel, r, disposition)
      yield next

    case Instr.Recv(channel, into) =>
      for
        disposition <- processDecision(graph, "process.receive", None)
        _ <- expect(disposition == ProcessDisposition.TransferToProcess, "F3 process.receive rule must transfer to a process")
        q <- s.channels.get(channel).toRight(s"unknown channel $channel")
        next <- q.dequeueOption match
          case Some((resource, rest)) => deliverQueued(s, channel, resource, rest, into)
          case None => Right(blockReceiver(s, channel, into))
      yield next

    case Instr.Spawn(child, captures) => spawn(s, child, captures, graph)

    case Instr.Terminate(process, result) => terminate(s, process, result, graph)

    case Instr.Join(child, into) => join(s, child, into, graph)

  private def instructionKey(i: Instr): String = i match
    case Instr.Alloc(_, _) => "alloc"
    case Instr.Move(_, _) => "move"
    case Instr.BorrowShared(_, _) => "borrow-shared"
    case Instr.BorrowMut(_, _) => "borrow-mut"
    case Instr.EndBorrow(_) => "end-borrow"
    case Instr.Drop(_) => "drop"
    case Instr.NewChannel(_) => "new-channel"
    case Instr.Send(_, _) => "send"
    case Instr.Recv(_, _) => "receive"
    case Instr.Spawn(_, _) => "spawn"
    case Instr.Terminate(_, _) => "terminate"
    case Instr.Join(_, _) => "join"

  private def executeRule(s: State, i: Instr, action: MachineAction, graph: Graph): Either[String, State] =
    (action, i) match
      case (MachineAction.AllocateOwned, Instr.Alloc(_, _)) => stepDirect(s, i, graph)
      case (MachineAction.MoveOwner, Instr.Move(_, _)) => stepDirect(s, i, graph)
      case (MachineAction.BeginSharedLoan, Instr.BorrowShared(_, _)) => stepDirect(s, i, graph)
      case (MachineAction.BeginMutableLoan, Instr.BorrowMut(_, _)) => stepDirect(s, i, graph)
      case (MachineAction.EndLoan, Instr.EndBorrow(_)) => stepDirect(s, i, graph)
      case (MachineAction.DropOwned, Instr.Drop(_)) => stepDirect(s, i, graph)
      case (MachineAction.ProcessDispatch, Instr.NewChannel(_)) => stepDirect(s, i, graph)
      case (MachineAction.ProcessDispatch, Instr.Send(_, _)) => stepDirect(s, i, graph)
      case (MachineAction.ProcessDispatch, Instr.Recv(_, _)) => stepDirect(s, i, graph)
      case (MachineAction.ProcessDispatch, Instr.Spawn(_, _)) => stepDirect(s, i, graph)
      case (MachineAction.ProcessDispatch, Instr.Terminate(_, _)) => stepDirect(s, i, graph)
      case (MachineAction.ProcessDispatch, Instr.Join(_, _)) => stepDirect(s, i, graph)
      case _ => Left(s"F4 action $action is incompatible with ${instructionKey(i)}")

  private def send(s: State, channel: String, r: Resource, disposition: ProcessDisposition): Either[String, State] =
    val waiters = s.waiting.getOrElse(channel, Queue.empty)
    waiters.dequeueOption match
      case Some((receiver, remaining)) =>
        val ownerResult = disposition match
          case ProcessDisposition.CopyToChannel => shareWith(r.owner, s.process, receiver)
          case ProcessDisposition.TransferToChannel => Right(Owner.Process(receiver))
          case other => Left(s"invalid send disposition $other")
        ownerResult.map { owner =>
          s.copy(
            resources = s.resources.updated(r.name, r.copy(owner = owner)),
            processes = ensureProcess(s.processes, receiver).updated(receiver, ProcessStatus.Running),
            waiting = s.waiting.updated(channel, remaining),
            trace = s.trace :+ s"send ${r.name} -> $channel -> $receiver"
          )
        }
      case None =>
        disposition match
          case ProcessDisposition.CopyToChannel =>
            val q = s.channels(channel).enqueue(r.name)
            Right(s.copy(channels = s.channels.updated(channel, q), trace = s.trace :+ s"send-copy ${r.name} -> $channel"))
          case ProcessDisposition.TransferToChannel =>
            val q = s.channels(channel).enqueue(r.name)
            Right(s.copy(
              resources = s.resources.updated(r.name, r.copy(owner = Owner.Channel(channel))),
              channels = s.channels.updated(channel, q),
              trace = s.trace :+ s"send ${r.name} -> $channel"
            ))
          case other => Left(s"invalid send disposition $other")

  private def deliverQueued(
      s: State,
      channel: String,
      resource: String,
      rest: Queue[String],
      into: String
  ): Either[String, State] =
    s.resources.get(resource).toRight(s"channel refers to missing resource $resource").flatMap { r =>
      val ownerResult = r.mode match
        case Mode.Unrestricted => shareWith(r.owner, s.process, into)
        case Mode.Affine | Mode.Linear =>
          r.owner match
            case Owner.Channel(c) if c == channel => Right(Owner.Process(into))
            case other => Left(s"resource $resource is queued on $channel but owned by $other")
      ownerResult.map { owner =>
        s.copy(
          resources = s.resources.updated(resource, r.copy(owner = owner)),
          channels = s.channels.updated(channel, rest),
          processes = ensureProcess(s.processes, into).updated(into, ProcessStatus.Running),
          trace = s.trace :+ s"recv $resource -> $into"
        )
      }
    }

  private def blockReceiver(s: State, channel: String, into: String): State =
    val waiters = s.waiting.getOrElse(channel, Queue.empty)
    s.copy(
      processes = ensureProcess(s.processes, into).updated(into, ProcessStatus.Blocked(channel)),
      waiting = s.waiting.updated(channel, waiters.enqueue(into)),
      trace = s.trace :+ s"block $into <- $channel"
    )

  private def spawn(s: State, child: String, captures: Vector[String], graph: Graph): Either[String, State] =
    if s.processes.contains(child) then Left(s"process $child already exists")
    else
      val handleName = s"$child.handle"
      for
        _ <- expect(!s.resources.contains(handleName), s"process handle $handleName already exists")
        handleMode <- Check.ProcessRules.endpointMode(graph, EntityId("process.capability.handle"))
        captured <- captures.foldLeft[Either[String, Map[String, Resource]]](Right(s.resources)) { (acc, name) =>
          acc.flatMap { resources =>
            resources.get(name).toRight(s"unknown resource $name").flatMap { r =>
              accessibleBy(r, s.process).flatMap { _ =>
                ensureNoLoans(r).flatMap { _ =>
                  processDecision(graph, "process.spawn", Some(r.mode)).flatMap {
                    case ProcessDisposition.ShareWithChild =>
                      shareWith(r.owner, s.process, child).map(owner => resources.updated(name, r.copy(owner = owner)))
                    case ProcessDisposition.TransferToChild =>
                      Right(resources.updated(name, r.copy(owner = Owner.Process(child))))
                    case other => Left(s"invalid spawn disposition $other")
                  }
                }
              }
            }
          }
        }
      yield s.copy(
        processes = s.processes.updated(child, ProcessStatus.Running),
        resources = captured.updated(handleName, Resource(handleName, handleMode, Owner.Process(s.process), "process.handle")),
        trace = s.trace :+ s"spawn $child captures=${captures.mkString(",")}"
      )

  private def terminate(s: State, process: String, result: Option[String], graph: Graph): Either[String, State] =
    for
      disposition <- processDecision(graph, "process.terminate", None)
      _ <- expect(disposition == ProcessDisposition.StructuralDiscard, "F3 process.terminate rule must use structural discard")
      _ <- s.processes.get(process).toRight(s"unknown process $process")
      _ <- result match
        case None => Right(())
        case Some(name) => s.resources.get(name).toRight(s"unknown result resource $name").flatMap(accessibleBy(_, process))
      exclusive = s.resources.values.filter(r => r.owner == Owner.Process(process) && !result.contains(r.name)).toVector
      _ <- exclusive.foldLeft[Either[String, Unit]](Right(())) { (acc, r) =>
        acc.flatMap(_ => terminationAllowed(r, graph))
      }
      afterExclusive <- exclusive.foldLeft[Either[String, State]](Right(s)) { (acc, r) =>
        acc.flatMap(current => discardForTermination(current, r, graph))
      }
      afterShared = afterExclusive.resources.values.filter {
        case Resource(name, _, Owner.Shared(ps), _, _, _) => ps.contains(process) && !result.contains(name)
        case _ => false
      }.foldLeft(afterExclusive) { (current, r) =>
        r.owner match
          case Owner.Shared(ps) =>
            val remaining = ps - process
            val resources = if remaining.isEmpty then current.resources - r.name
            else if remaining.size == 1 then current.resources.updated(r.name, r.copy(owner = Owner.Process(remaining.head)))
            else current.resources.updated(r.name, r.copy(owner = Owner.Shared(remaining)))
            current.copy(resources = resources)
          case _ => current
      }
    yield afterShared.copy(
      processes = afterShared.processes.updated(process, ProcessStatus.Terminated(result)),
      trace = afterShared.trace :+ s"terminate $process result=${result.getOrElse("unit")}"
    )

  private def join(s: State, child: String, into: String, graph: Graph): Either[String, State] =
    for
      disposition <- processDecision(graph, "process.join", None)
      _ <- expect(disposition == ProcessDisposition.TransferToJoiner, "F3 process.join rule must transfer to joiner")
      status <- s.processes.get(child).toRight(s"unknown process $child")
      result <- status match
        case ProcessStatus.Terminated(value) => Right(value)
        case other => Left(s"process $child is not terminated: $other")
      handleName = s"$child.handle"
      handle <- s.resources.get(handleName).toRight(s"missing process handle $handleName")
      _ <- accessibleBy(handle, into)
      withResult <- result match
        case None => Right(s)
        case Some(name) => s.resources.get(name).toRight(s"missing child result $name").flatMap { r =>
          r.owner match
            case Owner.Process(p) if p == child => Right(s.copy(resources = s.resources.updated(name, r.copy(owner = Owner.Process(into)))))
            case Owner.Shared(ps) if ps.contains(child) =>
              val next = (ps - child) + into
              Right(s.copy(resources = s.resources.updated(name, r.copy(owner = if next.size == 1 then Owner.Process(next.head) else Owner.Shared(next)))))
            case other => Left(s"child result $name has incompatible owner $other")
        }
    yield withResult.copy(
      processes = withResult.processes - child,
      resources = withResult.resources - handleName,
      trace = withResult.trace :+ s"join $child -> $into"
    )

  private def terminationAllowed(r: Resource, graph: Graph): Either[String, Unit] =
    Check.ResourceRules.structural(graph, r.mode).discard match
      case "allow" => ensureNoLoans(r)
      case "drop" => ensureNoLoans(r)
      case "forbid" => Left(s"process cannot terminate with live ${r.mode} resource ${r.name}")
      case other => Left(s"unknown structural discard policy $other for ${r.mode}")

  private def discardForTermination(s: State, r: Resource, graph: Graph): Either[String, State] =
    Check.ResourceRules.structural(graph, r.mode).discard match
      case "allow" => Right(s.copy(resources = s.resources - r.name, trace = s.trace :+ s"erase ${r.name} on terminate"))
      case "drop" => Right(s.copy(resources = s.resources - r.name, trace = s.trace :+ s"drop ${r.name} on terminate"))
      case "forbid" => Left(s"process cannot terminate with live ${r.mode} resource ${r.name}")
      case other => Left(s"unknown structural discard policy $other for ${r.mode}")


  /**
   * F7/F8 DeltaNet bootstrap.
   *
   * F7 defines lowering and local interaction selection. F8 adds graph-defined
   * independent agent reduction. CESK-R delegation remains available only when
   * explicitly running predecessor F7, so historical foundation tests remain
   * executable while the current foundation no longer delegates reduction.
   */
  object DeltaNet:
    final case class Agent(
        id: Int,
        kind: EntityId,
        instructionKey: String,
        instruction: Instr
    )

    final case class Net(
        agents: Vector[Agent],
        trace: Vector[String] = Vector.empty
    )

    final case class Round(
        index: Int,
        agents: Vector[Agent],
        touches: Set[String]
    )

    final case class StructuralResult(
        outputs: Vector[String],
        interactions: Int,
        trace: Vector[String]
    )


    final case class RedexCertificate(
        agentId: Int,
        agentKind: EntityId,
        instructionKey: String,
        reduction: EntityId,
        touches: Vector[String]
    )

    final case class RoundCertificate(
        index: Int,
        beforeRoot: String,
        afterRoot: String,
        redexes: Vector[RedexCertificate],
        confluent: Boolean
    )

    final case class ExecutionCertificate(
        foundationRoot: String,
        evidencePolicyContent: String,
        netRoot: String,
        initialRoot: String,
        finalRoot: String,
        readbackRoot: String,
        rounds: Vector[RoundCertificate]
    )

    def lower(program: Vector[Instr], graph: Graph = Bootstrap.graph): Either[String, Net] =
      for
        policy <- Check.DeltaNetRules.policy(graph)
        _ <- expect(policy.scheduler == "stable-agent-id", s"unsupported DeltaNet scheduler ${policy.scheduler}")
        agents <- program.zipWithIndex.foldLeft[Either[String, Vector[Agent]]](Right(Vector.empty)) {
          case (acc, (instruction, index)) =>
            for
              current <- acc
              key = instructionKeyOf(instruction)
              rule <- Check.DeltaNetRules.loweringForInstruction(graph, key).toRight(s"no F7 DeltaNet lowering for $key")
              admitted <- Check.DeltaNetRules.admittedLowering(graph, rule)
              _ <- expect(admitted, s"F7 DeltaNet lowering ${rule.entity.value} is not admitted")
            yield current :+ Agent(index, rule.agent, key, instruction)
        }
      yield Net(agents, agents.map(agent => s"lower ${agent.instructionKey} -> ${agent.kind.value}"))

    def reduce(net: Net, initial: State = State(), graph: Graph = Bootstrap.graph): Either[String, State] =
      if Check.DeltaNetParallelRules.enabled(graph) then reduceParallel(net, initial, graph)
      else if Check.DeltaNetRuntimeRules.enabled(graph) then reduceIndependent(net, initial, graph)
      else reduceF7Oracle(net, initial, graph)

    /** F7 compatibility path retained only so predecessor-foundation tests remain executable. */
    private def reduceF7Oracle(net: Net, initial: State, graph: Graph): Either[String, State] =
      for
        policy <- Check.DeltaNetRules.policy(graph)
        _ <- expect(policy.scheduler == "stable-agent-id", s"unsupported DeltaNet scheduler ${policy.scheduler}")
        _ <- expect(net.agents.size <= policy.maxInteractions, s"DeltaNet interaction budget exceeded: ${net.agents.size} > ${policy.maxInteractions}")
        reduced <- net.agents.sortBy(_.id).foldLeft[Either[String, State]](Right(initial)) { (acc, agent) =>
          acc.flatMap(state => Machine.step(state, agent.instruction, graph))
        }
        _ <- expect(policy.readback == "ceskr-state", s"unsupported DeltaNet readback ${policy.readback}")
      yield reduced

    /**
     * F8 independent reducer. Agent-to-primitive selection comes only from F8
     * graph data. This path never calls Machine.step or Machine.stepDirect;
     * CESK-R is therefore an external parity oracle rather than an executor.
     */
    private def reduceIndependent(net: Net, initial: State, graph: Graph): Either[String, State] =
      for
        runtime <- Check.DeltaNetRuntimeRules.policy(graph)
        _ <- expect(runtime.executor == "independent", s"unsupported DeltaNet runtime executor ${runtime.executor}")
        _ <- expect(!runtime.delegate, "F8 DeltaNet runtime delegation is disabled")
        _ <- expect(runtime.scheduler == "stable-agent-id", s"unsupported DeltaNet runtime scheduler ${runtime.scheduler}")
        _ <- expect(runtime.readback == "ceskr-state", s"unsupported DeltaNet runtime readback ${runtime.readback}")
        _ <- expect(net.agents.size <= runtime.maxReductions, s"DeltaNet reduction budget exceeded: ${net.agents.size} > ${runtime.maxReductions}")
        reduced <- net.agents.sortBy(_.id).foldLeft[Either[String, State]](Right(initial)) { (acc, agent) =>
          acc.flatMap(state => reduceAgent(state, agent, graph))
        }
      yield reduced


    /**
     * F9 deterministic parallel reducer. A round is a maximal stable-id-ordered
     * subset whose graph-defined dynamic touch footprints are pairwise disjoint.
     * Independent agents are executed in stable order for deterministic trace
     * production, while reverse-order replay is used as a local confluence
     * check on observable state.
     */
    private def reduceParallel(net: Net, initial: State, graph: Graph): Either[String, State] =
      parallelPlan(net, initial, graph).map(_._2)

    def schedule(
        net: Net,
        initial: State = State(),
        graph: Graph = Bootstrap.graph
    ): Either[String, Vector[Round]] =
      parallelPlan(net, initial, graph).map(_._1)

    def roundConfluent(
        initial: State,
        round: Round,
        graph: Graph = Bootstrap.graph
    ): Either[String, Boolean] =
      for
        stable <- reduceRound(initial, round.agents.sortBy(_.id), graph)
        reverse <- reduceRound(initial, round.agents.sortBy(_.id).reverse, graph)
      yield observational(stable) == observational(reverse)

    def footprint(
        agent: Agent,
        state: State = State(),
        graph: Graph = Bootstrap.graph
    ): Either[String, Set[String]] =
      for
        profile <- Check.DeltaNetParallelRules.profileForAgent(graph, agent.kind).toRight(
          s"no F9 DeltaNet parallel profile for ${agent.kind.value}"
        )
        admitted <- Check.DeltaNetParallelRules.admitted(graph, profile)
        _ <- expect(admitted, s"F9 DeltaNet parallel profile ${profile.entity.value} is not admitted")
        fields = instructionFields(agent.instruction)
        keys <- profile.touches.foldLeft[Either[String, Set[String]]](Right(Set.empty)) { (acc, selector) =>
          for
            current <- acc
            resolved <- resolveTouch(selector, fields, state)
          yield current ++ resolved
        }
      yield keys

    /** Canonical F10 content address of the lowered DeltaNet program. */
    def netRoot(net: Net): ContentId =
      ContentId(Canon.sha256(encodeNet(net)))

    /** Canonical F10 content address of observable machine state; traces are not semantic state. */
    def observableStateRoot(state: State): ContentId =
      ContentId(Canon.sha256(encodeObservableState(state)))

    def encodeCertificate(certificate: ExecutionCertificate): String =
      val rounds = certificate.rounds.sortBy(_.index).map(encodeRoundCertificate)
      Canon.record(
        "execution-certificate",
        Vector(
          certificate.foundationRoot,
          certificate.evidencePolicyContent,
          certificate.netRoot,
          certificate.initialRoot,
          certificate.finalRoot,
          certificate.readbackRoot,
          Canon.record("rounds", rounds)
        )
      )

    def certificateId(certificate: ExecutionCertificate): ContentId =
      ContentId(Canon.sha256(encodeCertificate(certificate)))

    def decodeCertificate(text: String): Either[String, ExecutionCertificate] =
      for
        parts <- Canon.fixed(text, "execution-certificate", 7)
        _ <- validateCertificateHash(parts(0), "foundation root")
        _ <- validateCertificateHash(parts(1), "evidence policy content id")
        _ <- validateCertificateHash(parts(2), "net root")
        _ <- validateCertificateHash(parts(3), "initial state root")
        _ <- validateCertificateHash(parts(4), "final state root")
        _ <- validateCertificateHash(parts(5), "readback root")
        roundTexts <- Canon.fields(parts(6), "rounds")
        rounds <- sequenceEither(roundTexts.map(decodeRoundCertificate))
        _ <- expect(rounds.map(_.index) == rounds.indices.toVector, "F10 round indexes are not canonical")
        certificate = ExecutionCertificate(parts(0), parts(1), parts(2), parts(3), parts(4), parts(5), rounds)
        _ <- expect(encodeCertificate(certificate) == text, "non-canonical F10 execution certificate")
      yield certificate

    def decodeCertificateBytes(bytes: Array[Byte]): Either[String, ExecutionCertificate] =
      for
        text <- Canon.decodeUtf8(bytes)
        certificate <- decodeCertificate(text)
        _ <- expect(
          java.util.Arrays.equals(encodeCertificate(certificate).getBytes(java.nio.charset.StandardCharsets.UTF_8), bytes),
          "non-canonical F10 execution certificate bytes"
        )
      yield certificate

    private def decodeRoundCertificate(text: String): Either[String, RoundCertificate] =
      for
        parts <- Canon.fixed(text, "round-certificate", 5)
        index <- parts(0).toIntOption.filter(_ >= 0).toRight(s"invalid F10 round index ${parts(0)}")
        _ <- validateCertificateHash(parts(1), "round before root")
        _ <- validateCertificateHash(parts(2), "round after root")
        redexTexts <- Canon.fields(parts(3), "redexes")
        redexes <- sequenceEither(redexTexts.map(decodeRedexCertificate))
        redexIds = redexes.map(_.agentId)
        _ <- expect(redexIds == redexIds.sorted, "F10 redexes are not in canonical agent order")
        _ <- expect(redexIds.distinct.size == redexIds.size, "F10 redex certificate contains duplicate agents")
        confluent <- parts(4) match
          case "true" => Right(true)
          case "false" => Right(false)
          case other => Left(s"invalid F10 confluence flag $other")
      yield RoundCertificate(index, parts(1), parts(2), redexes, confluent)

    private def decodeRedexCertificate(text: String): Either[String, RedexCertificate] =
      for
        parts <- Canon.fixed(text, "redex-certificate", 5)
        agentId <- parts(0).toIntOption.filter(_ >= 0).toRight(s"invalid F10 agent id ${parts(0)}")
        _ <- expect(parts(1).nonEmpty, "empty F10 agent kind")
        _ <- expect(parts(2).nonEmpty, "empty F10 instruction key")
        _ <- expect(parts(3).nonEmpty, "empty F10 reduction entity")
        touches <- Canon.fields(parts(4), "touches")
        _ <- expect(touches == touches.sorted, "F10 touch keys are not in canonical order")
        _ <- expect(touches.distinct.size == touches.size, "F10 touch keys contain duplicates")
      yield RedexCertificate(agentId, EntityId(parts(1)), parts(2), EntityId(parts(3)), touches)

    private def validateCertificateHash(value: String, label: String): Either[String, Unit] =
      Canon.validateHash(value, s"F10 $label")

    private def sequenceEither[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
      values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) { (acc, value) =>
        for
          current <- acc
          next <- value
        yield current :+ next
      }

    /**
     * F10 canonical execution evidence. Scheduling and reduction are replayed
     * from F9/F8 graph data; the resulting certificate binds the full current
     * foundation, evidence policy content, lowered net, dynamic footprints,
     * and every observable before/after state root.
     */
    def certify(
        program: Vector[Instr],
        initial: State = State(),
        graph: Graph = Bootstrap.graph
    ): Either[String, ExecutionCertificate] =
      for
        policy <- Check.DeltaNetEvidenceRules.policy(graph)
        _ <- expect(policy.encoding == "canonical-v1", s"unsupported F10 evidence encoding ${policy.encoding}")
        _ <- expect(policy.hash == "sha256", s"unsupported F10 evidence hash ${policy.hash}")
        _ <- expect(policy.stateRoot == "observable-state-v1", s"unsupported F10 state-root ${policy.stateRoot}")
        _ <- expect(policy.roundOrder == "stable-index", s"unsupported F10 round order ${policy.roundOrder}")
        _ <- expect(policy.agentOrder == "stable-agent-id", s"unsupported F10 agent order ${policy.agentOrder}")
        _ <- expect(policy.verification == "replay-exact", s"unsupported F10 verification ${policy.verification}")
        net <- lower(program, graph)
        plan <- parallelPlan(net, initial, graph)
        rounds = plan._1
        finalState = plan._2
        built <- buildRoundCertificates(rounds, initial, policy, graph)
        roundCertificates = built._1
        replayed = built._2
        _ <- expect(observational(replayed) == observational(finalState), "F10 certificate replay diverged from F9 parallel execution")
        policyContent <- Check.DeltaNetEvidenceRules.policyContentId(graph)
        foundationRoot = Canon.graphId(graph).value
        initialRoot = observableStateRoot(initial).value
        finalRoot = observableStateRoot(finalState).value
        certificate = ExecutionCertificate(
          foundationRoot,
          policyContent.value,
          netRoot(net).value,
          initialRoot,
          finalRoot,
          finalRoot,
          roundCertificates
        )
      yield certificate

    /** Strict replay verifier for an F10 certificate and its supplied program. */
    def verifyCertificate(
        program: Vector[Instr],
        certificate: ExecutionCertificate,
        initial: State = State(),
        graph: Graph = Bootstrap.graph
    ): Either[String, State] =
      for
        expected <- certify(program, initial, graph)
        _ <- expect(
          encodeCertificate(expected) == encodeCertificate(certificate),
          s"F10 execution certificate mismatch: expected ${certificateId(expected).value}, found ${certificateId(certificate).value}"
        )
        net <- lower(program, graph)
        result <- parallelPlan(net, initial, graph).map(_._2)
      yield result

    private def buildRoundCertificates(
        rounds: Vector[Round],
        initial: State,
        policy: Check.DeltaNetEvidencePolicy,
        graph: Graph
    ): Either[String, (Vector[RoundCertificate], State)] =
      rounds.foldLeft[Either[String, (Vector[RoundCertificate], State)]](Right(Vector.empty -> initial)) {
        case (acc, round) =>
          for
            pair <- acc
            certificates = pair._1
            state = pair._2
            _ <- expect(round.index == certificates.size, s"F10 round index ${round.index} is not canonical")
            confluent <- roundConfluent(state, round, graph)
            _ <- expect(!policy.requireConfluence || confluent, s"F10 round ${round.index} lacks required confluence evidence")
            redexes <- round.agents.sortBy(_.id).foldLeft[Either[String, Vector[RedexCertificate]]](Right(Vector.empty)) {
              (redexAcc, agent) =>
                for
                  current <- redexAcc
                  rule <- Check.DeltaNetRuntimeRules.ruleForAgent(graph, agent.kind).toRight(
                    s"no F8 DeltaNet reduction for ${agent.kind.value}"
                  )
                  touches <- footprint(agent, state, graph).map(_.toVector.sorted)
                  _ <- expect(!policy.requireFootprints || touches.nonEmpty, s"F10 redex ${agent.id} lacks required footprint evidence")
                yield current :+ RedexCertificate(agent.id, agent.kind, agent.instructionKey, rule.entity, touches)
            }
            before = observableStateRoot(state).value
            next <- reduceRound(state, round.agents.sortBy(_.id), graph)
            after = observableStateRoot(next).value
            certificate = RoundCertificate(round.index, before, after, redexes, confluent)
          yield (certificates :+ certificate) -> next
      }

    private def encodeRoundCertificate(round: RoundCertificate): String =
      Canon.record(
        "round-certificate",
        Vector(
          round.index.toString,
          round.beforeRoot,
          round.afterRoot,
          Canon.record("redexes", round.redexes.sortBy(_.agentId).map(encodeRedexCertificate)),
          round.confluent.toString
        )
      )

    private def encodeRedexCertificate(redex: RedexCertificate): String =
      Canon.record(
        "redex-certificate",
        Vector(
          redex.agentId.toString,
          redex.agentKind.value,
          redex.instructionKey,
          redex.reduction.value,
          Canon.record("touches", redex.touches.sorted)
        )
      )

    private def encodeNet(net: Net): String =
      Canon.record(
        "deltanet",
        net.agents.sortBy(_.id).map { agent =>
          Canon.record(
            "agent",
            Vector(agent.id.toString, agent.kind.value, agent.instructionKey, encodeInstruction(agent.instruction))
          )
        }
      )

    private def encodeInstruction(instruction: Instr): String = instruction match
      case Instr.Alloc(name, mode) => Canon.record("alloc", Vector(name, Canon.encodeMode(mode)))
      case Instr.Move(name, to) => Canon.record("move", Vector(name, to))
      case Instr.BorrowShared(name, loan) => Canon.record("borrow-shared", Vector(name, loan))
      case Instr.BorrowMut(name, loan) => Canon.record("borrow-mut", Vector(name, loan))
      case Instr.EndBorrow(loan) => Canon.record("end-borrow", Vector(loan))
      case Instr.Drop(name) => Canon.record("drop", Vector(name))
      case Instr.NewChannel(name) => Canon.record("new-channel", Vector(name))
      case Instr.Send(channel, resource) => Canon.record("send", Vector(channel, resource))
      case Instr.Recv(channel, into) => Canon.record("receive", Vector(channel, into))
      case Instr.Spawn(child, captures) => Canon.record("spawn", Vector(child, Canon.record("captures", captures)))
      case Instr.Terminate(process, result) => Canon.record("terminate", Vector(process, encodeStringOption(result)))
      case Instr.Join(child, into) => Canon.record("join", Vector(child, into))

    private def encodeObservableState(state: State): String =
      val processes = state.processes.toVector.sortBy(_._1).map { case (name, status) =>
        Canon.record("process", Vector(name, encodeProcessStatus(status)))
      }
      val resources = state.resources.toVector.sortBy(_._1).map { case (_, resource) =>
        Canon.record(
          "resource",
          Vector(
            resource.name,
            Canon.encodeMode(resource.mode),
            encodeOwner(resource.owner),
            resource.kind,
            Canon.record("shared-loans", resource.sharedLoans.toVector.sorted),
            encodeStringOption(resource.mutableLoan)
          )
        )
      }
      val channels = state.channels.toVector.sortBy(_._1).map { case (name, queue) =>
        Canon.record("channel", Vector(name, Canon.record("queue", queue.iterator.toVector)))
      }
      val waiting = state.waiting.toVector.sortBy(_._1).map { case (name, queue) =>
        Canon.record("waiting", Vector(name, Canon.record("queue", queue.iterator.toVector)))
      }
      Canon.record(
        "observable-state",
        Vector(
          state.process,
          Canon.record("processes", processes),
          Canon.record("resources", resources),
          Canon.record("channels", channels),
          Canon.record("waiting", waiting)
        )
      )

    private def encodeOwner(owner: Owner): String = owner match
      case Owner.Process(name) => Canon.record("process", Vector(name))
      case Owner.Channel(name) => Canon.record("channel", Vector(name))
      case Owner.Shared(processes) => Canon.record("shared", processes.toVector.sorted)

    private def encodeProcessStatus(status: ProcessStatus): String = status match
      case ProcessStatus.Running => Canon.record("running", Vector.empty)
      case ProcessStatus.Blocked(channel) => Canon.record("blocked", Vector(channel))
      case ProcessStatus.Terminated(result) => Canon.record("terminated", Vector(encodeStringOption(result)))

    private def encodeStringOption(value: Option[String]): String = value match
      case None => Canon.record("none", Vector.empty)
      case Some(text) => Canon.record("some", Vector(text))

    private def parallelPlan(net: Net, initial: State, graph: Graph): Either[String, (Vector[Round], State)] =
      for
        policy <- Check.DeltaNetParallelRules.policy(graph)
        runtime <- Check.DeltaNetRuntimeRules.policy(graph)
        _ <- expect(runtime.executor == "independent" && !runtime.delegate, "F9 parallel execution requires the independent F8 runtime")
        _ <- expect(policy.tieBreak == "stable-agent-id", s"unsupported F9 tie-break ${policy.tieBreak}")
        _ <- expect(policy.conflict == "touch-overlap", s"unsupported F9 conflict relation ${policy.conflict}")
        _ <- expect(policy.independence == "disjoint-touch", s"unsupported F9 independence relation ${policy.independence}")
        _ <- expect(policy.confluence == "readback-equality", s"unsupported F9 confluence relation ${policy.confluence}")
        result <- loopParallel(net.agents.sortBy(_.id), initial, Vector.empty, policy, graph)
      yield result

    private def loopParallel(
        remaining: Vector[Agent],
        state: State,
        rounds: Vector[Round],
        policy: Check.DeltaNetParallelPolicy,
        graph: Graph
    ): Either[String, (Vector[Round], State)] =
      if remaining.isEmpty then Right(rounds -> state)
      else if rounds.size >= policy.maxRounds then Left(s"F9 DeltaNet parallel round budget exceeded: ${rounds.size} >= ${policy.maxRounds}")
      else
        for
          chosen <- chooseRound(remaining, state, policy, graph)
          _ <- expect(chosen.nonEmpty, "F9 DeltaNet scheduler selected an empty round")
          touchSets <- chosen.foldLeft[Either[String, Set[String]]](Right(Set.empty)) { (acc, agent) =>
            for
              current <- acc
              next <- footprint(agent, state, graph)
            yield current ++ next
          }
          round = Round(rounds.size, chosen, touchSets)
          confluent <- roundConfluent(state, round, graph)
          _ <- expect(confluent, s"F9 DeltaNet round ${round.index} is not confluent under readback equality")
          next <- reduceRound(state, chosen.sortBy(_.id), graph)
          chosenIds = chosen.map(_.id).toSet
          rest = remaining.filterNot(agent => chosenIds.contains(agent.id))
          result <- loopParallel(rest, next, rounds :+ round, policy, graph)
        yield result

    private def chooseRound(
        remaining: Vector[Agent],
        state: State,
        policy: Check.DeltaNetParallelPolicy,
        graph: Graph
    ): Either[String, Vector[Agent]] =
      policy.scheduler match
        case "singleton" =>
          remaining.sortBy(_.id).find(agent => reduceAgent(state, agent, graph).isRight) match
            case Some(agent) => Right(Vector(agent))
            case None => Left("F9 DeltaNet has no currently reducible agent")
        case "maximal-nonconflicting" =>
          remaining.sortBy(_.id).foldLeft[Either[String, (Vector[Agent], Set[String])]](Right(Vector.empty -> Set.empty)) {
            case (acc, agent) =>
              acc.flatMap { pair =>
                val (selected, used) = pair
                if reduceAgent(state, agent, graph).isLeft then Right(pair)
                else
                  footprint(agent, state, graph).map { keys =>
                    if (keys intersect used).isEmpty then (selected :+ agent, used ++ keys)
                    else pair
                  }
              }
          }.map(_._1)
        case other => Left(s"unsupported F9 DeltaNet parallel scheduler $other")

    private def reduceRound(state: State, agents: Vector[Agent], graph: Graph): Either[String, State] =
      agents.foldLeft[Either[String, State]](Right(state)) { (acc, agent) =>
        acc.flatMap(current => reduceAgent(current, agent, graph))
      }

    private def observational(state: State): State = state.copy(trace = Vector.empty)

    private def instructionFields(instruction: Instr): Map[String, Vector[String]] = instruction match
      case Instr.Alloc(name, _) => Map("name" -> Vector(name))
      case Instr.Move(name, to) => Map("name" -> Vector(name), "to" -> Vector(to))
      case Instr.BorrowShared(name, loan) => Map("name" -> Vector(name), "loan" -> Vector(loan))
      case Instr.BorrowMut(name, loan) => Map("name" -> Vector(name), "loan" -> Vector(loan))
      case Instr.EndBorrow(loan) => Map("loan" -> Vector(loan))
      case Instr.Drop(name) => Map("name" -> Vector(name))
      case Instr.NewChannel(name) => Map("name" -> Vector(name))
      case Instr.Send(channel, resource) => Map("channel" -> Vector(channel), "value" -> Vector(resource))
      case Instr.Recv(channel, into) => Map("channel" -> Vector(channel), "to" -> Vector(into))
      case Instr.Spawn(child, captures) => Map("child" -> Vector(child), "captures" -> captures)
      case Instr.Terminate(process, result) => Map("pid" -> Vector(process), "result" -> result.toVector)
      case Instr.Join(child, into) => Map("child" -> Vector(child), "to" -> Vector(into))

    private def resolveTouch(
        selector: String,
        fields: Map[String, Vector[String]],
        state: State
    ): Either[String, Set[String]] =
      val split = selector.indexOf(':')
      if split <= 0 || split == selector.length - 1 then Left(s"invalid F9 touch selector $selector")
      else
        val category = selector.take(split)
        val source = selector.drop(split + 1)
        category match
          case "loan-resource" =>
            fieldValues(source, fields).map { loans =>
              loans.flatMap { loan =>
                state.resources.valuesIterator
                  .filter(r => r.mutableLoan.contains(loan) || r.sharedLoans.contains(loan))
                  .map(r => s"resource:${r.name}")
                  .toVector
              }.toSet
            }
          case "owned-by" =>
            fieldValues(source, fields).map { processes =>
              processes.flatMap { process =>
                state.resources.valuesIterator.collect {
                  case r if r.owner == Owner.Process(process) => s"resource:${r.name}"
                  case r @ Resource(_, _, Owner.Shared(ps), _, _, _) if ps.contains(process) => s"resource:${r.name}"
                }.toVector
              }.toSet
            }
          case "result-of" =>
            fieldValues(source, fields).map { processes =>
              processes.flatMap { process =>
                state.processes.get(process).toVector.flatMap {
                  case ProcessStatus.Terminated(Some(name)) => Vector(s"resource:$name")
                  case _ => Vector.empty
                }
              }.toSet
            }
          case "waiting-process" =>
            fieldValues(source, fields).map { channels =>
              channels.flatMap(channel => state.waiting.get(channel).flatMap(_.headOption).toVector.map(p => s"process:$p")).toSet
            }
          case "queued-resource" =>
            fieldValues(source, fields).map { channels =>
              channels.flatMap(channel => state.channels.get(channel).flatMap(_.headOption).toVector.map(r => s"resource:$r")).toSet
            }
          case other =>
            fieldValues(source, fields).map(_.map(value => s"$other:$value").toSet)

    private def fieldValues(source: String, fields: Map[String, Vector[String]]): Either[String, Vector[String]] =
      if source.startsWith("=") then Right(Vector(source.drop(1)))
      else
        fields.get(source) match
          case Some(values) => Right(values)
          case None =>
            val dot = source.indexOf('.')
            if dot > 0 then
              val base = source.take(dot)
              val suffix = source.drop(dot)
              fields.get(base).map(_.map(_ + suffix)).toRight(s"F9 touch selector references unknown field $base")
            else Left(s"F9 touch selector references unknown field $source")

    private def reduceAgent(s: State, agent: Agent, graph: Graph): Either[String, State] =
      for
        rule <- Check.DeltaNetRuntimeRules.ruleForAgent(graph, agent.kind).toRight(
          s"no F8 DeltaNet reduction for ${agent.kind.value}"
        )
        admitted <- Check.DeltaNetRuntimeRules.admitted(graph, rule)
        _ <- expect(admitted, s"F8 DeltaNet reduction ${rule.entity.value} is not admitted")
        next <- executePrimitive(s, agent.instruction, rule.primitive, graph)
      yield next

    private def executePrimitive(
        s: State,
        instruction: Instr,
        primitive: Check.DeltaNetPrimitive,
        graph: Graph
    ): Either[String, State] =
      (primitive, instruction) match
        case (Check.DeltaNetPrimitive.AllocateOwned, Instr.Alloc(name, mode)) =>
          if s.resources.contains(name) then Left(s"resource $name already exists")
          else Right(s.copy(
            resources = s.resources.updated(name, Resource(name, mode, Owner.Process(s.process))),
            trace = s.trace :+ s"alloc $name -> ${s.process}"
          ))

        case (Check.DeltaNetPrimitive.MoveOwner, Instr.Move(name, to)) =>
          owned(s, name).flatMap { r =>
            ensureNoLoans(r).map { _ =>
              s.copy(
                resources = s.resources.updated(name, r.copy(owner = Owner.Process(to))),
                processes = ensureProcess(s.processes, to),
                trace = s.trace :+ s"move $name -> $to"
              )
            }
          }

        case (Check.DeltaNetPrimitive.BeginSharedLoan, Instr.BorrowShared(name, loan)) =>
          owned(s, name).flatMap { r =>
            if r.mutableLoan.nonEmpty then Left(s"cannot shared-borrow $name during mutable loan")
            else Right(s.copy(
              resources = s.resources.updated(name, r.copy(sharedLoans = r.sharedLoans + loan)),
              trace = s.trace :+ s"borrow-shared $name as $loan"
            ))
          }

        case (Check.DeltaNetPrimitive.BeginMutableLoan, Instr.BorrowMut(name, loan)) =>
          owned(s, name).flatMap { r =>
            if r.mutableLoan.nonEmpty || r.sharedLoans.nonEmpty then Left(s"cannot mutably borrow $name while borrowed")
            else Right(s.copy(
              resources = s.resources.updated(name, r.copy(mutableLoan = Some(loan))),
              trace = s.trace :+ s"borrow-mut $name as $loan"
            ))
          }

        case (Check.DeltaNetPrimitive.EndLoan, Instr.EndBorrow(loan)) =>
          s.resources.find { case (_, r) => r.mutableLoan.contains(loan) || r.sharedLoans.contains(loan) } match
            case None => Left(s"unknown loan $loan")
            case Some((name, r)) =>
              val next = r.copy(sharedLoans = r.sharedLoans - loan, mutableLoan = r.mutableLoan.filterNot(_ == loan))
              Right(s.copy(resources = s.resources.updated(name, next), trace = s.trace :+ s"end-borrow $loan"))

        case (Check.DeltaNetPrimitive.DropOwned, Instr.Drop(name)) =>
          owned(s, name).flatMap { r =>
            ensureNoLoans(r).flatMap { _ =>
              r.owner match
                case Owner.Shared(_) => Left(s"cannot uniquely drop shared resource $name")
                case _ if r.mode == Mode.Linear => Left(s"linear resource $name requires explicit protocol completion")
                case _ => Right(s.copy(resources = s.resources - name, trace = s.trace :+ s"drop $name"))
            }
          }

        case (Check.DeltaNetPrimitive.CreateChannel, Instr.NewChannel(name)) =>
          for
            disposition <- processDecision(graph, "process.new-channel", None)
            _ <- expect(disposition == ProcessDisposition.CreateChannel, "F3 process.new-channel rule must create a channel")
            _ <- expect(!s.channels.contains(name), s"channel $name already exists")
            sendMode <- Check.ProcessRules.endpointMode(graph, EntityId("process.capability.send"))
            recvMode <- Check.ProcessRules.endpointMode(graph, EntityId("process.capability.recv"))
            handleNames = Set(s"$name.send", s"$name.recv")
            _ <- expect(handleNames.forall(n => !s.resources.contains(n)), s"channel endpoint resource collision for $name")
          yield s.copy(
            channels = s.channels.updated(name, Queue.empty),
            waiting = s.waiting.updated(name, Queue.empty),
            resources = s.resources
              .updated(s"$name.send", Resource(s"$name.send", sendMode, Owner.Process(s.process), "process.send"))
              .updated(s"$name.recv", Resource(s"$name.recv", recvMode, Owner.Process(s.process), "process.recv")),
            trace = s.trace :+ s"channel $name"
          )

        case (Check.DeltaNetPrimitive.Send, Instr.Send(channel, resource)) =>
          for
            r <- owned(s, resource)
            _ <- ensureNoLoans(r)
            _ <- s.channels.get(channel).toRight(s"unknown channel $channel")
            disposition <- processDecision(graph, "process.send", Some(r.mode))
            next <- send(s, channel, r, disposition)
          yield next

        case (Check.DeltaNetPrimitive.Receive, Instr.Recv(channel, into)) =>
          for
            disposition <- processDecision(graph, "process.receive", None)
            _ <- expect(disposition == ProcessDisposition.TransferToProcess, "F3 process.receive rule must transfer to a process")
            q <- s.channels.get(channel).toRight(s"unknown channel $channel")
            next <- q.dequeueOption match
              case Some((resource, rest)) => deliverQueued(s, channel, resource, rest, into)
              case None => Right(blockReceiver(s, channel, into))
          yield next

        case (Check.DeltaNetPrimitive.Spawn, Instr.Spawn(child, captures)) => spawn(s, child, captures, graph)
        case (Check.DeltaNetPrimitive.Terminate, Instr.Terminate(process, result)) => terminate(s, process, result, graph)
        case (Check.DeltaNetPrimitive.Join, Instr.Join(child, into)) => join(s, child, into, graph)
        case _ => Left(s"F8 primitive $primitive is incompatible with ${instructionKeyOf(instruction)}")

    def run(program: Vector[Instr], initial: State = State(), graph: Graph = Bootstrap.graph): Either[String, State] =
      lower(program, graph).flatMap(net => reduce(net, initial, graph))

    def interactionAction(
        left: EntityId,
        right: EntityId,
        mode: Option[Mode],
        graph: Graph = Bootstrap.graph
    ): Option[Check.DeltaNetAction] =
      Check.DeltaNetRules.interaction(graph, left, right, mode)

    /**
     * Direct local structural interaction. `uses` is intentionally limited to
     * 0/1/2 in F7: erasure, identity wiring, or one binary replicator.
     */
    def structural(
        name: String,
        mode: Mode,
        uses: Int,
        graph: Graph = Bootstrap.graph
    ): Either[String, StructuralResult] =
      for
        policy <- Check.DeltaNetRules.structuralPolicy(graph)
        result <- uses match
          case 0 =>
            Check.DeltaNetRules.interaction(
              graph,
              policy.eraseAgent,
              EntityId("deltanet.agent-kind.value"),
              Some(mode)
            ) match
              case Some(Check.DeltaNetAction.Erase) =>
                Right(StructuralResult(Vector.empty, 1, Vector(s"erase $name")))
              case Some(Check.DeltaNetAction.Drop) =>
                Right(StructuralResult(Vector.empty, 1, Vector(s"drop $name")))
              case Some(other) => Left(s"invalid F7 eraser interaction action $other")
              case None => Left(s"no F7 eraser interaction for ${Canon.encodeMode(mode)}")
          case 1 =>
            Right(StructuralResult(Vector(name), 0, Vector(s"wire $name")))
          case 2 =>
            Check.DeltaNetRules.interaction(
              graph,
              policy.duplicateAgent,
              EntityId("deltanet.agent-kind.value"),
              Some(mode)
            ) match
              case Some(Check.DeltaNetAction.Duplicate) =>
                Right(StructuralResult(Vector(s"$name.0", s"$name.1"), 1, Vector(s"replicate $name")))
              case Some(other) => Left(s"invalid F7 replicator interaction action $other")
              case None => Left(s"no F7 replicator interaction for ${Canon.encodeMode(mode)}")
          case other => Left(s"F7 structural net supports 0, 1, or 2 uses, found $other")
      yield result

    private def instructionKeyOf(i: Instr): String = i match
      case Instr.Alloc(_, _) => "alloc"
      case Instr.Move(_, _) => "move"
      case Instr.BorrowShared(_, _) => "borrow-shared"
      case Instr.BorrowMut(_, _) => "borrow-mut"
      case Instr.EndBorrow(_) => "end-borrow"
      case Instr.Drop(_) => "drop"
      case Instr.NewChannel(_) => "new-channel"
      case Instr.Send(_, _) => "send"
      case Instr.Recv(_, _) => "receive"
      case Instr.Spawn(_, _) => "spawn"
      case Instr.Terminate(_, _) => "terminate"
      case Instr.Join(_, _) => "join"

  private def processDecision(graph: Graph, operation: String, mode: Option[Mode]): Either[String, ProcessDisposition] =
    Check.ProcessRules.decision(graph, operation, mode).toRight {
      val suffix = mode.map(m => s"/${Canon.encodeMode(m)}").getOrElse("")
      s"no F3 process rule for $operation$suffix"
    }

  private def owned(s: State, name: String): Either[String, Resource] =
    s.resources.get(name).toRight(s"unknown resource $name").flatMap { r => accessibleBy(r, s.process).map(_ => r) }

  private def accessibleBy(r: Resource, process: String): Either[String, Unit] = r.owner match
    case Owner.Process(p) if p == process => Right(())
    case Owner.Shared(ps) if ps.contains(process) => Right(())
    case Owner.Process(p) => Left(s"resource ${r.name} owned by process $p")
    case Owner.Channel(c) => Left(s"resource ${r.name} owned by channel $c")
    case Owner.Shared(ps) => Left(s"resource ${r.name} shared by ${ps.toVector.sorted.mkString(",")}")

  private def shareWith(owner: Owner, source: String, target: String): Either[String, Owner] = owner match
    case Owner.Process(p) if p == source =>
      if source == target then Right(owner) else Right(Owner.Shared(Set(source, target)))
    case Owner.Shared(ps) if ps.contains(source) => Right(Owner.Shared(ps + target))
    case Owner.Process(p) => Left(s"cannot share capability owned by process $p from $source")
    case Owner.Channel(c) => Left(s"cannot copy capability owned by channel $c")
    case Owner.Shared(ps) => Left(s"process $source does not hold shared capability ${ps.toVector.sorted.mkString(",")}")

  private def ensureNoLoans(r: Resource): Either[String, Unit] =
    if r.sharedLoans.nonEmpty || r.mutableLoan.nonEmpty then Left(s"resource ${r.name} is borrowed") else Right(())

  private def ensureProcess(processes: Map[String, ProcessStatus], name: String): Map[String, ProcessStatus] =
    if processes.contains(name) then processes else processes.updated(name, ProcessStatus.Running)

  private def expect(condition: Boolean, error: => String): Either[String, Unit] =
    if condition then Right(()) else Left(error)
