package trellis.storage

import java.net.JarURLConnection
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import trellis.Delta
import trellis.Core.*
import trellis.Delta.*
import trellis.storage.Composition.*

/** Loads capability packages and profiles from resources without product-name switches. */
object CompositionCatalog:
  private final case class ParsedPackage(packageValue: Package, changes: Map[ChangeId, Change])
  private final case class Loaded(registry: Registry, changes: Map[ChangeId, Change], universe: Graph)

  private lazy val loaded: Loaded =
    val packageResources = discover("trellis/products", ".delta") ++ discoverOptional("trellis/composition", ".delta")
    val parsed = packageResources.map(parsePackage)
    val changes = parsed.flatMap(_.changes).toMap
    if changes.size != parsed.map(_.changes.size).sum then throw IllegalStateException("composition resources repeat a change id")
    val registry = Registry(parsed.map(_.packageValue), discover("trellis/profiles", ".profile").map(parseProfile))
    val productIds = ProductCatalog.products.map(_.changeId).toSet
    val supplemental = changes.toVector.filterNot { case (id, _) => productIds(id) }.sortBy(_._1.value).map(_._2)
    val universe = supplemental.foldLeft[Either[String, Graph]](Right(ProductCatalog.latest)) { (state, change) =>
      state.flatMap(graph => Delta.applyChange(graph, change))
    }.fold(error => throw IllegalStateException(s"cannot build discovery graph: $error"), identity)
    GraphContract.validateRegistry(registry, changes, universe).fold(error => throw IllegalStateException(error), identity)
    Loaded(registry, changes, universe)

  lazy val registry: Registry = loaded.registry

  def resolve(profile: String): Either[String, Lock] = Composition.resolve(registry, profile)

  def resolveAssembly(assembly: Assembly): Either[String, Lock] =
    Composition.resolve(registry.copy(profiles = registry.profiles :+ AssemblyLanguage.profile(assembly)), s"assembly:${assembly.id}")
      .flatMap(lock => AssemblyLanguage.validateSelection(assembly, lock).map(_ => lock))
      .flatMap(lock => GraphContract.validateSelection(lock, loaded.universe).map(_ => lock))
      .flatMap(lock => GraphContract.validateEndpoints(s"assembly ${assembly.id} exposes", assembly.exposes, loaded.universe).map(_ => lock))

  def materialize(
      profile: String,
      basis: Graph,
      basisChanges: Set[ChangeId],
      handlers: Map[String, PostActions.Handler]
  ): Either[String, SelectionApplication.Result] =
    resolve(profile).flatMap { lock =>
      val selectedIds = lock.fragments.map(_.changeId).toSet
      val selectedChanges = loaded.changes.view.filterKeys(selectedIds).toMap
      SelectionApplication.materialize(basis, basisChanges, lock, selectedChanges, handlers)
    }

  def compile(
      profile: String,
      basis: Graph,
      basisFrontier: Set[ChangeId],
      handlers: Map[String, PostActions.Handler]
  ): Either[String, SelectionCompiler.Result] =
    resolve(profile).flatMap { lock =>
      val selectedIds = lock.fragments.map(_.changeId).toSet
      val selectedChanges = loaded.changes.view.filterKeys(selectedIds).toMap
      SelectionCompiler.compile(basis, basisFrontier, lock, selectedChanges, handlers)
    }

  def compileAssembly(
      assembly: Assembly,
      basis: Graph,
      basisFrontier: Set[ChangeId],
      handlers: Map[String, PostActions.Handler]
  ): Either[String, SelectionCompiler.Result] =
    resolveAssembly(assembly).flatMap { lock =>
      val selectedIds = lock.fragments.map(_.changeId).toSet
      val selectedChanges = loaded.changes.view.filterKeys(selectedIds).toMap
      SelectionCompiler.compile(basis, basisFrontier, lock, selectedChanges, handlers)
    }.flatMap { result =>
      for
        _ <- GraphContract.validateSelection(result.lock, result.graph)
        _ <- GraphContract.validateEndpoints(s"assembly ${assembly.id} exposes", assembly.exposes, result.graph)
      yield result
    }

  private def parsePackage(resource: Resource): ParsedPackage =
    val document = ManifestLanguage.parse(resource.source).fold(error => throw IllegalStateException(s"${resource.name}: $error"), identity)
    val header = document.lines.headOption.filter(_.tag == "delta-package").getOrElse(throw IllegalStateException(s"${resource.name}: first directive must be delta-package"))
    val provides = document.lines.collect { case ManifestLanguage.Line("provides", Vector(capability), _) => capability }.toSet
    val requires = document.lines.collect { case ManifestLanguage.Line("requires", Vector(capability), _) => capability }.toSet
    val imports = document.lines.collect { case ManifestLanguage.Line("imports", Vector(capability), _) => capability }.toSet
    val conflicts = document.lines.collect { case ManifestLanguage.Line("conflicts", Vector(packageId), _) => packageId }.toSet
    val included = document.lines.foldLeft(Vector.empty[(String, Vector[String])]) {
      case (acc, ManifestLanguage.Line("include", Vector(path), _)) => acc :+ (path -> Vector.empty)
      case (init :+ (path, actions), ManifestLanguage.Line("post-action", Vector(action), _)) => init :+ (path -> (actions :+ action))
      case (Vector(), ManifestLanguage.Line("post-action", _, indent)) if !document.lines.exists(line => line.tag == "change" && line.indent < indent) => throw IllegalStateException(s"${resource.name}: post-action precedes include")
      case (acc, _) => acc
    }.map { case (path, actions) =>
      if path.startsWith("/") || path.split('/').contains("..") then throw IllegalStateException(s"${resource.name}: unsafe include $path")
      if !path.startsWith("source/") || !path.endsWith(".delta") then throw IllegalStateException(s"${resource.name}: include must name an authoritative source")
      val product = ProductCatalog.named(path.split('/').last.stripSuffix(".delta"))
      val change = product.change
      val id = product.changeId
      Fragment(id, actions) -> (id -> change)
    }
    val inline = parseInlineChanges(resource, document)
    val fragmentChanges = included ++ inline
    val fragments = fragmentChanges.map(_._1)
    if provides.isEmpty then throw IllegalStateException(s"${resource.name}: package ${header.args.head} provides no capability")
    if fragments.isEmpty then throw IllegalStateException(s"${resource.name}: package ${header.args.head} includes no fragments")
    ParsedPackage(Package(header.args.head, provides, requires, imports, conflicts, fragments), fragmentChanges.map(_._2).toMap)

  private def parseProfile(resource: Resource): Profile =
    val document = ManifestLanguage.parse(resource.source).fold(error => throw IllegalStateException(s"${resource.name}: $error"), identity)
    val header = document.lines.headOption.filter(_.tag == "profile").getOrElse(throw IllegalStateException(s"${resource.name}: first directive must be profile"))
    val requires = document.lines.collect { case ManifestLanguage.Line("requires", Vector(capability), _) => capability }.toSet
    val inherited = document.lines.collect { case ManifestLanguage.Line("extends", Vector(profile), _) => profile }.toSet
    val preferred = document.lines.collect { case ManifestLanguage.Line("prefer", Vector(capability, provider), _) => capability -> provider }.toMap
    val assumed = document.lines.collect { case ManifestLanguage.Line("assumes", Vector(capability), _) => capability }.toSet
    Profile(header.args.head, requires, inherited, preferred, assumed)

  private def parseInlineChanges(resource: Resource, document: ManifestLanguage.Document): Vector[(Fragment, (ChangeId, Change))] =
    val result = scala.collection.mutable.ArrayBuffer.empty[(Fragment, (ChangeId, Change))]
    var index = 0
    while index < document.lines.size do
      val line = document.lines(index)
      if line.tag != "change" then index += 1
      else
        val operations = scala.collection.mutable.ArrayBuffer.empty[Op]
        val actions = scala.collection.mutable.ArrayBuffer.empty[String]
        val changeIndent = line.indent
        index += 1
        while index < document.lines.size && document.lines(index).indent > changeIndent do
          val child = document.lines(index)
          child.tag match
            case "entity" =>
              val entityIndent = child.indent
              val attrs = scala.collection.mutable.Map.empty[String, String]
              index += 1
              while index < document.lines.size && document.lines(index).indent > entityIndent do
                val attr = document.lines(index)
                if attr.tag != "attr" then throw IllegalStateException(s"${resource.name}: ${attr.tag} is not valid beneath entity")
                attrs(attr.args.head) = attr.args.tail.mkString(" ")
                index += 1
              operations += Op.ReplaceEntity(EntityId(child.args.head), Node(child.args(1), attrs = attrs.toMap))
            case "post-action" => actions += child.args.head; index += 1
            case directive => throw IllegalStateException(s"${resource.name}: $directive is not valid beneath change")
        val change = Change(Set.empty, operations.toVector, line.args.mkString(" "), resource.name.stripSuffix(".delta"))
        val id = Change.id(change)
        result += Fragment(id, actions.toVector) -> (id -> change)
    result.toVector

  private final case class Resource(directory: String, name: String, source: String)

  private def discover(directory: String, suffix: String): Vector[Resource] =
    val loader = getClass.getClassLoader
    val url = Option(loader.getResource(directory)).getOrElse(throw IllegalStateException(s"missing resource directory $directory"))
    url.getProtocol match
      case "file" =>
        val stream = Files.list(Path.of(url.toURI))
        try stream.iterator.asScala.filter(path => path.getFileName.toString.endsWith(suffix)).toVector.sortBy(_.getFileName.toString).map(path => Resource(directory, path.getFileName.toString, Files.readString(path)))
        finally stream.close()
      case "jar" =>
        val jar = url.openConnection().asInstanceOf[JarURLConnection].getJarFile
        val prefix = s"$directory/"
        jar.entries.asScala.filter(entry => !entry.isDirectory && entry.getName.startsWith(prefix) && entry.getName.endsWith(suffix) && entry.getName.stripPrefix(prefix).count(_ == '/') == 0).toVector.sortBy(_.getName).map { entry =>
          val input = jar.getInputStream(entry)
          try Resource(directory, entry.getName.split('/').last, new String(input.readAllBytes(), StandardCharsets.UTF_8)) finally input.close()
        }
      case protocol => throw IllegalStateException(s"unsupported resource protocol $protocol")

  private def discoverOptional(directory: String, suffix: String): Vector[Resource] =
    if getClass.getClassLoader.getResource(directory) == null then Vector.empty else discover(directory, suffix)
