package trellis.storage

import scala.collection.mutable
import trellis.{Canon, Delta}
import trellis.Core.*
import trellis.Delta.*

object Composition:
  final case class Fragment(changeId: ChangeId, postActions: Vector[String] = Vector.empty)
  final case class Package(
      id: String,
      provides: Set[String],
      requires: Set[String] = Set.empty,
      imports: Set[String] = Set.empty,
      conflicts: Set[String] = Set.empty,
      fragments: Vector[Fragment] = Vector.empty
  )
  final case class Profile(
      id: String,
      requires: Set[String],
      extendsProfiles: Set[String] = Set.empty,
      preferredProviders: Map[String, String] = Map.empty,
      basisCapabilities: Set[String] = Set.empty
  )
  final case class Registry(packages: Vector[Package], profiles: Vector[Profile])
  final case class Lock(profile: String, packages: Vector[String], providers: Map[String, String], fragments: Vector[Fragment]):
    lazy val canonical: String = Canon.record("selection-lock", Vector(
      profile,
      Canon.record("packages", packages),
      Canon.record("providers", providers.toVector.sortBy(_._1).map { case (capability, provider) => Canon.record("provider", Vector(capability, provider)) }),
      Canon.record("fragments", fragments.map(fragment => Canon.record("fragment", Vector(fragment.changeId.value, Canon.record("post-actions", fragment.postActions)))))
    ))
    lazy val id: ContentId = ContentId(Canon.sha256(canonical))

  def resolve(registry: Registry, profileId: String): Either[String, Lock] =
    val packagesById = registry.packages.groupBy(_.id)
    packagesById.collectFirst { case (id, values) if values.size != 1 => id } match
      case Some(id) => Left(s"duplicate package $id")
      case None =>
        val profilesById = registry.profiles.groupBy(_.id)
        profilesById.collectFirst { case (id, values) if values.size != 1 => id } match
          case Some(id) => Left(s"duplicate profile $id")
          case None =>
            for
              expanded <- expandProfile(profileId, profilesById.view.mapValues(_.head).toMap)
              lock <- select(registry.packages, expanded)
            yield lock

  private final case class ExpandedProfile(id: String, requires: Set[String], preferred: Map[String, String], basisCapabilities: Set[String])

  private def expandProfile(id: String, profiles: Map[String, Profile]): Either[String, ExpandedProfile] =
    def visit(current: String, active: Vector[String]): Either[String, Vector[Profile]] =
      if active.contains(current) then Left(s"profile inheritance cycle: ${(active :+ current).mkString(" -> ")}")
      else profiles.get(current).toRight(s"unknown profile $current").flatMap { profile =>
        sequence(profile.extendsProfiles.toVector.sorted.map(visit(_, active :+ current))).map(_.flatten :+ profile)
      }
    visit(id, Vector.empty).flatMap { inherited =>
      val preferenceGroups = inherited.flatMap(_.preferredProviders).groupBy(_._1)
      preferenceGroups.collectFirst { case (capability, values) if values.map(_._2).distinct.size > 1 => capability } match
        case Some(capability) => Left(s"conflicting preferred providers for $capability")
        case None => Right(ExpandedProfile(id, inherited.flatMap(_.requires).toSet, inherited.flatMap(_.preferredProviders).toMap, inherited.flatMap(_.basisCapabilities).toSet))
    }

  private def select(packages: Vector[Package], profile: ExpandedProfile): Either[String, Lock] =
    val providersByCapability = packages.flatMap(pkg => pkg.provides.toVector.map(_ -> pkg)).groupMap(_._1)(_._2)
    val selected = mutable.LinkedHashMap.empty[String, Package]
    val decisions = mutable.Map.empty[String, String]
    val pending = mutable.PriorityQueue.empty[String](Ordering.String.reverse)
    pending.enqueue(profile.requires.toSeq*)
    var failure: Option[String] = None
    while pending.nonEmpty && failure.isEmpty do
      val capability = pending.dequeue()
      if !decisions.contains(capability) then
        if profile.basisCapabilities(capability) then decisions(capability) = "@basis"
        else
          val candidates = providersByCapability.getOrElse(capability, Vector.empty).sortBy(_.id)
          val chosen = profile.preferred.get(capability) match
            case Some(packageId) => candidates.find(_.id == packageId).toRight(s"preferred package $packageId does not provide $capability")
            case None => candidates match
              case Vector(one) => Right(one)
              case Vector() => Left(s"no provider for $capability")
              case many => Left(s"ambiguous providers for $capability: ${many.map(_.id).mkString(", ")}")
          chosen match
            case Left(error) => failure = Some(error)
            case Right(pkg) =>
              val conflict = selected.values.find(other => pkg.conflicts(other.id) || other.conflicts(pkg.id))
              conflict match
                case Some(other) => failure = Some(s"package ${pkg.id} conflicts with ${other.id}")
                case None =>
                  decisions(capability) = pkg.id
                  if !selected.contains(pkg.id) then
                    selected(pkg.id) = pkg
                    pending.enqueue((pkg.requires ++ pkg.imports).toSeq*)
    failure match
      case Some(error) => Left(error)
      case None =>
        topological(selected.values.toVector, decisions.toMap).map { ordered =>
          Lock(profile.id, ordered.map(_.id), decisions.toMap, ordered.flatMap(_.fragments))
        }

  private def topological(packages: Vector[Package], providers: Map[String, String]): Either[String, Vector[Package]] =
    val byId = packages.map(pkg => pkg.id -> pkg).toMap
    val dependencies = packages.map { pkg =>
      pkg.id -> (pkg.requires ++ pkg.imports).flatMap(providers.get).filter(_ != pkg.id).intersect(byId.keySet)
    }.toMap
    val remaining = mutable.Map.from(dependencies)
    val result = mutable.ArrayBuffer.empty[Package]
    while remaining.nonEmpty do
      val ready = remaining.collect { case (id, deps) if deps.forall(dep => !remaining.contains(dep)) => id }.toVector.sorted
      if ready.isEmpty then return Left(s"package dependency cycle: ${remaining.keys.toVector.sorted.mkString(", ")}")
      ready.foreach { id => result += byId(id); remaining -= id }
    Right(result.toVector)

  private def sequence[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) { (acc, value) => acc.flatMap(xs => value.map(xs :+ _)) }

