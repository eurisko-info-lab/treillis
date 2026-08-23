package trellis.storage

import java.util.Arrays
import trellis.{Canon, Check, Delta}
import trellis.Core.*

object DeltaSourceTests:
  final case class Result(name: String, error: Option[String])

  def run(product: ProductCatalog.Product): Vector[Result] =
    val document = DeltaSource.parse(product.source).fold(error => throw IllegalStateException(error), identity)
    document.tests.map { test =>
      val errors = test.assertions.flatMap(assertion => evaluate(product, document, assertion).left.toOption)
      Result(test.name, errors.headOption)
    }

  private def evaluate(product: ProductCatalog.Product, document: DeltaSource.Document, assertion: DeltaSource.Assertion): Either[String, Unit] =
    assertion.kind -> assertion.arguments match
      case "canonical" -> Vector() =>
        val replayed = Delta.applyChange(ProductCatalog.predecessor(product), product.change)
        replayed.flatMap { graph =>
          if ChangeId(Delta.Change.id(product.change).value) != product.changeId then Left("change id is not canonical")
          else if !Arrays.equals(Canon.encodeGraphBytes(graph), Canon.encodeGraphBytes(product.graph)) then Left("canonical replay differs from catalog graph")
          else Right(())
        }
      case "valid" -> Vector() =>
        val errors = Check.validate(product.graph)
        if errors.isEmpty then Right(()) else Left(errors.mkString("; "))
      case "source-roundtrip" -> Vector() =>
        DeltaSource.parse(DeltaSource.print(document)).flatMap { reparsed =>
          if reparsed == document then Right(()) else Left("delta source changes meaning after print and parse")
        }
      case "dependency" -> Vector(expected) =>
        if document.dependencies == Vector(expected) then Right(()) else Left(s"expected dependency $expected, found ${document.dependencies.mkString(",")}")
      case "introduces" -> Vector(entity, kind) =>
        val id = EntityId(entity)
        if ProductCatalog.predecessor(product).entities.contains(id) then Left(s"$entity already exists in predecessor")
        else product.graph.entity(id) match
          case Some(node) if node.kind == kind => Right(())
          case Some(node) => Left(s"$entity has kind ${node.kind}, expected $kind")
          case None => Left(s"$entity is missing")
      case "defines" -> Vector(entity, kind) =>
        product.graph.entity(EntityId(entity)) match
          case Some(node) if node.kind == kind => Right(())
          case Some(node) => Left(s"$entity has kind ${node.kind}, expected $kind")
          case None => Left(s"$entity is missing")
      case kind -> arguments => Left(s"unknown delta assertion $kind/${arguments.size}")
