package trellis.engine.internal

import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future, blocking}
import scala.concurrent.duration.Duration
import java.util.concurrent.atomic.AtomicInteger
import trellis.Core.*

/** Native Scala execution of graph-resident `ir.*` Nat workspaces. */
object NatWorkspaceEvaluator:
  enum Engine:
    case DeltaNet, Ceskr

  final case class Run(value: BigInt, reductions: Int, rounds: Int, trace: Vector[String])
  private final case class TailCall(function: EntityId, arguments: Vector[BigInt])
  private type Outcome = BigInt | TailCall

  def run(graph: Graph, workspace: EntityId, arguments: Vector[BigInt], engine: Engine): Either[String, Run] =
    new Interpreter(graph, workspace, engine).run(arguments)

  private final class Interpreter(graph: Graph, workspace: EntityId, engine: Engine):
    private val entityByNode = graph.entities.iterator.map(_.swap).toMap
    private val reductions = AtomicInteger(0)
    private val rounds = AtomicInteger(0)
    private val trace = mutable.ArrayBuffer.empty[String]

    private def node(entity: EntityId): Either[String, Node] =
      graph.entity(entity).toRight(s"unknown workspace IR entity ${entity.value}")

    private def child(entity: EntityId, role: String): Either[String, EntityId] =
      graph.entities.get(entity).toRight(s"unknown workspace IR entity ${entity.value}").flatMap { content =>
        val matches = graph.edges.valuesIterator.filter(edge => edge.from.node == content && edge.role == role).toVector
        matches match
          case Vector(edge) => entityByNode.get(edge.to.node).toRight(s"workspace edge $role targets an unbound node")
          case Vector() => Left(s"${entity.value} lacks $role")
          case _ => Left(s"${entity.value} has ambiguous $role")
      }

    private def children(entity: EntityId, prefix: String): Vector[(String, EntityId)] =
      graph.entities.get(entity).toVector.flatMap { content =>
        graph.edges.valuesIterator.filter(edge => edge.from.node == content && edge.role.startsWith(prefix)).flatMap { edge =>
          entityByNode.get(edge.to.node).map(edge.role -> _)
        }
      }.sortBy(_._1)

    private lazy val definition = child(workspace, "definition")
    private lazy val functions: Either[String, Map[String, EntityId]] =
      definition.flatMap { root =>
        val reachable = mutable.Set.empty[EntityId]
        val queue = mutable.Queue(root)
        while queue.nonEmpty do
          val current = queue.dequeue()
          if reachable.add(current) then children(current, "").foreach { case (_, next) => queue.enqueue(next) }
        val entries = reachable.toVector.flatMap { entity =>
          graph.entity(entity).filter(_.kind == "ir.function").flatMap(_.attrs.get("name")).map(_ -> entity)
        }
        entries.groupBy(_._1).collectFirst { case (name, values) if values.size > 1 => name } match
          case Some(name) => Left(s"ambiguous workspace function $name")
          case None => Right(entries.toMap)
      }

    def run(arguments: Vector[BigInt]): Either[String, Run] =
      for
        root <- definition
        value <- invoke(root, arguments)
      yield Run(value, reductions.get(), rounds.get(), trace.toVector)

    private def record(control: EntityId, environment: Map[String, BigInt], continuation: Int): Unit =
      if engine == Engine.Ceskr && trace.size < 10000 then
        val bindings = environment.toVector.sortBy(_._1).map((name, value) => s"$name=$value").mkString(",")
        val kind = graph.entity(control).map(_.kind).getOrElse("unknown")
        trace += f"${reductions.get()}%05d C=$kind:${control.value} E={$bindings} K=$continuation R=Nat"

    private def reduce(control: EntityId, environment: Map[String, BigInt], continuation: Int): Either[String, Outcome] =
      val reduction = reductions.incrementAndGet()
      if reduction > 1000000 then Left("workspace reduction limit exceeded")
      else
        record(control, environment, continuation)
        node(control).flatMap { current =>
          current.kind match
            case "ir.reference" =>
              current.attrs.get("name").flatMap(environment.get).toRight(s"unbound reference at ${control.value}")
            case "ir.constructor" =>
              children(control, "argument-") match
                case Vector() => Right(BigInt(0))
                case Vector((_, argument)) => reduceValue(argument, environment, continuation + 1).map(_ + 1)
                case _ => Left(s"Nat constructor ${control.value} must have zero or one argument")
            case "ir.binary" =>
              if current.attrs.get("operator") != Some("+") then Left(s"unsupported Nat primitive at ${control.value}")
              else
                for
                  left <- child(control, "left")
                  right <- child(control, "right")
                  values <- reduceIndependent(Vector(left, right), environment, continuation + 1)
                yield values(0) + values(1)
            case "ir.call" =>
              for
                name <- current.attrs.get("callee").toRight(s"call ${control.value} lacks callee")
                table <- functions
                target <- table.get(name).toRight(s"unknown workspace function $name")
                args <- reduceIndependent(children(control, "argument-").map(_._2), environment, continuation + 1)
                result <- if current.attrs.get("tail").contains("true") then Right(TailCall(target, args)) else invoke(target, args)
              yield result
            case "ir.match" =>
              for
                scrutinee <- child(control, "scrutinee")
                value <- reduceValue(scrutinee, environment, continuation + 1)
                selected <- selectCase(control, value)
                pattern <- child(selected, "pattern")
                body <- child(selected, "body")
                patternNode <- node(pattern)
                binder = patternNode.attrs.get("binder")
                nextEnvironment = binder.fold(environment)(name => environment.updated(name, value + (-1)))
                result <- reduce(body, nextEnvironment, continuation + 1)
              yield result
            case other => Left(s"unsupported workspace control $other at ${control.value}")
        }

    private def reduceValue(control: EntityId, environment: Map[String, BigInt], continuation: Int): Either[String, BigInt] =
      reduce(control, environment, continuation).flatMap {
        case value: BigInt => Right(value)
        case _: TailCall => Left(s"tail call escaped value context at ${control.value}")
      }

    private def reduceIndependent(controls: Vector[EntityId], environment: Map[String, BigInt], continuation: Int): Either[String, Vector[BigInt]] =
      engine match
        case Engine.Ceskr => sequence(controls.map(reduceValue(_, environment, continuation)))
        case Engine.DeltaNet =>
          if controls.nonEmpty then rounds.incrementAndGet()
          given ExecutionContext = ExecutionContext.global
          // A DeltaNet round executes independent argument/operand redexes concurrently.
          try
            val scheduled = Future.sequence(controls.map(control => Future(reduceValue(control, environment, continuation))))
            sequence(blocking(Await.result(scheduled, Duration.Inf)))
          catch case error: Throwable => Left(s"DeltaNet worker failed: ${error.getMessage}")

    private def selectCase(control: EntityId, value: BigInt): Either[String, EntityId] =
      val admitted = children(control, "case-").flatMap { case (_, branch) =>
        child(branch, "pattern").toOption.flatMap(node(_).toOption).flatMap { pattern =>
          val successor = pattern.attrs.contains("binder")
          Option.when((!successor && value == 0) || (successor && value != 0))(branch)
        }
      }
      admitted match
        case Vector(branch) => Right(branch)
        case Vector() => Left(s"non-exhaustive Nat match at ${control.value}")
        case _ => Left(s"ambiguous Nat match at ${control.value}")

    private def invoke(initialFunction: EntityId, initialArguments: Vector[BigInt]): Either[String, BigInt] =
      var function = initialFunction
      var arguments = initialArguments
      var done: Option[Either[String, BigInt]] = None
      while done.isEmpty do
        val parameters = children(function, "parameter-").map(_._2)
        if parameters.size != arguments.size then done = Some(Left(s"arity mismatch at ${function.value}"))
        else
          val names = parameters.map(parameter => node(parameter).flatMap(_.attrs.get("name").toRight(s"parameter ${parameter.value} lacks name")))
          sequence(names).flatMap { resolved =>
            child(function, "body").flatMap(reduce(_, resolved.zip(arguments).toMap, 0))
          } match
            case Left(error) => done = Some(Left(error))
            case Right(value: BigInt) => done = Some(Right(value))
            case Right(TailCall(next, args)) => function = next; arguments = args
      done.get

    private def sequence[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
      values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty))((acc, value) => acc.flatMap(items => value.map(items :+ _)))
