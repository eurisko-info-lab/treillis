package trellis

import trellis.Core.Mode
import trellis.Machine.*
import trellis.TestSupport.*

object MachineTest:
  val tests = Vector(
    Test("ownership moves through an asynchronous channel", () => {
      val program = Vector(
        Instr.Alloc("job", Mode.Affine),
        Instr.NewChannel("jobs"),
        Instr.Send("jobs", "job"),
        Instr.Recv("jobs", "worker")
      )
      val end = Machine.run(program).fold(err => throw new AssertionError(err), identity)
      equal(end.resources("job").owner, Owner.Process("worker"))
    }),
    Test("mutable borrow excludes shared borrow", () => {
      val program = Vector(Instr.Alloc("x", Mode.Affine), Instr.BorrowMut("x", "m"), Instr.BorrowShared("x", "s"))
      check(Machine.run(program).isLeft)
    }),
    Test("affine resource drops deterministically", () => {
      val end = Machine.run(Vector(Instr.Alloc("x", Mode.Affine), Instr.Drop("x"))).fold(err => throw new AssertionError(err), identity)
      check(!end.resources.contains("x"))
    })
  )