/** A package contract names graph entities, optionally narrowed to one typed port. */
object GraphContract:
  final case class Endpoint(entity: EntityId, port: Option[String]):
    def render: String = entity.value + port.fold("")(name => s"#$name")

  final case class EndpointType(kind: String, port: Option[Port])

  def parse(value: String): Either[String, Endpoint] =
    val pieces = value.split("#", -1).toVector
    val (entity, port) = pieces match
      case Vector(path) => path -> None
      case Vector(path, name) if name.nonEmpty => path -> Some(name)
      case _ => return Left(s"invalid graph endpoint $value")
    val segments = entity.split("\\.", -1).toVector
    val validSegment = "[A-Za-z_][A-Za-z0-9_-]*".r
    if entity.startsWith("@") || segments.isEmpty || segments.exists(segment => validSegment.matches(segment) == false) then
      Left(s"invalid graph entity path $entity")
    else Right(Endpoint(EntityId(entity), port))

  def resolve(graph: Graph, value: String): Either[String, EndpointType] =
    for
      endpoint <- parse(value)
      node <- graph.entity(endpoint.entity).toRight(s"missing graph contract entity ${endpoint.entity.value}")
      port <- endpoint.port match
        case None => Right(None)
        case Some(name) => node.port(name).map(Some.apply).toRight(s"graph contract endpoint $value names no port")
    yield EndpointType(node.kind, port)

  def validateRegistry(registry: Composition.Registry, changes: Map[ChangeId, Change], universe: Graph): Either[String, Unit] =
    val declarations = registry.packages.flatMap(pkg => (pkg.provides ++ pkg.requires ++ pkg.imports).toVector.map(pkg.id -> _))
    val providers = registry.packages.flatMap(pkg => pkg.provides.toVector.map(_ -> pkg.id)).groupMap(_._1)(_._2)
    for
      _ <- sequence(declarations.map { case (pkg, endpoint) => parse(endpoint).left.map(error => s"package $pkg: $error") })
      _ <- sequence(registry.packages.flatMap(pkg => (pkg.requires ++ pkg.imports).toVector.sorted.map { endpoint =>
        providers.getOrElse(endpoint, Vector.empty) match
          case Vector(_) => Right(())
          case Vector() => Left(s"package ${pkg.id} requires $endpoint but discovery found no provider")
          case many => Left(s"package ${pkg.id} requires $endpoint but discovery found ambiguous providers ${many.sorted.mkString(", ")}")
      }))
      _ <- sequence(registry.packages.flatMap { pkg =>
        pkg.provides.toVector.sorted.map { endpoint =>
          for
            parsed <- parse(endpoint)
            endpointType <- resolve(universe, endpoint).left.map(error => s"package ${pkg.id} provides $endpoint: $error")
            _ <- Either.cond(endpointType.port.forall(_.direction == Direction.Out), (), s"package ${pkg.id} provides input port $endpoint")
            touched = pkg.fragments.flatMap(fragment => changes.get(fragment.changeId).toVector.flatMap(touchedEntities)).toSet
            _ <- Either.cond(touched(parsed.entity), (), s"package ${pkg.id} claims $endpoint without introducing or replacing ${parsed.entity.value}")
          yield ()
        }
      })
      _ <- sequence(registry.packages.flatMap(pkg => (pkg.requires ++ pkg.imports).toVector.sorted.map { endpoint =>
        resolve(universe, endpoint).left.map(error => s"package ${pkg.id} requires $endpoint: $error")
      }))
    yield ()

  def validateSelection(lock: Composition.Lock, graph: Graph): Either[String, Unit] =
    sequence(lock.providers.toVector.sortBy(_._1).map { case (endpoint, provider) =>
      resolve(graph, endpoint).left.map(error => s"selected provider $provider for $endpoint: $error")
    }).map(_ => ())

  def validateEndpoints(label: String, endpoints: Iterable[String], graph: Graph): Either[String, Unit] =
    sequence(endpoints.toVector.sorted.map(endpoint => resolve(graph, endpoint).left.map(error => s"$label $endpoint: $error"))).map(_ => ())

  private def touchedEntities(change: Change): Vector[EntityId] = change.operations.flatMap {
    case Op.BindEntity(entity, _) => Vector(entity)
    case Op.ReplaceEntity(entity, _) => Vector(entity)
    case Op.RemoveEntity(entity) => Vector(entity)
    case Op.RefineHole(entity, _) => Vector(entity)
    case _ => Vector.empty
  }

  private def sequence[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) { (acc, value) => acc.flatMap(xs => value.map(xs :+ _)) }

