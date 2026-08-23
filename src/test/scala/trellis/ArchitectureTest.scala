package trellis

import trellis.Core.*
import trellis.engine.*
import trellis.language.*
import trellis.storage.*
import trellis.TestSupport.*

object ArchitectureTest:
  private object ForeignSyntax extends SyntaxCodec:
    type Tree = String
    def parse(source: String): Either[String, String] = if source.nonEmpty then Right(source) else Left("empty source")
    def print(tree: String): Either[String, String] = Right(tree)

  /** Represents an independently supplied language package such as a Unison adapter. */
  private object ForeignLanguage extends LanguagePackage:
    type Tree = String
    val id = "example.foreign"
    val version = "1"
    val syntax: SyntaxCodec { type Tree = String } = ForeignSyntax
    def lower(tree: String, basis: Graph): Either[String, LoweredProgram] = Left("fixture has no execution lowering")

  private def right[A](value: Either[String, A]): A = value.fold(error => throw new AssertionError(error), identity)

  val tests = Vector(
    Test("storage accepts graphs without a language package", () => {
      val repository: Repository = MemoryRepository(trellis.storage.RepositoryProducts.graph)
      equal(repository.graph, trellis.storage.RepositoryProducts.graph)
    }),
    Test("language packages are replaceable behind one contract", () => {
      val packages: Vector[LanguagePackage] = Vector(trellis.languages.trellis.TrellisLanguage, ForeignLanguage)
      equal(packages.map(_.id), Vector("trellis", "example.foreign"))
      equal(right(ForeignLanguage.syntax.parse("foreign term")), "foreign term")
    }),
    Test("execution engines are selected independently of language syntax", () => {
      val request = ExecutionRequest(trellis.storage.RepositoryProducts.graph, EntityId("example.tailrec.fibonacci"), Vector(BigInt(10)))
      equal(right(Engines.named("deltanet").flatMap(_.execute(request))).value, BigInt(55))
      equal(right(Engines.named("ceskr").flatMap(_.execute(request))).value, BigInt(55))
    }),
    Test("execution IR rejects unknown node kinds before engine dispatch", () => {
      val unknown = Node("ir.foreign")
      val id = Canon.nodeId(unknown)
      val graph = Graph(nodes = Map(id -> unknown), entities = Map(EntityId("foreign.root") -> id))
      check(DeltaNetEngine.execute(ExecutionRequest(graph, EntityId("foreign.root"), Vector.empty)).isLeft)
    })
  )
