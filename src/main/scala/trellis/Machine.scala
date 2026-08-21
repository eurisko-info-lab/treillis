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
