package trellis.engine

import trellis.Core.*
import trellis.engine.internal.NatWorkspaceEvaluator
import trellis.ir.ExecutionIr

final case class ExecutionRequest(graph: Graph, root: EntityId, arguments: Vector[BigInt], ir: String = ExecutionIr.NatV1)
final case class ExecutionResult(value: BigInt, reductions: Int, rounds: Int, trace: Vector[String])

trait ExecutionEngine:
  def id: String
  def acceptedIr: Set[String]
  def execute(request: ExecutionRequest): Either[String, ExecutionResult]

object DeltaNetEngine extends ExecutionEngine:
  val id = "deltanet"
  val acceptedIr = Set(ExecutionIr.NatV1)
  def execute(request: ExecutionRequest): Either[String, ExecutionResult] = executeWith(request, NatWorkspaceEvaluator.Engine.DeltaNet)

object CeskrEngine extends ExecutionEngine:
  val id = "ceskr"
  val acceptedIr = Set(ExecutionIr.NatV1)
  def execute(request: ExecutionRequest): Either[String, ExecutionResult] = executeWith(request, NatWorkspaceEvaluator.Engine.Ceskr)

object Engines:
  val installed: Vector[ExecutionEngine] = Vector(DeltaNetEngine, CeskrEngine)
  def named(id: String): Either[String, ExecutionEngine] = installed.find(_.id == id).toRight(s"unknown execution engine $id")

private def executeWith(request: ExecutionRequest, engine: NatWorkspaceEvaluator.Engine): Either[String, ExecutionResult] =
  for
    _ <- if Set(ExecutionIr.NatV1)(request.ir) then Right(()) else Left(s"unsupported IR ${request.ir}")
    _ <- ExecutionIr.validateReachable(request.graph, request.root)
    run <- NatWorkspaceEvaluator.run(request.graph, request.root, request.arguments, engine)
  yield ExecutionResult(run.value, run.reductions, run.rounds, run.trace)
