package trellis

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import trellis.Core.*
import trellis.Delta.*
import trellis.Repo.*

object Main:
  def main(args: Array[String]): Unit =
    args.headOption match
      case Some("dump") => println(Canon.encodeGraph(Bootstrap.graph))
      case Some("hash") => println(Canon.graphId(Bootstrap.graph).value)
      case Some("svg") => writeOrPrint(args.drop(1).headOption, Project.Svg.render(Bootstrap.graph).content)
      case Some("typst") => writeOrPrint(args.drop(1).headOption, Project.Typst.render(Bootstrap.graph).content)
      case _ => demo()

  private def demo(): Unit =
    val base = Bootstrap.graph
    println(s"bootstrap graph: ${Canon.graphId(base).value}")
    println(s"nodes: ${base.nodes.size}, entities: ${base.entities.size}")

    val publication = Publication(
      PublicationId("genesis-demo"),
      "trellis/application/default",
      "stable",
      Set.empty,
      Canon.graphId(base),
      "trellis-foundation",
      "demo-signature"
    )
    val branchId = BranchId("local/demo")
    val branch = branchFromPublication(branchId, base, publication).fold(err => throw new IllegalStateException(err), identity)
    var store = Store().addBranch(branch)

    val hello = Node("app.function", attrs = Map("name" -> "hello", "result" -> "Unit"))
    val change = Change(Set.empty, Vector(Op.ReplaceEntity(EntityId("app.hello"), hello)), "add hello function", "demo-ai")
    store = advance(store, branchId, change).fold(err => throw new IllegalStateException(err), identity)
    val materialized = materialize(store, store.branches(branchId)).fold(err => throw new IllegalStateException(err), identity)

    println(s"local graph:     ${Canon.graphId(materialized.graph).value}")
    println(s"changes applied: ${materialized.applied.size}")
    provenance(store.branches(branchId)).foreach { p =>
      println(s"upstream basis:  ${p.packageName}/${p.branch} @ ${p.basisRoot.value.take(12)}")
    }
    println("\nCode View:\n" + Project.CodeView.render(materialized.graph).content.linesIterator.take(8).mkString("\n"))

  private def writeOrPrint(path: Option[String], content: String): Unit = path match
    case None => println(content)
    case Some(p) =>
      Files.writeString(Path.of(p), content, StandardCharsets.UTF_8)
      println(s"wrote $p")
