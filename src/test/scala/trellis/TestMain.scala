package trellis

import trellis.TestSupport.Test
import trellis.storage.{DeltaSourceTests, ProductCatalog}

object TestMain:
  def main(args: Array[String]): Unit =
    val coreSuites = Vector(
      "Canon" -> CanonTest.tests,
      "Foundation" -> FoundationTest.tests,
      "RepositoryProducts" -> RepositoryProductsTest.tests,
      "Composition" -> CompositionTest.tests,
      "Check" -> CheckTest.tests,
      "Machine" -> MachineTest.tests,
      "Projection" -> ProjectionTest.tests,
      "Architecture" -> ArchitectureTest.tests,
      "TrellisLanguage" -> TrellisLanguageTest.tests,
      "ExecutionEngine" -> ExecutionEngineTest.tests,
      "AgentApi" -> AgentApiTest.tests,
      "WorkspacePersistence" -> WorkspacePersistenceTest.tests
    )
    val deltaSuites = ProductCatalog.products.map { product =>
      product.name -> DeltaSourceTests.run(product).map { result =>
        Test(result.name, () => result.error.foreach(error => throw AssertionError(error)))
      }
    }
    val suites = coreSuites ++ deltaSuites
    var failed = 0
    suites.foreach { case (suite, tests) =>
      tests.foreach { test =>
        try
          test.run()
          println(s"[ok]   $suite :: ${test.name}")
        catch
          case t: Throwable =>
            failed += 1
            println(s"[FAIL] $suite :: ${test.name}")
            t.printStackTrace()
      }
    }
    val total = suites.map(_._2.size).sum
    println(s"$total tests, $failed failures")
    if failed != 0 then sys.exit(1)
