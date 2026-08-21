package trellis

import scala.collection.immutable.Queue
import trellis.Core.Mode

/**
 * Small CESK-R-flavoured reference resource machine.
 *
 * It intentionally executes a tiny instruction vocabulary. The eventual Trellis
 * graph will define/lower richer semantics into this trusted substrate.
 */
object Machine:
  enum Owner:
    case Process(name: String)
    case Channel(name: String)

  final case class Resource(name: String, mode: Mode, owner: Owner, sharedLoans: Set[String] = Set.empty, mutableLoan: Option[String] = None)

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

  final case class State(
      process: String = "main",
      resources: Map[String, Resource] = Map.empty,
      channels: Map[String, Queue[String]] = Map.empty,
      trace: Vector[String] = Vector.empty
  )

  def run(program: Vector[Instr], initial: State = State()): Either[String, State] =
    program.foldLeft[Either[String, State]](Right(initial))((s, i) => s.flatMap(step(_, i)))

  def step(s: State, i: Instr): Either[String, State] = i match
    case Instr.Alloc(name, mode) =>
      if s.resources.contains(name) then Left(s"resource $name already exists")
      else Right(s.copy(resources = s.resources.updated(name, Resource(name, mode, Owner.Process(s.process))), trace = s.trace :+ s"alloc $name -> ${s.process}"))

    case Instr.Move(name, to) =>
      owned(s, name).flatMap { r =>
        ensureNoLoans(r).map { _ =>
          s.copy(resources = s.resources.updated(name, r.copy(owner = Owner.Process(to))), trace = s.trace :+ s"move $name -> $to")
        }
      }

    case Instr.BorrowShared(name, loan) =>
      owned(s, name).flatMap { r =>
        if r.mutableLoan.nonEmpty then Left(s"cannot shared-borrow $name during mutable loan")
        else Right(s.copy(resources = s.resources.updated(name, r.copy(sharedLoans = r.sharedLoans + loan)), trace = s.trace :+ s"borrow-shared $name as $loan"))
      }

    case Instr.BorrowMut(name, loan) =>
      owned(s, name).flatMap { r =>
        if r.mutableLoan.nonEmpty || r.sharedLoans.nonEmpty then Left(s"cannot mutably borrow $name while borrowed")
        else Right(s.copy(resources = s.resources.updated(name, r.copy(mutableLoan = Some(loan))), trace = s.trace :+ s"borrow-mut $name as $loan"))
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
          if r.mode == Mode.Linear then Left(s"linear resource $name requires explicit protocol completion")
          else Right(s.copy(resources = s.resources - name, trace = s.trace :+ s"drop $name"))
        }
      }

    case Instr.NewChannel(name) =>
      Right(s.copy(channels = s.channels.updated(name, Queue.empty), trace = s.trace :+ s"channel $name"))

    case Instr.Send(channel, resource) =>
      for
        r <- owned(s, resource)
        _ <- ensureNoLoans(r)
        q <- s.channels.get(channel).toRight(s"unknown channel $channel")
      yield s.copy(
        resources = s.resources.updated(resource, r.copy(owner = Owner.Channel(channel))),
        channels = s.channels.updated(channel, q.enqueue(resource)),
        trace = s.trace :+ s"send $resource -> $channel"
      )

    case Instr.Recv(channel, into) =>
      s.channels.get(channel).toRight(s"unknown channel $channel").flatMap { q =>
        q.dequeueOption match
          case None => Left(s"channel $channel empty")
          case Some((resource, rest)) =>
            s.resources.get(resource).toRight(s"channel refers to missing resource $resource").map { r =>
              s.copy(
                resources = s.resources.updated(resource, r.copy(owner = Owner.Process(into))),
                channels = s.channels.updated(channel, rest),
                trace = s.trace :+ s"recv $resource -> $into"
              )
            }
      }

  private def owned(s: State, name: String): Either[String, Resource] =
    s.resources.get(name).toRight(s"unknown resource $name").flatMap { r =>
      r.owner match
        case Owner.Process(p) if p == s.process => Right(r)
        case Owner.Process(p) => Left(s"resource $name owned by process $p")
        case Owner.Channel(c) => Left(s"resource $name owned by channel $c")
    }

  private def ensureNoLoans(r: Resource): Either[String, Unit] =
    if r.sharedLoans.nonEmpty || r.mutableLoan.nonEmpty then Left(s"resource ${r.name} is borrowed") else Right(())