/** A table-described line grammar shared by delta sets, packages, and profiles. */
object ManifestLanguage:
  final case class DirectiveSpec(keyword: String, minArgs: Int, maxArgs: Int)
  final case class Line(tag: String, args: Vector[String], indent: Int)
  final case class Document(lines: Vector[Line])

  val directives: Vector[DirectiveSpec] = Vector(
    DirectiveSpec("delta-set", 1, 1), DirectiveSpec("delta-package", 1, 1), DirectiveSpec("profile", 1, 1),
    DirectiveSpec("purpose", 1, Int.MaxValue), DirectiveSpec("include", 1, 1), DirectiveSpec("post-action", 1, 1),
    DirectiveSpec("provides", 1, 1), DirectiveSpec("requires", 1, 1), DirectiveSpec("imports", 1, 1),
    DirectiveSpec("conflicts", 1, 1), DirectiveSpec("extends", 1, 1), DirectiveSpec("prefer", 2, 2),
    DirectiveSpec("assumes", 1, 1), DirectiveSpec("change", 1, Int.MaxValue),
    DirectiveSpec("entity", 2, 2), DirectiveSpec("attr", 2, Int.MaxValue),
    DirectiveSpec("assembly", 1, 1), DirectiveSpec("foundation", 1, 1), DirectiveSpec("base", 1, 1),
    DirectiveSpec("use", 1, 1), DirectiveSpec("omit", 1, 1), DirectiveSpec("expose", 1, 1),
    DirectiveSpec("verify", 1, 1), DirectiveSpec("emit", 1, 1)
  )
  private val byKeyword = directives.map(spec => spec.keyword -> spec).toMap

  def parse(source: String): Either[String, Document] =
    sequence(source.linesIterator.zipWithIndex.filterNot(_._1.trim.isEmpty).map { case (raw, index) =>
      val indentText = raw.takeWhile(_ == ' ')
      val words = raw.trim.split(' ').toVector.filter(_.nonEmpty)
      for
        _ <- if indentText.length % 2 == 0 then Right(()) else Left(s"line ${index + 1}: indentation must use pairs of spaces")
        keyword <- words.headOption.toRight(s"line ${index + 1}: empty directive")
        spec <- byKeyword.get(keyword).toRight(s"line ${index + 1}: unknown directive $keyword")
        args = words.tail
        _ <- if args.size >= spec.minArgs && args.size <= spec.maxArgs then Right(()) else Left(s"line ${index + 1}: $keyword expects ${spec.minArgs}..${spec.maxArgs} arguments")
      yield Line(keyword, args, indentText.length)
    }.toVector).map(Document.apply)

  def print(document: Document): Either[String, String] =
    sequence(document.lines.map { line =>
      byKeyword.get(line.tag).toRight(s"unknown directive ${line.tag}").flatMap { spec =>
        if line.args.size >= spec.minArgs && line.args.size <= spec.maxArgs && line.indent >= 0 && line.indent % 2 == 0 then Right(" " * line.indent + (line.tag +: line.args).mkString(" "))
        else Left(s"invalid ${line.tag} line")
      }
    }).map(_.mkString("\n") + "\n")

  private def sequence[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) { (acc, value) => acc.flatMap(xs => value.map(xs :+ _)) }

