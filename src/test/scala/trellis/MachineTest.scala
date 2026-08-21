package trellis

import trellis.Core.Mode
import trellis.Machine.*
import trellis.TestSupport.*

object MachineTest:
  private def right[A](value: Either[String, A]): A = value.fold(err => throw new AssertionError(err), identity)

  val tests = Vector(
    Test("ownership moves through an asynchronous channel", () => {
      val program = Vector(
        Instr.Alloc("job", Mode.Affine),
        Instr.NewChannel("jobs"),
        Instr.Send("jobs", "job"),
        Instr.Recv("jobs", "worker")
      )
      val end = right(Machine.run(program))
      equal(end.resources("job").owner, Owner.Process("worker"))
    }),
    Test("mutable borrow excludes shared borrow", () => {
      val program = Vector(Instr.Alloc("x", Mode.Affine), Instr.BorrowMut("x", "m"), Instr.BorrowShared("x", "s"))
      check(Machine.run(program).isLeft)
    }),
    Test("affine resource drops deterministically", () => {
      val end = right(Machine.run(Vector(Instr.Alloc("x", Mode.Affine), Instr.Drop("x"))))
      check(!end.resources.contains("x"))
    }),
    Test("unrestricted send keeps sender access and grants receiver access", () => {
      val end = right(Machine.run(Vector(
        Instr.Alloc("n", Mode.Unrestricted),
        Instr.NewChannel("numbers"),
        Instr.Send("numbers", "n"),
        Instr.Recv("numbers", "worker")
      )))
      equal(end.resources("n").owner, Owner.Shared(Set("main", "worker")))
    }),
    Test("empty receive blocks and a later send wakes the receiver", () => {
      val blocked = right(Machine.run(Vector(
        Instr.NewChannel("jobs"),
        Instr.Recv("jobs", "worker")
      )))
      equal(blocked.processes("worker"), ProcessStatus.Blocked("jobs"))
      val withJob = right(Machine.step(blocked, Instr.Alloc("job", Mode.Affine)))
      val awakened = right(Machine.step(withJob, Instr.Send("jobs", "job")))
      equal(awakened.processes("worker"), ProcessStatus.Running)
      equal(awakened.resources("job").owner, Owner.Process("worker"))
      check(awakened.channels("jobs").isEmpty)
    }),
    Test("spawn moves affine captures and shares unrestricted captures", () => {
      val end = right(Machine.run(Vector(
        Instr.Alloc("owned", Mode.Affine),
        Instr.Alloc("shared", Mode.Unrestricted),
        Instr.Spawn("worker", Vector("owned", "shared"))
      )))
      equal(end.resources("owned").owner, Owner.Process("worker"))
      equal(end.resources("shared").owner, Owner.Shared(Set("main", "worker")))
      equal(end.resources("worker.handle").mode, Mode.Affine)
      equal(end.resources("worker.handle").owner, Owner.Process("main"))
    }),
    Test("process termination drops affine resources", () => {
      val started = right(Machine.run(Vector(
        Instr.Alloc("owned", Mode.Affine),
        Instr.Spawn("worker", Vector("owned"))
      )))
      val ended = right(Machine.step(started, Instr.Terminate("worker", None)))
      check(!ended.resources.contains("owned"))
      equal(ended.processes("worker"), ProcessStatus.Terminated(None))
      check(ended.trace.exists(_ == "drop owned on terminate"))
    }),
    Test("process termination rejects live linear obligations", () => {
      val started = right(Machine.run(Vector(
        Instr.Alloc("token", Mode.Linear),
        Instr.Spawn("worker", Vector("token"))
      )))
      check(Machine.step(started, Instr.Terminate("worker", None)).isLeft)
    }),
    Test("join transfers a child result and consumes the process handle", () => {
      val started = right(Machine.run(Vector(
        Instr.Alloc("result", Mode.Affine),
        Instr.Spawn("worker", Vector("result"))
      )))
      val terminated = right(Machine.step(started, Instr.Terminate("worker", Some("result"))))
      val joined = right(Machine.step(terminated, Instr.Join("worker", "main")))
      equal(joined.resources("result").owner, Owner.Process("main"))
      check(!joined.resources.contains("worker.handle"))
      check(!joined.processes.contains("worker"))
    }),
    Test("a channel can carry another channel endpoint", () => {
      val end = right(Machine.run(Vector(
        Instr.NewChannel("control"),
        Instr.NewChannel("reply"),
        Instr.Send("control", "reply.recv"),
        Instr.Recv("control", "worker")
      )))
      equal(end.resources("reply.recv").kind, "process.recv")
      equal(end.resources("reply.recv").owner, Owner.Process("worker"))
    }),
    Test("process transition choices are interpreted from F3 rule data", () => {
      equal(Check.ProcessRules.decision(Bootstrap.f3, "process.send", Some(Mode.Unrestricted)), Some(Check.ProcessDisposition.CopyToChannel))
      equal(Check.ProcessRules.decision(Bootstrap.f3, "process.send", Some(Mode.Affine)), Some(Check.ProcessDisposition.TransferToChannel))
      equal(Check.ProcessRules.decision(Bootstrap.f3, "process.spawn", Some(Mode.Unrestricted)), Some(Check.ProcessDisposition.ShareWithChild))
      equal(Check.ProcessRules.decision(Bootstrap.f3, "process.spawn", Some(Mode.Linear)), Some(Check.ProcessDisposition.TransferToChild))
    })
  )
