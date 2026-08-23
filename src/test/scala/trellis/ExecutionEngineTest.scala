package trellis

import trellis.Core.*
import trellis.engine.*
import trellis.TestSupport.*

object ExecutionEngineTest:
  private val workspace = EntityId("example.tailrec.fibonacci")
  private def right[A](value: Either[String, A]): A = value.fold(error => throw new AssertionError(error), identity)

  val tests = Vector(
    Test("native workspace engines execute the stored IR with observable parity", () => {
      val expected = Vector(0 -> BigInt(0), 1 -> BigInt(1), 2 -> BigInt(1), 10 -> BigInt(55), 50 -> BigInt("12586269025"))
      expected.foreach { case (input, output) =>
        val request = ExecutionRequest(trellis.storage.RepositoryProducts.graph, workspace, Vector(BigInt(input)))
        val delta = right(DeltaNetEngine.execute(request))
        val ceskr = right(CeskrEngine.execute(request))
        equal(delta.value, output)
        equal(ceskr.value, delta.value)
      }
    }),
    Test("native DeltaNet schedules rounds and native CESK-R records states", () => {
      val request = ExecutionRequest(trellis.storage.RepositoryProducts.graph, workspace, Vector(BigInt(10)))
      val delta = right(DeltaNetEngine.execute(request))
      val ceskr = right(CeskrEngine.execute(request))
      check(delta.rounds > 0)
      equal(delta.trace, Vector.empty)
      equal(ceskr.rounds, 0)
      check(ceskr.trace.exists(_.contains("C=ir.match")))
      check(ceskr.trace.exists(_.contains("E={current=")))
    })
  )
