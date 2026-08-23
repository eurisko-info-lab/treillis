package trellis

import trellis.Core.*
import trellis.Delta.*
import trellis.storage.{Composition, CompositionCatalog, DeltaSource, ManifestLanguage, ProductCatalog}
import trellis.storage.Composition.*
import trellis.storage.{PostActions, SelectionApplication}
import trellis.TestSupport.*

object CompositionTest:
  private def changeId(digit: Char): ChangeId = ChangeId(digit.toString * 64)
  private def right[A](value: Either[String, A]): A = value.fold(error => throw new AssertionError(error), identity)

  private val lambdaChange = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("common.lambda.apply"), Node("language.definition"))), "lambda")
  private val lambdaId = Change.id(lambdaChange)
  private val trellisChange = Change(Set(lambdaId), Vector(Op.ReplaceEntity(EntityId("language.trellis.syntax"), Node("language.package"))), "trellis")
  private val trellisId = Change.id(trellisChange)
  private val unisonChange = Change(Set(lambdaId), Vector(Op.ReplaceEntity(EntityId("language.unison.syntax"), Node("language.package"))), "unison")
  private val unisonId = Change.id(unisonChange)
  private val deltaNetChange = Change(Set(trellisId), Vector(Op.ReplaceEntity(EntityId("execution.deltanet"), Node("execution.engine"))), "deltanet")
  private val deltaNetId = Change.id(deltaNetChange)
  private val ceskrChange = Change(Set(trellisId), Vector(Op.ReplaceEntity(EntityId("execution.ceskr"), Node("execution.engine"))), "ceskr")
  private val ceskrId = Change.id(ceskrChange)

  private val lambda = Package(
    id = "common.lambda",
    provides = Set("language.core.lambda"),
    fragments = Vector(Fragment(lambdaId, Vector("validate-graph")))
  )
  private val trellis = Package(
    id = "language.trellis",
    provides = Set("language.trellis.syntax", "language.trellis.lowering"),
    imports = Set("language.core.lambda"),
    fragments = Vector(Fragment(trellisId, Vector("validate-graph")))
  )
  private val unison = Package(
    id = "language.unison",
    provides = Set("language.unison.syntax", "language.unison.lowering"),
    imports = Set("language.core.lambda"),
    fragments = Vector(Fragment(unisonId, Vector("validate-graph")))
  )
  private val deltaNet = Package(
    id = "execution.deltanet",
    provides = Set("execute.parallel"),
    requires = Set("language.trellis.lowering"),
    fragments = Vector(Fragment(deltaNetId, Vector("validate-graph")))
  )
  private val ceskr = Package(
    id = "execution.ceskr",
    provides = Set("execute.sequential", "debug.trace"),
    requires = Set("language.trellis.lowering"),
    fragments = Vector(Fragment(ceskrId, Vector("validate-graph")))
  )
  private val registry = Registry(
    Vector(lambda, trellis, unison, deltaNet, ceskr),
    Vector(
      Profile("production", Set("language.trellis.syntax", "execute.parallel")),
      Profile("polyglot-production", Set("language.trellis.syntax", "language.unison.syntax", "execute.parallel")),
      Profile("debug", Set("execute.sequential", "debug.trace"), extendsProfiles = Set("production"))
    )
  )
  private val changes = Map(
    lambdaId -> lambdaChange, trellisId -> trellisChange, unisonId -> unisonChange,
    deltaNetId -> deltaNetChange, ceskrId -> ceskrChange
  )
  private val handlers: Map[String, PostActions.Handler] = Map("validate-graph" -> (_ => Right(())))

  val tests = Vector(
    Test("delta block strings remove optional margins and round-trip", () => {
      val quotes = "\"\"\""
      val source =
        s"""delta-change "block-doc"
           |message "block docs"
           |author "test"
           |doc "guide" $quotes
           |  |# Heading
           |  |
           |  |Indented prose
           |  $quotes
           |test "contract"
           |  assert "valid"
           |""".stripMargin
      val document = right(DeltaSource.parse(source))
      equal(document.docs.head.value, "# Heading\n\nIndented prose\n")
      equal(right(DeltaSource.parse(DeltaSource.print(document))), document)
    }),
    Test("symbolic delta sources reproduce every canonical product change", () => {
      var basis = Bootstrap.graph
      var dependencyNames = Map(ChangeId(Bootstrap.F11ChangeId) -> s"@${Bootstrap.F11ChangeId}")
      var dependencyIds = Map(s"@${Bootstrap.F11ChangeId}" -> ChangeId(Bootstrap.F11ChangeId))
      ProductCatalog.products.foreach { product =>
        val source = right(DeltaSource.render(product.name, product.change, basis, dependencyNames))
        val document = right(DeltaSource.parse(source))
        val (change, graph) = right(DeltaSource.compile(document, basis, dependencyIds))
        equal(Delta.encodeChange(change), Delta.encodeChange(product.change))
        equal(Canon.encodeGraph(graph), Canon.encodeGraph(product.graph))
        basis = graph
        dependencyNames += product.changeId -> product.name
        dependencyIds += product.name -> product.changeId
      }
    }),
    Test("manifest grammar round-trips package and profile directives", () => {
      val source =
        """delta-package language.trellis
          |  imports language.core.lambda
          |  provides language.trellis.syntax
          |  include source/TrellisSyntax.delta
          |    post-action validate-graph
          |""".stripMargin
      val document = right(ManifestLanguage.parse(source))
      equal(right(ManifestLanguage.print(document)), source)
    }),
    Test("production profile excludes the sequential debugger", () => {
      val lock = right(Composition.resolve(registry, "production"))
      equal(lock.packages, Vector("common.lambda", "language.trellis", "execution.deltanet"))
      check(!lock.packages.contains("execution.ceskr"))
      equal(lock.providers("execute.parallel"), "execution.deltanet")
    }),
    Test("debug profile adds CESK-R and trace capabilities", () => {
      val lock = right(Composition.resolve(registry, "debug"))
      equal(lock.packages, Vector("common.lambda", "language.trellis", "execution.ceskr", "execution.deltanet"))
      equal(lock.providers("execute.sequential"), "execution.ceskr")
      equal(lock.providers("debug.trace"), "execution.ceskr")
    }),
    Test("production materialization physically omits CESK-R", () => {
      val lock = right(Composition.resolve(registry, "production"))
      val selected = changes.view.filterKeys(lock.fragments.map(_.changeId).toSet).toMap
      val result = right(SelectionApplication.materialize(Bootstrap.graph, Set.empty, lock, selected, handlers))
      check(result.graph.entities.contains(EntityId("common.lambda.apply")))
      check(result.graph.entities.contains(EntityId("language.trellis.syntax")))
      check(result.graph.entities.contains(EntityId("execution.deltanet")))
      check(!result.graph.entities.contains(EntityId("execution.ceskr")))
    }),
    Test("debug materialization includes both execution engines", () => {
      val lock = right(Composition.resolve(registry, "debug"))
      val selected = changes.view.filterKeys(lock.fragments.map(_.changeId).toSet).toMap
      val result = right(SelectionApplication.materialize(Bootstrap.graph, Set.empty, lock, selected, handlers))
      check(result.graph.entities.contains(EntityId("execution.deltanet")))
      check(result.graph.entities.contains(EntityId("execution.ceskr")))
    }),
    Test("materialization rejects a selected fragment with an omitted content dependency", () => {
      val lock = right(Composition.resolve(registry, "production"))
      val broken = deltaNetChange.copy(dependencies = Set(ceskrId))
      val brokenId = Change.id(broken)
      val brokenLock = lock.copy(fragments = lock.fragments.map(fragment => if fragment.changeId == deltaNetId then fragment.copy(changeId = brokenId) else fragment))
      val selected = changes.view.filterKeys(lock.fragments.map(_.changeId).toSet - deltaNetId).toMap.updated(brokenId, broken)
      check(SelectionApplication.materialize(Bootstrap.graph, Set.empty, brokenLock, selected, handlers).left.exists(_.contains("unavailable dependency")))
    }),
    Test("several languages share one Lambda Calculus import", () => {
      val lock = right(Composition.resolve(registry, "polyglot-production"))
      equal(lock.packages.count(_ == "common.lambda"), 1)
      equal(lock.providers("language.core.lambda"), "common.lambda")
      check(lock.packages.contains("language.trellis"))
      check(lock.packages.contains("language.unison"))
    }),
    Test("selection locks are canonical and reject ambiguous providers", () => {
      val first = right(Composition.resolve(registry, "production"))
      val reordered = registry.copy(packages = registry.packages.reverse, profiles = registry.profiles.reverse)
      val second = right(Composition.resolve(reordered, "production"))
      equal(first.canonical, second.canonical)
      equal(first.id, second.id)
      val alternate = deltaNet.copy(id = "execution.deltanet.alternate", fragments = Vector(Fragment(changeId('6'))))
      check(Composition.resolve(registry.copy(packages = registry.packages :+ alternate), "production").left.exists(_.contains("ambiguous providers")))
    }),
    Test("resource-backed full profile resolves every current product fragment", () => {
      val lock = right(CompositionCatalog.resolve("full"))
      equal(lock.packages, Vector("common.lambda", "language.trellis", "execution.ceskr", "studio.navigation", "studio.core", "optimization.core", "optimization.differential", "storage.network", "studio.runtime", "example.fibonacci"))
      check(ProductCatalog.products.map(_.changeId).toSet.subsetOf(lock.fragments.map(_.changeId).toSet))
      equal(lock.fragments.size, ProductCatalog.products.size + 1)
    }),
    Test("resource production profile physically omits CESK-R changes", () => {
      val validating: Map[String, PostActions.Handler] = Map("validate-graph" -> { graph =>
        val errors = Check.validate(graph)
        if errors.isEmpty then Right(()) else Left(errors.mkString("; "))
      })
      val result = right(CompositionCatalog.materialize("production", Bootstrap.graph, Set(ChangeId(Bootstrap.F11ChangeId)), validating))
      check(result.graph.entities.contains(EntityId("session-protocols.schema")))
      check(result.graph.entities.contains(EntityId("common.lambda.law.beta")))
      check(result.graph.entities.contains(EntityId("deltanet.policy.parallel")))
      check(!result.graph.entities.contains(EntityId("ceskr-transitions.schema")))
      check(!result.graph.entities.contains(EntityId("ceskr-traces.schema")))
      check(!result.graph.entities.contains(EntityId("studio-navigator.schema")))
      equal(result.applied.size, 17)
    }),
    Test("resource debug profile adds actual CESK-R and trace changes", () => {
      val result = right(CompositionCatalog.materialize("debug", Bootstrap.graph, Set(ChangeId(Bootstrap.F11ChangeId)), Map("validate-graph" -> (_ => Right(())))))
      check(result.graph.entities.contains(EntityId("ceskr-transitions.schema")))
      check(result.graph.entities.contains(EntityId("ceskr-traces.schema")))
      check(result.graph.entities.contains(EntityId("common.lambda.term.application")))
      check(!result.graph.entities.contains(EntityId("studio-navigator.schema")))
      equal(result.applied.size, 19)
    }),
    Test("production IDE compiles later actual fragments over a debugger-free frontier", () => {
      val validating: Map[String, PostActions.Handler] = Map("validate-graph" -> { graph =>
        val errors = Check.validate(graph)
        if errors.isEmpty then Right(()) else Left(errors.mkString("; "))
      })
      val compiled = right(CompositionCatalog.compile("production-ide", Bootstrap.graph, Set(ChangeId(Bootstrap.F11ChangeId)), validating))
      check(compiled.graph.entities.contains(EntityId("studio-navigator.schema")))
      check(compiled.graph.entities.contains(EntityId("lsp-adapter.schema")))
      check(compiled.graph.entities.contains(EntityId("example.tailrec.fibonacci")))
      check(compiled.graph.entities.contains(EntityId("common.lambda.schema")))
      check(!compiled.graph.entities.contains(EntityId("ceskr-transitions.schema")))
      check(!compiled.graph.entities.contains(EntityId("ceskr-traces.schema")))
      check(!compiled.graph.entities.contains(EntityId("differential-certificates.schema")))
      equal(compiled.derivations.size, 35)
      check(compiled.derivations.exists(item => item.source != item.compiled))
      val replayed = right(SelectionApplication.materialize(Bootstrap.graph, Set(ChangeId(Bootstrap.F11ChangeId)), compiled.lock, compiled.changes, validating))
      equal(Canon.graphId(replayed.graph), Canon.graphId(compiled.graph))
      equal(replayed.applied, compiled.lock.fragments.map(_.changeId))
    })
  )
