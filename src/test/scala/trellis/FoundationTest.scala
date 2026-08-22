package trellis

import java.util.Arrays
import trellis.Core.*
import trellis.Delta.*
import trellis.TestSupport.*

object FoundationTest:
  private def right[A](value: Either[String, A]): A = value.fold(err => throw new AssertionError(err), identity)

  val tests = Vector(
    Test("F1 delta is canonical data with a frozen content id", () => {
      val change = Bootstrap.f1Change
      equal(Change.id(change).value, Bootstrap.F1ChangeId)
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
      check(Arrays.equals(Delta.encodeChangeBytes(change), Delta.encodeChange(change).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
    }),
    Test("F1 is derived only from F0 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f0, Bootstrap.f1Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f1)))
      equal(Canon.graphId(derived).value, Bootstrap.F1Root)
    }),
    Test("F1 semantic schema is graph data rather than Scala node cases", () => {
      check(Bootstrap.f1SchemaEntities.subsetOf(Bootstrap.f1.entities.keySet))
      Bootstrap.f1SchemaEntities.foreach { entity =>
        val node = Bootstrap.f1.entity(entity).getOrElse(throw new AssertionError(s"missing ${entity.value}"))
        equal(node.kind, "meta.schema")
        equal(node.attrs.get("foundation"), Some("F1"))
        check(node.port("self").exists(_.direction == Direction.Out), s"${entity.value} lacks schema self output")
      }
    }),
    Test("F1 schema relationships are first-class typed graph edges", () => {
      equal(Bootstrap.f1.edges.size, 12)
      check(Check.validate(Bootstrap.f1).isEmpty)
      val roles = Bootstrap.f1.edges.values.map(_.role).toSet
      check(Set("schema.type", "schema.mode", "schema.capability", "schema.port", "schema.node-kind", "schema.edge-kind", "schema.graph", "schema.change").subsetOf(roles))
    }),
    Test("replacing an entity prunes obsolete unreferenced semantic content", () => {
      val old = Node("old")
      val oldId = Canon.nodeId(old)
      val entity = EntityId("app.x")
      val base = Graph(nodes = Map(oldId -> old), entities = Map(entity -> oldId))
      val next = right(Delta.applyChange(base, Change(Set.empty, Vector(Op.ReplaceEntity(entity, Node("new"))), "replace")))
      check(!next.nodes.contains(oldId))
      equal(next.entity(entity).map(_.kind), Some("new"))
    }),
    Test("F2 delta is canonical data depending exactly on F1", () => {
      val change = Bootstrap.f2Change
      equal(Change.id(change).value, Bootstrap.F2ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F1ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F2 is derived only from F1 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f1, Bootstrap.f2Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f2)))
      equal(Canon.graphId(derived).value, Bootstrap.F2Root)
    }),
    Test("F2 modes, capabilities, operations, and rules are Trellis graph data", () => {
      val graph = Bootstrap.f2
      check(Bootstrap.f2ModeEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f2CapabilityEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f2OperationEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f2RuleEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f2ModeEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("resource.mode")))
      Bootstrap.f2CapabilityEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("resource.capability")))
      Bootstrap.f2OperationEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("resource.operation")))
      Bootstrap.f2RuleEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("resource.rule")))
      equal(Check.ResourceRules.rules(graph).size, 10)
    }),
    Test("F2 resource relationships are first-class typed graph edges", () => {
      check(Check.validate(Bootstrap.f2).isEmpty)
      val roles = Bootstrap.f2.edges.values.map(_.role).toSet
      check(Set("resource.operation", "resource.mode", "resource.capability").subsetOf(roles))
      equal(Bootstrap.f2.edges.size, 33)
    }),
    Test("F2 structural permissions come from mode graph data", () => {
      val u = Check.ResourceRules.structural(Bootstrap.f2, Mode.Unrestricted)
      val a = Check.ResourceRules.structural(Bootstrap.f2, Mode.Affine)
      val l = Check.ResourceRules.structural(Bootstrap.f2, Mode.Linear)
      equal((u.duplicate, u.discard), ("allow", "allow"))
      equal((a.duplicate, a.discard), ("forbid", "drop"))
      equal((l.duplicate, l.discard), ("forbid", "forbid"))
    }),
    Test("F3 delta is canonical data depending exactly on F2", () => {
      val change = Bootstrap.f3Change
      equal(Change.id(change).value, Bootstrap.F3ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F2ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F3 is derived only from F2 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f2, Bootstrap.f3Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f3)))
      equal(Canon.graphId(derived).value, Bootstrap.F3Root)
    }),
    Test("F3 process concepts, capabilities, operations, and rules are Trellis graph data", () => {
      val graph = Bootstrap.f3
      check(Bootstrap.f3ProcessEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f3CapabilityEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f3OperationEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f3RuleEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f3ProcessEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("process.kind")))
      Bootstrap.f3CapabilityEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("process.capability")))
      Bootstrap.f3OperationEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("process.operation")))
      Bootstrap.f3RuleEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("process.rule")))
      equal(Check.ProcessRules.rules(graph).size, 10)
    }),
    Test("F3 process relationships are first-class typed graph edges", () => {
      check(Check.validate(Bootstrap.f3).isEmpty)
      val roles = Bootstrap.f3.edges.values.map(_.role).toSet
      check(Set("process.operation", "process.mode", "process.send-capability", "process.recv-capability").subsetOf(roles))
      equal(Bootstrap.f3.edges.size, 54)
    }),
    Test("F3 endpoint structural modes come from graph data", () => {
      equal(Check.ProcessRules.endpointMode(Bootstrap.f3, EntityId("process.capability.send")), Right(Mode.Unrestricted))
      equal(Check.ProcessRules.endpointMode(Bootstrap.f3, EntityId("process.capability.recv")), Right(Mode.Affine))
      equal(Check.ProcessRules.endpointMode(Bootstrap.f3, EntityId("process.capability.handle")), Right(Mode.Affine))
    }),
    Test("F4 delta is canonical data depending exactly on F3", () => {
      val change = Bootstrap.f4Change
      equal(Change.id(change).value, Bootstrap.F4ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F3ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F4 is derived only from F3 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f3, Bootstrap.f4Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f4)))
      equal(Canon.graphId(derived).value, Bootstrap.F4Root)
    }),
    Test("F4 machine state components and transition rules are Trellis graph data", () => {
      val graph = Bootstrap.f4
      check(Bootstrap.f4ComponentEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f4RuleEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f4ComponentEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("machine.component")))
      Bootstrap.f4RuleEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("machine.rule")))
      equal(Check.MachineRules.rules(graph).size, 12)
    }),
    Test("F4 machine relationships are first-class typed graph edges", () => {
      check(Check.validate(Bootstrap.f4).isEmpty)
      val roles = Bootstrap.f4.edges.values.map(_.role).toSet
      check(Set("machine.component", "machine.operation").subsetOf(roles))
      equal(Bootstrap.f4.edges.size, 74)
    }),
    Test("F4 dispatch table covers every reference-machine instruction kind", () => {
      val instructions = Check.MachineRules.rules(Bootstrap.f4).map(_.instruction).toSet
      equal(
        instructions,
        Set("alloc", "move", "borrow-shared", "borrow-mut", "end-borrow", "drop",
          "new-channel", "send", "receive", "spawn", "terminate", "join")
      )
    }),
    Test("F5 delta is canonical data depending exactly on F4", () => {
      val change = Bootstrap.f5Change
      equal(Change.id(change).value, Bootstrap.F5ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F4ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F5 is derived only from F4 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f4, Bootstrap.f5Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f5)))
      equal(Canon.graphId(derived).value, Bootstrap.F5Root)
    }),
    Test("F5 projection components, views, and render rules are Trellis graph data", () => {
      val graph = Bootstrap.f5
      check(Bootstrap.f5ComponentEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f5ViewEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f5RuleEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f5ComponentEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("projection.component")))
      Bootstrap.f5ViewEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("projection.view")))
      Bootstrap.f5RuleEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("projection.rule")))
      equal(graph.entity(EntityId("projection.engine")).map(_.kind), Some("projection.schema"))
      equal(Project.ProjectionRules.views(graph).size, 6)
      equal(Project.ProjectionRules.rules(graph).size, 10)
    }),
    Test("F5 projection relationships are first-class typed graph edges", () => {
      check(Check.validate(Bootstrap.f5).isEmpty)
      val roles = Bootstrap.f5.edges.values.map(_.role).toSet
      check(Set("projection.component", "projection.view").subsetOf(roles))
      equal(Bootstrap.f5.edges.size, 91)
    }),
    Test("F5 view and primitive policy comes from graph data", () => {
      val svg = Project.ProjectionRules.view(Bootstrap.f5, EntityId("projection.svg")).fold(err => throw new AssertionError(err), identity)
      equal(svg.format, "svg")
      equal(svg.int("width", 0), 640)
      check(Project.ProjectionRules.hasPrimitive(Bootstrap.f5, EntityId("projection.svg"), "node", "svg-node"))
      check(Project.ProjectionRules.hasPrimitive(Bootstrap.f5, EntityId("projection.svg"), "edge", "svg-edge"))
      val machineView = Project.ProjectionRules.view(Bootstrap.f5, EntityId("projection.svg.machine")).fold(err => throw new AssertionError(err), identity)
      equal(machineView.attrs.get("node-filter"), Some("entity-prefix:machine."))
    }),
    Test("F6 delta is canonical data depending exactly on F5", () => {
      val change = Bootstrap.f6Change
      equal(Change.id(change).value, Bootstrap.F6ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F5ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F6 is derived only from F5 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f5, Bootstrap.f6Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f6)))
      equal(Canon.graphId(derived).value, Bootstrap.F6Root)
    }),
    Test("F6 equality components, invariants, costs, and laws are Trellis graph data", () => {
      val graph = Bootstrap.f6
      check(Bootstrap.f6ComponentEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f6InvariantEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f6CostDimensionEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f6LawEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f6ComponentEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("equality.component")))
      Bootstrap.f6InvariantEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("equality.invariant")))
      Bootstrap.f6CostDimensionEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("equality.cost-dimension")))
      Bootstrap.f6LawEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("equality.law")))
      equal(graph.entity(EntityId("equality.engine")).map(_.kind), Some("equality.schema"))
      equal(graph.entity(EntityId("equality.policy.rewrite")).map(_.kind), Some("equality.policy"))
      equal(graph.entity(EntityId("equality.cost-model.default")).map(_.kind), Some("equality.cost-model"))
    }),
    Test("F6 equality relationships are first-class typed graph edges", () => {
      check(Check.validate(Bootstrap.f6).isEmpty)
      val roles = Bootstrap.f6.edges.values.map(_.role).toSet
      check(Set("equality.component", "equality.invariant", "equality.cost-dimension", "equality.policy", "equality.cost-model", "equality.law").subsetOf(roles))
      equal(Bootstrap.f6.edges.size, 121)
    }),
    Test("F6 rewrite admission and extraction policy comes from graph data", () => {
      val policy = Check.EqualityRules.policy(Bootstrap.f6).fold(err => throw new AssertionError(err), identity)
      equal(policy.requiredPreserve, Set("type", "resource", "effect", "protocol"))
      check(policy.proofRequired)
      equal(policy.maxIterations, 32)
      equal(policy.maxTerms, 4096)
      equal(Check.EqualityRules.invariantKeys(Bootstrap.f6), Set("type", "resource", "effect", "protocol"))
      val cost = Check.EqualityRules.costModel(Bootstrap.f6).fold(err => throw new AssertionError(err), identity)
      equal(cost.weights("nodes"), 1)
      equal(cost.weights("allocations"), 4)
      equal(cost.weights("replication"), 6)
      equal(cost.weights("communication"), 8)
      equal(Check.EqualityRules.costDimensionKeys(Bootstrap.f6), Set(
        "nodes", "allocations", "replication", "interactions", "peak-memory", "communication", "critical-path"
      ))
    }),
    Test("F7 delta is canonical data depending exactly on F6", () => {
      val change = Bootstrap.f7Change
      equal(Change.id(change).value, Bootstrap.F7ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F6ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F7 is derived only from F6 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f6, Bootstrap.f7Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f7)))
      equal(Canon.graphId(derived).value, Bootstrap.F7Root)
    }),
    Test("F7 DeltaNet components, agent kinds, lowerings, and interactions are Trellis graph data", () => {
      val graph = Bootstrap.f7
      check(Bootstrap.f7ComponentEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f7AgentKindEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f7LoweringEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f7InteractionEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f7ComponentEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("deltanet.component")))
      Bootstrap.f7AgentKindEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("deltanet.agent-kind")))
      Bootstrap.f7LoweringEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("deltanet.lowering-rule")))
      Bootstrap.f7InteractionEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("deltanet.interaction-rule")))
      equal(Check.DeltaNetRules.lowerings(graph).size, 12)
      equal(Check.DeltaNetRules.interactions(graph).size, 7)
    }),
    Test("F7 DeltaNet relationships are first-class typed graph edges", () => {
      check(Check.validate(Bootstrap.f7).isEmpty)
      val roles = Bootstrap.f7.edges.values.map(_.role).toSet
      check(Set("deltanet.component", "deltanet.policy", "deltanet.invariant", "deltanet.mode", "deltanet.operation", "deltanet.agent-kind").subsetOf(roles))
      equal(Bootstrap.f7.edges.size, 181)
    }),
    Test("F7 lowering, scheduling, readback, and structural policy comes from graph data", () => {
      val policy = Check.DeltaNetRules.policy(Bootstrap.f7).fold(err => throw new AssertionError(err), identity)
      equal(policy.requiredPreserve, Set("type", "resource", "effect", "protocol"))
      check(policy.proofRequired)
      equal(policy.maxInteractions, 4096)
      equal(policy.scheduler, "stable-agent-id")
      equal(policy.readback, "ceskr-state")
      val structural = Check.DeltaNetRules.structuralPolicy(Bootstrap.f7).fold(err => throw new AssertionError(err), identity)
      equal(structural.duplicateAgent, EntityId("deltanet.agent-kind.replicator"))
      equal(structural.eraseAgent, EntityId("deltanet.agent-kind.eraser"))
      equal((structural.unrestrictedDiscard, structural.affineDiscard, structural.linearDiscard), ("erase", "drop", "forbid"))
      equal(
        Check.DeltaNetRules.lowerings(Bootstrap.f7).map(_.instruction).toSet,
        Set("alloc", "move", "borrow-shared", "borrow-mut", "end-borrow", "drop",
          "new-channel", "send", "receive", "spawn", "terminate", "join")
      )
    }),
    Test("F8 delta is canonical data depending exactly on F7", () => {
      val change = Bootstrap.f8Change
      equal(Change.id(change).value, Bootstrap.F8ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F7ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F8 is derived only from F7 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f7, Bootstrap.f8Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f8)))
      equal(Canon.graphId(derived).value, Bootstrap.F8Root)
    }),
    Test("F8 runtime components, policy, and reduction rules are Trellis graph data", () => {
      val graph = Bootstrap.f8
      check(Bootstrap.f8RuntimeComponentEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f8ReductionEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f8RuntimeComponentEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("deltanet.runtime-component")))
      Bootstrap.f8ReductionEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("deltanet.reduction-rule")))
      equal(graph.entity(EntityId("deltanet.policy.runtime")).map(_.kind), Some("deltanet.runtime-policy"))
      equal(graph.entity(EntityId("deltanet.runtime-engine")).map(_.kind), Some("deltanet.runtime-schema"))
      equal(Check.DeltaNetRuntimeRules.rules(graph).size, 12)
    }),
    Test("F8 runtime relationships are first-class typed graph edges", () => {
      check(Check.validate(Bootstrap.f8).isEmpty)
      val roles = Bootstrap.f8.edges.values.map(_.role).toSet
      check(Set("deltanet.runtime-component", "deltanet.runtime-policy", "deltanet.runtime-invariant",
        "deltanet.runtime-agent", "deltanet.runtime-operation").subsetOf(roles))
      equal(Bootstrap.f8.edges.size, 215)
    }),
    Test("F8 independent execution policy and complete reduction table come from graph data", () => {
      val policy = Check.DeltaNetRuntimeRules.policy(Bootstrap.f8).fold(err => throw new AssertionError(err), identity)
      equal(policy.requiredPreserve, Set("type", "resource", "effect", "protocol"))
      check(policy.proofRequired)
      equal(policy.maxReductions, 4096)
      equal(policy.scheduler, "stable-agent-id")
      equal(policy.readback, "ceskr-state")
      equal(policy.executor, "independent")
      check(!policy.delegate)
      equal(policy.oracle, "ceskr")
      equal(
        Check.DeltaNetRuntimeRules.rules(Bootstrap.f8).map(_.agent).toSet,
        Check.DeltaNetRules.lowerings(Bootstrap.f8).map(_.agent).toSet
      )
    }),
    Test("F9 delta is canonical data depending exactly on F8", () => {
      val change = Bootstrap.f9Change
      equal(Change.id(change).value, Bootstrap.F9ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F8ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F9 is derived only from F8 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f8, Bootstrap.f9Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f9)))
      equal(Canon.graphId(derived).value, Bootstrap.F9Root)
    }),
    Test("F9 parallel components, policy, and profiles are Trellis graph data", () => {
      val graph = Bootstrap.f9
      check(Bootstrap.f9ParallelComponentEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f9ParallelProfileEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f9ParallelComponentEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("deltanet.parallel-component")))
      Bootstrap.f9ParallelProfileEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("deltanet.parallel-profile")))
      equal(graph.entity(EntityId("deltanet.policy.parallel")).map(_.kind), Some("deltanet.parallel-policy"))
      equal(Check.DeltaNetParallelRules.profiles(graph).size, 12)
    }),
    Test("F9 parallel relationships are first-class typed graph edges", () => {
      check(Check.validate(Bootstrap.f9).isEmpty)
      val roles = Bootstrap.f9.edges.values.map(_.role).toSet
      check(Set("deltanet.parallel.invariant", "deltanet.parallel.agent", "deltanet.parallel.operation").subsetOf(roles))
      equal(Bootstrap.f9.edges.size, 243)
    }),
    Test("F9 deterministic parallel scheduling policy comes from graph data", () => {
      val policy = Check.DeltaNetParallelRules.policy(Bootstrap.f9).fold(err => throw new AssertionError(err), identity)
      equal(policy.requiredPreserve, Set("type", "resource", "effect", "protocol"))
      check(policy.proofRequired)
      equal(policy.maxRounds, 4096)
      equal(policy.scheduler, "maximal-nonconflicting")
      equal(policy.tieBreak, "stable-agent-id")
      equal(policy.conflict, "touch-overlap")
      equal(policy.independence, "disjoint-touch")
      equal(policy.oracle, "sequential-f8")
      equal(policy.confluence, "readback-equality")
      equal(
        Check.DeltaNetParallelRules.profiles(Bootstrap.f9).map(_.agent).toSet,
        Check.DeltaNetRuntimeRules.rules(Bootstrap.f9).map(_.agent).toSet
      )
    }),
    Test("F10 delta is canonical data depending exactly on F9", () => {
      val change = Bootstrap.f10Change
      equal(Change.id(change).value, Bootstrap.F10ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F9ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F10 is derived only from F9 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f9, Bootstrap.f10Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f10)))
      equal(Canon.graphId(derived).value, Bootstrap.F10Root)
    }),
    Test("F10 execution evidence components and policy are Trellis graph data", () => {
      val graph = Bootstrap.f10
      check(Bootstrap.f10EvidenceComponentEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f10EvidenceComponentEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("deltanet.evidence-component")))
      equal(graph.entity(EntityId("deltanet.policy.evidence")).map(_.kind), Some("deltanet.evidence-policy"))
    }),
    Test("F10 evidence relationships are first-class typed graph edges", () => {
      check(Check.validate(Bootstrap.f10).isEmpty)
      val roles = Bootstrap.f10.edges.values.map(_.role).toSet
      check(Set("evidence.net-root", "evidence.initial-root", "evidence.final-root", "evidence.readback-root",
        "evidence.round", "evidence.redex", "evidence.footprint", "evidence.verify", "evidence.replay", "evidence.component").subsetOf(roles))
      equal(Bootstrap.f10.edges.size, 263)
    }),
    Test("F10 canonical replay policy comes from graph data", () => {
      val policy = Check.DeltaNetEvidenceRules.policy(Bootstrap.f10).fold(err => throw new AssertionError(err), identity)
      equal(policy.encoding, "canonical-v1")
      equal(policy.hash, "sha256")
      equal(policy.stateRoot, "observable-state-v1")
      equal(policy.roundOrder, "stable-index")
      equal(policy.agentOrder, "stable-agent-id")
      equal(policy.verification, "replay-exact")
      check(policy.requireFootprints)
      check(policy.requireConfluence)
      check(policy.bindFoundationRoot)
      check(policy.bindPolicyContent)
    }),
    Test("F11 delta is canonical data depending exactly on F10", () => {
      val change = Bootstrap.f11Change
      equal(Change.id(change).value, Bootstrap.F11ChangeId)
      equal(change.dependencies, Set(ChangeId(Bootstrap.F10ChangeId)))
      equal(Delta.decodeChange(Delta.encodeChange(change)), Right(change))
    }),
    Test("F11 is derived only from F10 plus its canonical delta", () => {
      val derived = right(Delta.applyChange(Bootstrap.f10, Bootstrap.f11Change))
      check(Arrays.equals(Canon.encodeGraphBytes(derived), Canon.encodeGraphBytes(Bootstrap.f11)))
      equal(Canon.graphId(derived).value, Bootstrap.F11Root)
    }),
    Test("F11 closure manifest and derivation staircase are Trellis graph data", () => {
      val graph = Bootstrap.f11
      check(Bootstrap.f11ClosureComponentEntities.subsetOf(graph.entities.keySet))
      check(Bootstrap.f11DerivationStepEntities.subsetOf(graph.entities.keySet))
      Bootstrap.f11ClosureComponentEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("bootstrap.closure-component")))
      Bootstrap.f11DerivationStepEntities.foreach(e => equal(graph.entity(e).map(_.kind), Some("bootstrap.derivation-step")))
      equal(Check.BootstrapClosureRules.steps(graph).size, 10)
      val roles = graph.edges.values.map(_.role).toSet
      check(Set("bootstrap.component", "bootstrap.policy", "bootstrap.step", "bootstrap.manifest", "bootstrap.reproducer", "bootstrap.verifier").subsetOf(roles))
      equal(graph.nodes.size, 267)
      equal(graph.edges.size, 287)
      equal(graph.entities.size, 267)
      check(Check.validate(graph).isEmpty)
    }),
    Test("F11 clean-room verifier rederives F0 through F10 with no skip path", () => {
      val report1 = Bootstrap.cleanRoomReproduce(Bootstrap.f11).fold(err => throw new AssertionError(err), identity)
      val report2 = Bootstrap.cleanRoomReproduce(Bootstrap.f11).fold(err => throw new AssertionError(err), identity)
      equal(report1.start, "F0")
      equal(report1.end, "F10")
      equal(report1.steps.size, 10)
      equal(report1.finalRoot, Bootstrap.F10Root)
      equal(Bootstrap.encodeClosureReport(report1), Bootstrap.encodeClosureReport(report2))
      equal(Bootstrap.closureReportId(report1), Bootstrap.closureReportId(report2))
      equal(report1.steps.map(_.foundation), (1 to 10).map(i => s"F$i").toVector)
    }),
    Test("F11 closure policy is fail-closed and forbids successor snapshots", () => {
      val policy = Check.BootstrapClosureRules.policy(Bootstrap.f11).fold(err => throw new AssertionError(err), identity)
      equal(policy.reproduction, "predecessor-plus-delta")
      equal(policy.deltaDecoding, "strict-canonical")
      equal(policy.dependency, "exact-predecessor-change")
      equal(policy.validation, "full")
      equal(policy.snapshot, "successor-forbidden")
      equal(policy.failure, "fail-closed")
      val steps = Check.BootstrapClosureRules.steps(Bootstrap.f11)
      check(steps.forall(_.snapshot == "forbidden"))
      check((1 to 11).forall(i => getClass.getResourceAsStream(s"/trellis/foundations/F$i.graph") == null))
    }),
    Test("F11 closure verification rejects a tampered derivation manifest", () => {
      val target = EntityId("bootstrap.step.F5")
      val original = Bootstrap.f11.entity(target).get
      val tampered = original.copy(attrs = original.attrs.updated("successor-root", "0".repeat(64)))
      val (withNode, id) = Canon.addNode(Bootstrap.f11, tampered)
      val graph = withNode.copy(entities = withNode.entities.updated(target, id))
      check(Check.BootstrapClosureRules.definitionErrors(graph).nonEmpty || Bootstrap.cleanRoomReproduce(graph).isLeft)
    })
  )