object PostActions:
  type Handler = Graph => Either[String, Unit]

  def run(names: Vector[String], graph: Graph, handlers: Map[String, Handler]): Either[String, Unit] =
    names.foldLeft[Either[String, Unit]](Right(())) { (result, name) =>
      result.flatMap(_ => handlers.get(name).toRight(s"unknown post-action $name").flatMap(_(graph)).left.map(error => s"post-action $name failed: $error"))
    }

object SelectionApplication:
  final case class Result(graph: Graph, applied: Vector[ChangeId])

  def materialize(
      basis: Graph,
      basisChanges: Set[ChangeId],
      lock: Composition.Lock,
      changes: Map[ChangeId, Change],
      handlers: Map[String, PostActions.Handler]
  ): Either[String, Result] =
    val fragments = lock.fragments
    val duplicate = fragments.groupBy(_.changeId).collectFirst { case (id, values) if values.size > 1 => id }
    duplicate match
      case Some(id) => Left(s"selection lock repeats fragment ${id.value}")
      case None =>
        val selectedIds = fragments.map(_.changeId).toSet
        changes.keys.find(id => !selectedIds(id)) match
          case Some(id) => Left(s"unselected change supplied: ${id.value}")
          case None =>
            changes.collectFirst { case (id, change) if Change.id(change) != id => id } match
              case Some(id) => Left(s"change payload does not match selected id ${id.value}")
              case None => selectedIds.find(id => !changes.contains(id)) match
                case Some(id) => Left(s"selected change missing: ${id.value}")
                case None =>
                  val missingDependency = fragments.iterator.flatMap { fragment =>
                    changes(fragment.changeId).dependencies.iterator
                      .filterNot(id => basisChanges(id) || selectedIds(id))
                      .map(dependency => fragment.changeId -> dependency)
                  }.toVector.headOption
                  missingDependency match
                    case Some((change, dependency)) => Left(s"selected change ${change.value} has unavailable dependency ${dependency.value}")
                    case None => applySelected(basis, basisChanges, fragments, changes, handlers)

  private def applySelected(
      basis: Graph,
      basisChanges: Set[ChangeId],
      fragments: Vector[Composition.Fragment],
      changes: Map[ChangeId, Change],
      handlers: Map[String, PostActions.Handler]
  ): Either[String, Result] =
    val remaining = mutable.Map.from(fragments.map(fragment => fragment.changeId -> fragment))
    val applied = mutable.ArrayBuffer.empty[ChangeId]
    var graph = basis
    var failure: Option[String] = None
    while remaining.nonEmpty && failure.isEmpty do
      val available = basisChanges ++ applied
      val ready = remaining.values.filter(fragment => changes(fragment.changeId).dependencies.subsetOf(available)).toVector.sortBy(_.changeId.value)
      if ready.isEmpty then failure = Some(s"selected change dependency cycle: ${remaining.keys.toVector.map(_.value).sorted.mkString(",")}")
      else
        ready.foreach { fragment =>
          if failure.isEmpty then
            Delta.applyChange(graph, changes(fragment.changeId)) match
              case Left(error) => failure = Some(s"change ${fragment.changeId.value} failed: $error")
              case Right(next) =>
                PostActions.run(fragment.postActions, next, handlers) match
                  case Left(error) => failure = Some(s"change ${fragment.changeId.value}: $error")
                  case Right(_) =>
                    graph = next
                    applied += fragment.changeId
                    remaining -= fragment.changeId
        }
    failure.toLeft(Result(graph, applied.toVector))

