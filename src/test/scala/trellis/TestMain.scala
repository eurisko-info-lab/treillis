package trellis

object TestMain:
  def main(args: Array[String]): Unit =
    val suites = Vector(
      "Canon" -> CanonTest.tests,
      "Foundation" -> FoundationTest.tests,
      "Repo" -> RepoTest.tests,
      "Check" -> CheckTest.tests,
      "Machine" -> MachineTest.tests,
      "Projection" -> ProjectionTest.tests
    )
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
