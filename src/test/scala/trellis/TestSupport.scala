package trellis

object TestSupport:
  final case class Test(name: String, run: () => Unit)

  def check(condition: Boolean, clue: => String = "assertion failed"): Unit =
    if !condition then throw new AssertionError(clue)

  def equal[A](obtained: A, expected: A): Unit =
    if obtained != expected then throw new AssertionError(s"obtained: $obtained\nexpected: $expected")
