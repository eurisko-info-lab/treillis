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
      case Some("dump-f0") => println(Canon.encodeGraph(Bootstrap.f0))
      case Some("dump-f1") => println(Canon.encodeGraph(Bootstrap.f1))
      case Some("delta-f1") => println(Delta.encodeChange(Bootstrap.f1Change))
      case Some("delta-f2") => println(Delta.encodeChange(Bootstrap.f2Change))
      case Some("hash") => println(Canon.graphId(Bootstrap.graph).value)
      case Some("hash-f0") => println(Canon.graphId(Bootstrap.f0).value)
      case Some("hash-f1") => println(Canon.graphId(Bootstrap.f1).value)
      case Some("hash-f2") => println(Canon.graphId(Bootstrap.f2).value)
      case Some("svg") => writeOrPrint(args.drop(1).headOption, Project.Svg.render(Bootstrap.graph).content)
      case Some("typst") => writeOrPrint(args.drop(1).headOption, Project.Typst.render(Bootstrap.graph).content)
      case _ => demo()

  private def demo(): Unit =
    val base = Bootstrap.graph
    println(s"F0 root:         ${Bootstrap.F0Root}")
    println(s"F1 delta:        ${Bootstrap.F1ChangeId}")
    println(s"F1 root:         ${Bootstrap.F1Root}")
    println(s"F2 delta:        ${Bootstrap.F2ChangeId}")
    println(s"F2 root:         ${Canon.graphId(base).value}")
    println(s"nodes: ${base.nodes.size}, edges: ${base.edges.size}, entities: ${base.entities.size}")
    println(s"resource rules:  ${Check.ResourceRules.rules(base).size}")

    val publication = Publication(
      PublicationId("foundation-f2-demo"),
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
    println("\nCode View:\n" + Project.CodeView.render(materialized.graph).content.linesIterator.take(12).mkString("\n"))

  private def writeOrPrint(path: Option[String], content: String): Unit = path match
    case None => println(content)
    case Some(p) =>
      Files.writeString(Path.of(p), content, StandardCharsets.UTF_8)
      println(s"wrote $p")
