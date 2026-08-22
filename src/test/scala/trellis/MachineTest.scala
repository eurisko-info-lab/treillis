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
    }),
    Test("machine transition choices are interpreted from F4 rule data", () => {
      equal(Check.MachineRules.decision(Bootstrap.f4, "alloc"), Some(Check.MachineAction.AllocateOwned))
      equal(Check.MachineRules.decision(Bootstrap.f4, "move"), Some(Check.MachineAction.MoveOwner))
      equal(Check.MachineRules.decision(Bootstrap.f4, "borrow-shared"), Some(Check.MachineAction.BeginSharedLoan))
      equal(Check.MachineRules.decision(Bootstrap.f4, "send"), Some(Check.MachineAction.ProcessDispatch))
    }),
    Test("F4 rule-driven machine stays in parity with the pre-F4 direct oracle", () => {
      val program = Vector(
        Instr.Alloc("job", Mode.Affine),
        Instr.BorrowShared("job", "r"),
        Instr.EndBorrow("r"),
        Instr.NewChannel("jobs"),
        Instr.Send("jobs", "job"),
        Instr.Recv("jobs", "worker"),
        Instr.Spawn("child", Vector.empty),
        Instr.Terminate("child", None),
        Instr.Join("child", "main")
      )
      val driven = right(Machine.run(program, graph = Bootstrap.f4))
      val direct = right(Machine.runDirect(program, graph = Bootstrap.f3))
      equal(driven, direct)
    }),
    Test("changing F4 dispatch data changes machine admissibility without changing the oracle", () => {
      val original = Bootstrap.f4.entity(trellis.Core.EntityId("machine.rule.alloc")).getOrElse(
        throw new AssertionError("missing F4 alloc rule")
      )
      val altered = original.copy(attrs = original.attrs.updated("action", "drop-owned"))
      val changed = trellis.Delta.applyChange(
        Bootstrap.f4,
        trellis.Delta.Change(Set.empty, Vector(trellis.Delta.Op.ReplaceEntity(trellis.Core.EntityId("machine.rule.alloc"), altered)), "alter F4 dispatch for test")
      ).fold(err => throw new AssertionError(err), identity)

      check(Machine.step(State(), Instr.Alloc("x", Mode.Affine), changed).isLeft)
      check(Machine.stepDirect(State(), Instr.Alloc("x", Mode.Affine), Bootstrap.f3).isRight)
    }),
    Test("F7 lowering maps machine instructions to graph-defined DeltaNet agent kinds", () => {
      val net = right(Machine.DeltaNet.lower(Vector(
        Instr.Alloc("x", Mode.Affine),
        Instr.NewChannel("jobs"),
        Instr.Send("jobs", "x"),
        Instr.Recv("jobs", "worker")
      ), Bootstrap.f7))
      equal(
        net.agents.map(_.kind),
        Vector(
          trellis.Core.EntityId("deltanet.agent-kind.alloc"),
          trellis.Core.EntityId("deltanet.agent-kind.channel"),
          trellis.Core.EntityId("deltanet.agent-kind.send"),
          trellis.Core.EntityId("deltanet.agent-kind.receive")
        )
      )
    }),
    Test("F7 DeltaNet lowering and readback stay in parity with CESK-R", () => {
      val program = Vector(
        Instr.Alloc("job", Mode.Affine),
        Instr.NewChannel("jobs"),
        Instr.Send("jobs", "job"),
        Instr.Recv("jobs", "worker"),
        Instr.Spawn("child", Vector.empty),
        Instr.Terminate("child", None),
        Instr.Join("child", "main")
      )
      val net = right(Machine.DeltaNet.run(program, graph = Bootstrap.f7))
      val ceskr = right(Machine.run(program, graph = Bootstrap.f7))
      equal(net, ceskr)
    }),
    Test("changing F7 lowering data changes the net without changing CESK-R", () => {
      val entity = trellis.Core.EntityId("deltanet.lower.alloc")
      val original = Bootstrap.f7.entity(entity).getOrElse(throw new AssertionError("missing F7 alloc lowering"))
      val altered = original.copy(attrs = original.attrs.updated("agent", "deltanet.agent-kind.move"))
      val changed = trellis.Delta.applyChange(
        Bootstrap.f7,
        trellis.Delta.Change(Set.empty, Vector(trellis.Delta.Op.ReplaceEntity(entity, altered)), "alter F7 lowering for test")
      ).fold(err => throw new AssertionError(err), identity)
      val net = right(Machine.DeltaNet.lower(Vector(Instr.Alloc("x", Mode.Affine)), changed))
      equal(net.agents.head.kind, trellis.Core.EntityId("deltanet.agent-kind.move"))
      check(Machine.run(Vector(Instr.Alloc("x", Mode.Affine)), graph = changed).isRight)
    }),
    Test("F7 replicator permits unrestricted duplication and rejects affine duplication", () => {
      val duplicated = right(Machine.DeltaNet.structural("x", Mode.Unrestricted, 2, Bootstrap.f7))
      equal(duplicated.outputs, Vector("x.0", "x.1"))
      equal(duplicated.interactions, 1)
      check(Machine.DeltaNet.structural("x", Mode.Affine, 2, Bootstrap.f7).isLeft)
      check(Machine.DeltaNet.structural("x", Mode.Linear, 2, Bootstrap.f7).isLeft)
    }),
    Test("F7 eraser performs unrestricted erase and affine drop but rejects linear erase", () => {
      val erased = right(Machine.DeltaNet.structural("u", Mode.Unrestricted, 0, Bootstrap.f7))
      val dropped = right(Machine.DeltaNet.structural("a", Mode.Affine, 0, Bootstrap.f7))
      equal(erased.trace, Vector("erase u"))
      equal(dropped.trace, Vector("drop a"))
      check(Machine.DeltaNet.structural("l", Mode.Linear, 0, Bootstrap.f7).isLeft)
    }),
    Test("F7 channel and process active-pair actions are graph-defined", () => {
      import trellis.Core.EntityId
      equal(
        Machine.DeltaNet.interactionAction(EntityId("deltanet.agent-kind.send"), EntityId("deltanet.agent-kind.channel"), None, Bootstrap.f7),
        Some(Check.DeltaNetAction.Enqueue)
      )
      equal(
        Machine.DeltaNet.interactionAction(EntityId("deltanet.agent-kind.receive"), EntityId("deltanet.agent-kind.channel"), None, Bootstrap.f7),
        Some(Check.DeltaNetAction.DequeueOrBlock)
      )
      equal(
        Machine.DeltaNet.interactionAction(EntityId("deltanet.agent-kind.spawn"), EntityId("deltanet.agent-kind.process"), None, Bootstrap.f7),
        Some(Check.DeltaNetAction.SplitContext)
      )
    }),
    Test("F7 preservation policy can reject lowering while CESK-R remains unchanged", () => {
      val entity = trellis.Core.EntityId("deltanet.lower.alloc")
      val original = Bootstrap.f7.entity(entity).getOrElse(throw new AssertionError("missing F7 alloc lowering"))
      val altered = original.copy(attrs = original.attrs.updated("preserves", "type;effect;protocol"))
      val changed = trellis.Delta.applyChange(
        Bootstrap.f7,
        trellis.Delta.Change(Set.empty, Vector(trellis.Delta.Op.ReplaceEntity(entity, altered)), "remove resource preservation for test")
      ).fold(err => throw new AssertionError(err), identity)
      check(Machine.DeltaNet.lower(Vector(Instr.Alloc("x", Mode.Affine)), changed).isLeft)
      check(Machine.run(Vector(Instr.Alloc("x", Mode.Affine)), graph = changed).isRight)
    }),
    Test("F8 runtime maps lowered agents to independent graph-defined primitives", () => {
      import trellis.Core.EntityId
      equal(
        Check.DeltaNetRuntimeRules.ruleForAgent(Bootstrap.f8, EntityId("deltanet.agent-kind.alloc")).map(_.primitive),
        Some(Check.DeltaNetPrimitive.AllocateOwned)
      )
      equal(
        Check.DeltaNetRuntimeRules.ruleForAgent(Bootstrap.f8, EntityId("deltanet.agent-kind.send")).map(_.primitive),
        Some(Check.DeltaNetPrimitive.Send)
      )
      equal(
        Check.DeltaNetRuntimeRules.ruleForAgent(Bootstrap.f8, EntityId("deltanet.agent-kind.join")).map(_.primitive),
        Some(Check.DeltaNetPrimitive.Join)
      )
    }),
    Test("F8 independent DeltaNet reducer stays in parity with CESK-R across all primitive families", () => {
      val program = Vector(
        Instr.Alloc("temp", Mode.Affine),
        Instr.BorrowShared("temp", "r"),
        Instr.EndBorrow("r"),
        Instr.Drop("temp"),
        Instr.Alloc("mut", Mode.Affine),
        Instr.BorrowMut("mut", "m"),
        Instr.EndBorrow("m"),
        Instr.Drop("mut"),
        Instr.Alloc("moved", Mode.Affine),
        Instr.Move("moved", "worker"),
        Instr.Alloc("job", Mode.Affine),
        Instr.NewChannel("jobs"),
        Instr.Send("jobs", "job"),
        Instr.Recv("jobs", "receiver"),
        Instr.Alloc("owned", Mode.Affine),
        Instr.Alloc("shared", Mode.Unrestricted),
        Instr.Spawn("child", Vector("owned", "shared")),
        Instr.Terminate("child", None),
        Instr.Join("child", "main")
      )
      val net = right(Machine.DeltaNet.run(program, graph = Bootstrap.f8))
      val ceskr = right(Machine.run(program, graph = Bootstrap.f8))
      equal(net, ceskr)
    }),
    Test("F8 DeltaNet execution no longer depends on F4 machine dispatch", () => {
      val entity = trellis.Core.EntityId("machine.rule.alloc")
      val original = Bootstrap.f8.entity(entity).getOrElse(throw new AssertionError("missing F4 alloc rule in F8"))
      val altered = original.copy(attrs = original.attrs.updated("action", "drop-owned"))
      val changed = trellis.Delta.applyChange(
        Bootstrap.f8,
        trellis.Delta.Change(Set.empty, Vector(trellis.Delta.Op.ReplaceEntity(entity, altered)), "break CESK-R dispatch for F8 independence test")
      ).fold(err => throw new AssertionError(err), identity)

      check(Machine.run(Vector(Instr.Alloc("x", Mode.Affine)), graph = changed).isLeft)
      check(Machine.DeltaNet.run(Vector(Instr.Alloc("x", Mode.Affine)), graph = changed).isRight)
    }),
    Test("changing F8 reduction data changes DeltaNet without changing CESK-R", () => {
      val entity = trellis.Core.EntityId("deltanet.reduce.alloc")
      val original = Bootstrap.f8.entity(entity).getOrElse(throw new AssertionError("missing F8 alloc reduction"))
      val altered = original.copy(attrs = original.attrs.updated("primitive", "drop-owned"))
      val changed = trellis.Delta.applyChange(
        Bootstrap.f8,
        trellis.Delta.Change(Set.empty, Vector(trellis.Delta.Op.ReplaceEntity(entity, altered)), "alter F8 reduction for test")
      ).fold(err => throw new AssertionError(err), identity)

      check(Machine.DeltaNet.run(Vector(Instr.Alloc("x", Mode.Affine)), graph = changed).isLeft)
      check(Machine.run(Vector(Instr.Alloc("x", Mode.Affine)), graph = changed).isRight)
    }),
    Test("F9 scheduler packs independent reductions into one deterministic parallel round", () => {
      val net = right(Machine.DeltaNet.lower(Vector(
        Instr.Alloc("a", Mode.Affine),
        Instr.Alloc("b", Mode.Affine),
        Instr.Alloc("c", Mode.Unrestricted)
      ), Bootstrap.f9))
      val rounds = right(Machine.DeltaNet.schedule(net, graph = Bootstrap.f9))
      equal(rounds.size, 1)
      equal(rounds.head.agents.map(_.id), Vector(0, 1, 2))
      equal(rounds.head.touches, Set("resource:a", "resource:b", "resource:c"))
    }),
    Test("F9 conflicts and readiness preserve resource dependency chains", () => {
      val program = Vector(
        Instr.Alloc("x", Mode.Affine),
        Instr.BorrowShared("x", "r"),
        Instr.EndBorrow("r"),
        Instr.Drop("x")
      )
      val net = right(Machine.DeltaNet.lower(program, Bootstrap.f9))
      val rounds = right(Machine.DeltaNet.schedule(net, graph = Bootstrap.f9))
      equal(rounds.map(_.agents.map(_.id)), Vector(Vector(0), Vector(1), Vector(2), Vector(3)))
    }),
    Test("F9 independent rounds commute under reverse local reduction order", () => {
      val net = right(Machine.DeltaNet.lower(Vector(
        Instr.Alloc("left", Mode.Affine),
        Instr.Alloc("right", Mode.Unrestricted)
      ), Bootstrap.f9))
      val round = right(Machine.DeltaNet.schedule(net, graph = Bootstrap.f9)).head
      check(right(Machine.DeltaNet.roundConfluent(Machine.State(), round, Bootstrap.f9)))
    }),
    Test("F9 parallel DeltaNet readback stays observationally equal to sequential F8", () => {
      val program = Vector(
        Instr.Alloc("a", Mode.Affine),
        Instr.Alloc("b", Mode.Unrestricted),
        Instr.Alloc("x", Mode.Affine),
        Instr.BorrowShared("x", "r"),
        Instr.EndBorrow("r"),
        Instr.Drop("x")
      )
      val parallel = right(Machine.DeltaNet.run(program, graph = Bootstrap.f9))
      val sequential = right(Machine.DeltaNet.run(program, graph = Bootstrap.f8))
      equal(parallel.copy(trace = Vector.empty), sequential.copy(trace = Vector.empty))
    }),
    Test("changing F9 scheduler policy changes round structure without changing readback", () => {
      val entity = trellis.Core.EntityId("deltanet.policy.parallel")
      val original = Bootstrap.f9.entity(entity).getOrElse(throw new AssertionError("missing F9 parallel policy"))
      val altered = original.copy(attrs = original.attrs.updated("scheduler", "singleton"))
      val changed = trellis.Delta.applyChange(
        Bootstrap.f9,
        trellis.Delta.Change(Set.empty, Vector(trellis.Delta.Op.ReplaceEntity(entity, altered)), "serialize F9 scheduler for test")
      ).fold(err => throw new AssertionError(err), identity)
      val program = Vector(Instr.Alloc("a", Mode.Affine), Instr.Alloc("b", Mode.Affine), Instr.Alloc("c", Mode.Affine))
      val net = right(Machine.DeltaNet.lower(program, changed))
      val parallelRounds = right(Machine.DeltaNet.schedule(net, graph = Bootstrap.f9))
      val singletonRounds = right(Machine.DeltaNet.schedule(net, graph = changed))
      equal(parallelRounds.size, 1)
      equal(singletonRounds.size, 3)
      val a = right(Machine.DeltaNet.run(program, graph = Bootstrap.f9)).copy(trace = Vector.empty)
      val b = right(Machine.DeltaNet.run(program, graph = changed)).copy(trace = Vector.empty)
      equal(a, b)
    }),
    Test("changing F9 footprint data changes conflicts without changing F8 execution", () => {
      val entity = trellis.Core.EntityId("deltanet.parallel.alloc")
      val original = Bootstrap.f9.entity(entity).getOrElse(throw new AssertionError("missing F9 alloc parallel profile"))
      val altered = original.copy(attrs = original.attrs.updated("touches", "resource:name;global:=alloc"))
      val changed = trellis.Delta.applyChange(
        Bootstrap.f9,
        trellis.Delta.Change(Set.empty, Vector(trellis.Delta.Op.ReplaceEntity(entity, altered)), "serialize alloc footprints for test")
      ).fold(err => throw new AssertionError(err), identity)
      val program = Vector(Instr.Alloc("a", Mode.Affine), Instr.Alloc("b", Mode.Affine), Instr.Alloc("c", Mode.Affine))
      val net = right(Machine.DeltaNet.lower(program, changed))
      equal(right(Machine.DeltaNet.schedule(net, graph = changed)).size, 3)
      check(Machine.DeltaNet.run(program, graph = Bootstrap.f8).isRight)
    })
  )
