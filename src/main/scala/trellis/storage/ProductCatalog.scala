package trellis.storage

import java.net.JarURLConnection
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import trellis.{Bootstrap, Canon, Check, Delta}
import trellis.Core.*
import trellis.Delta.*

/** Compiles, verifies, orders, and replays every authoritative post-foundation source. */
object ProductCatalog:
  final case class Product(name: String, change: Change, changeId: ChangeId, graph: Graph, root: ContentId, postActions: Vector[String], source: String)

  lazy val products: Vector[Product] =
    val actions = discoverPostActions()
    val sources = discoverSources()
    val remaining = scala.collection.mutable.Map.from(sources.map { case (name, source) =>
      val document = DeltaSource.parse(source).fold(error => throw IllegalStateException(s"$name: $error"), identity)
      if document.name != name then throw IllegalStateException(s"$name source declares ${document.name}")
      name -> (document, source)
    })
    val built = scala.collection.mutable.ArrayBuffer.empty[Product]
    var predecessor = ChangeId(Bootstrap.F11ChangeId)
    var predecessorName = s"@${Bootstrap.F11ChangeId}"
    var dependencyIds = Map(predecessorName -> predecessor)
    var graph = Bootstrap.graph
    while remaining.nonEmpty do
      val candidates = remaining.toVector.collect { case (name, (document, source)) if document.dependencies == Vector(predecessorName) => (name, document, source) }
      if candidates.size != 1 then throw IllegalStateException(s"product chain after ${predecessor.value} has ${candidates.size} successors")
      val (name, document, source) = candidates.head
      val (change, nextGraph) = DeltaSource.compile(document, graph, dependencyIds).fold(error => throw IllegalStateException(s"$name: $error"), identity)
      val id = Change.id(change)
      graph = nextGraph
      val postActions = actions.getOrElse(name, Vector.empty)
      if postActions.isEmpty then throw IllegalStateException(s"$name declares no post-action")
      PostActions.run(postActions, graph, Map(
        "validate-graph" -> { candidate =>
          val errors = Check.validate(candidate)
          if errors.isEmpty then Right(()) else Left(errors.mkString("; "))
        }
      )).fold(error => throw IllegalStateException(s"$name: $error"), identity)
      built += Product(name, change, id, graph, Canon.graphId(graph), postActions, source)
      remaining -= name
      predecessor = id
      predecessorName = name
      dependencyIds += name -> id
    built.toVector

  def named(name: String): Product = products.find(_.name == name).getOrElse(throw new NoSuchElementException(s"unknown product $name"))
  def graph(name: String): Graph = named(name).graph
  def introducing(entity: EntityId): Product =
    products.zipWithIndex.collectFirst {
      case (product, index) if product.graph.entities.contains(entity) && !precedingGraph(index).entities.contains(entity) => product
    }.getOrElse(throw new NoSuchElementException(s"no product introduces ${entity.value}"))
  def predecessor(product: Product): Graph =
    val index = products.indexWhere(_.changeId == product.changeId)
    if index < 0 then throw new NoSuchElementException(s"unknown product ${product.changeId.value}")
    else if index == 0 then Bootstrap.graph
    else products(index - 1).graph
  def predecessorChangeId(product: Product): ChangeId =
    val index = products.indexWhere(_.changeId == product.changeId)
    if index < 0 then throw new NoSuchElementException(s"unknown product ${product.changeId.value}")
    else if index == 0 then ChangeId(Bootstrap.F11ChangeId)
    else products(index - 1).changeId
  def graphIntroducing(entity: EntityId): Graph = introducing(entity).graph
  def graphBefore(entity: EntityId): Graph = predecessor(introducing(entity))
  def latest: Graph = products.lastOption.map(_.graph).getOrElse(Bootstrap.graph)

  private def precedingGraph(index: Int): Graph = if index == 0 then Bootstrap.graph else products(index - 1).graph

  private def discoverSources(): Map[String, String] =
    val loader = getClass.getClassLoader
    val url = Option(loader.getResource("trellis/products/source")).getOrElse(throw IllegalStateException("missing readable product sources"))
    val values: Vector[(String, String)] = url.getProtocol match
      case "file" =>
        val stream = Files.walk(Path.of(url.toURI))
        try stream.iterator.asScala.filter(path => path.getFileName.toString.endsWith(".delta")).toVector.sortBy(_.getFileName.toString).map(path => path.getFileName.toString.stripSuffix(".delta") -> Files.readString(path))
        finally stream.close()
      case "jar" =>
        val jar = url.openConnection().asInstanceOf[JarURLConnection].getJarFile
        jar.entries.asScala.filter(entry => !entry.isDirectory && entry.getName.startsWith("trellis/products/source/") && entry.getName.endsWith(".delta")).toVector.sortBy(_.getName).map { entry =>
          val input = jar.getInputStream(entry)
          try entry.getName.split('/').last.stripSuffix(".delta") -> new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally input.close()
        }
      case protocol => throw IllegalStateException(s"unsupported product source protocol $protocol")
    values.toMap

  private def discoverPostActions(): Map[String, Vector[String]] =
    val loader = getClass.getClassLoader
    val url = Option(loader.getResource("trellis/products")).getOrElse(throw IllegalStateException("missing product resources"))
    val manifests: Vector[(String, String)] = url.getProtocol match
      case "file" =>
        val stream = Files.list(Path.of(url.toURI))
        try stream.iterator.asScala.filter(path => path.getFileName.toString.endsWith(".delta")).toVector.sortBy(_.getFileName.toString).map(path => path.getFileName.toString -> Files.readString(path))
        finally stream.close()
      case "jar" =>
        val jar = url.openConnection().asInstanceOf[JarURLConnection].getJarFile
        jar.entries.asScala.filter { entry =>
          !entry.isDirectory && entry.getName.startsWith("trellis/products/") && entry.getName.endsWith(".delta") && entry.getName.stripPrefix("trellis/products/").count(_ == '/') == 0
        }.toVector.sortBy(_.getName).map { entry =>
          val input = jar.getInputStream(entry)
          try entry.getName.split('/').last -> new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally input.close()
        }
      case protocol => throw IllegalStateException(s"unsupported product resource protocol $protocol")

    manifests.flatMap { case (manifest, source) =>
      var included: Option[String] = None
      val document = ManifestLanguage.parse(source).fold(error => throw IllegalStateException(s"$manifest: $error"), identity)
      document.lines.zipWithIndex.flatMap { case (line, index) =>
        line.tag match
          case "delta-set" | "delta-package" | "purpose" | "provides" | "requires" | "imports" | "conflicts" => None
          case "include" =>
            val path = line.args.head
            if path.startsWith("/") || path.split('/').contains("..") then throw IllegalStateException(s"$manifest:${index + 1}: unsafe include $path")
            included = Some(path.split('/').last.stripSuffix(".delta"))
            None
          case "post-action" =>
            val product = included.getOrElse(throw IllegalStateException(s"$manifest:${index + 1}: post-action precedes include"))
            Some(product -> line.args.head)
          case directive => throw IllegalStateException(s"$manifest:${index + 1}: $directive is not valid in a delta set")
      }
    }.groupMap(_._1)(_._2)