object SelectionCompiler:
  final case class Derivation(source: ChangeId, compiled: ChangeId)
  final case class Result(sourceLock: ContentId, lock: Composition.Lock, changes: Map[ChangeId, Change], graph: Graph, derivations: Vector[Derivation]):
    lazy val canonicalProvenance: String = Canon.record("selection-compilation", Vector(
      sourceLock.value,
      lock.id.value,
      Canon.record("derivations", derivations.map(item => Canon.record("derivation", Vector(item.source.value, item.compiled.value))))
    ))
    lazy val id: ContentId = ContentId(Canon.sha256(canonicalProvenance))

  /** Reissues selected operations over a deterministic linear frontier, preserving source-to-derived provenance. */
  def compile(
      basis: Graph,
      basisFrontier: Set[ChangeId],
      sourceLock: Composition.Lock,
      sourceChanges: Map[ChangeId, Change],
      handlers: Map[String, PostActions.Handler]
  ): Either[String, Result] =
    val expected = sourceLock.fragments.map(_.changeId).toSet
    sourceChanges.keys.find(id => !expected(id)) match
      case Some(id) => Left(s"unselected source change supplied: ${id.value}")
      case None =>
        expected.find(id => !sourceChanges.contains(id)) match
          case Some(id) => Left(s"selected source change missing: ${id.value}")
          case None =>
            sourceChanges.collectFirst { case (id, change) if Change.id(change) != id => id } match
              case Some(id) => Left(s"source payload does not match change id ${id.value}")
              case None => compileFragments(basis, basisFrontier, sourceLock, sourceChanges, handlers)

  private def compileFragments(
      basis: Graph,
      basisFrontier: Set[ChangeId],
      sourceLock: Composition.Lock,
      sourceChanges: Map[ChangeId, Change],
      handlers: Map[String, PostActions.Handler]
  ): Either[String, Result] =
    var graph = basis
    var frontier = basisFrontier
    val compiledChanges = mutable.LinkedHashMap.empty[ChangeId, Change]
    val compiledFragments = mutable.ArrayBuffer.empty[Composition.Fragment]
    val derivations = mutable.ArrayBuffer.empty[Derivation]
    var failure: Option[String] = None
    sourceLock.fragments.foreach { fragment =>
      if failure.isEmpty then
        val source = sourceChanges(fragment.changeId)
        val compiled = source.copy(dependencies = frontier)
        val compiledId = Change.id(compiled)
        Delta.applyChange(graph, compiled) match
          case Left(error) => failure = Some(s"source change ${fragment.changeId.value} cannot be compiled over selected frontier: $error")
          case Right(next) =>
            PostActions.run(fragment.postActions, next, handlers) match
              case Left(error) => failure = Some(s"compiled change ${compiledId.value}: $error")
              case Right(_) =>
                graph = next
                frontier = Set(compiledId)
                compiledChanges(compiledId) = compiled
                compiledFragments += fragment.copy(changeId = compiledId)
                derivations += Derivation(fragment.changeId, compiledId)
    }
    failure match
      case Some(error) => Left(error)
      case None =>
        val lock = sourceLock.copy(fragments = compiledFragments.toVector)
        Right(Result(sourceLock.id, lock, compiledChanges.toMap, graph, derivations.toVector))
