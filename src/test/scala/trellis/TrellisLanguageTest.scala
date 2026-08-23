package trellis

import trellis.TestSupport.*
import trellis.languages.trellis.TrellisLanguage

object TrellisLanguageTest:
  private val fibonacci =
    """module example.tailrec {
      |  fn fibonacci(n: Nat) -> Nat =
      |    loop(n, Zero(), Succ(Zero()));
      |  fn loop(remaining: Nat, current: Nat, next: Nat) -> Nat =
      |    match remaining
      |      Zero -> current
      |      Succ(rest) -> loop(rest, next, current + next)
      |}""".stripMargin

  private def right[A](value: Either[String, A]): A = value.fold(error => throw new AssertionError(error), identity)

  val tests = Vector(
    Test("Trellis grammar parses the tail-recursive Fibonacci definition", () => {
      val tree = right(TrellisLanguage.parse(fibonacci))
      equal(tree.tag, "Module")
      tree.fields(1) match
        case TrellisLanguage.Field.Nodes(functions) => equal(functions.map(_.tag), Vector("Function", "Function"))
        case other => throw new AssertionError(s"expected functions, found $other")
    }),
    Test("Trellis grammar and printer round-trip canonically", () => {
      val first = right(TrellisLanguage.parse(fibonacci))
      val canonical = right(TrellisLanguage.print(first))
      val second = right(TrellisLanguage.parse(canonical))
      equal(second, first)
      equal(right(TrellisLanguage.print(second)), canonical)
    }),
    Test("Trellis parser rejects incomplete definitions", () => {
      check(TrellisLanguage.parse("module broken { fn f(x: Nat) -> Nat = }").isLeft)
    }),
    Test("Trellis function bodies must be indented", () => {
      val source = "module broken {\n  fn f(x: Nat) -> Nat =\n  x\n}"
      check(TrellisLanguage.parse(source).isLeft)
    }),
    Test("Trellis match cases must align", () => {
      val source =
        """module broken {
          |  fn f(x: Nat) -> Nat =
          |    match x
          |      Zero -> x
          |       Succ(rest) -> rest
          |}""".stripMargin
      check(TrellisLanguage.parse(source).isLeft)
    }),
    Test("Trellis grammar has no construct-specific parser code", () => {
      val tags = TrellisLanguage.grammar.categories.flatMap(_.constructors.map(_.tag)).toSet
      check(Set("Module", "Function", "Match", "Call", "Add", "Reference").subsetOf(tags))
    })
  )
