package trellis.languages.trellis

import scala.collection.mutable
import trellis.Core.*
import trellis.language.*

/** Concrete Trellis source syntax, described as data and interpreted generically. */
object TrellisLanguage extends LanguagePackage:
  final case class Span(start: Int, end: Int)
  final case class Token(text: String, span: Span, line: Int, column: Int)
  final case class Tree(tag: String, fields: Vector[Field])
  enum Field:
    case Leaf(value: String)
    case Node(value: Tree)
    case Nodes(values: Vector[Tree])

  enum Elem:
    case Lit(text: String)
    case Name
    case Category(name: String)
    case SepBy(category: String, separator: String, allowEmpty: Boolean)
    case Block(category: String, min: Int, max: Int)

  final case class Constructor(tag: String, elements: Vector[Elem])
  final case class Category(name: String, constructors: Vector[Constructor])
  final case class Grammar(start: String, punctuation: Vector[String], keywords: Set[String], categories: Vector[Category])

  enum Segment:
    case Text(value: String)
    case Field(index: Int)
    case Child(index: Int)
    case Children(index: Int, separator: String)
    case Block(index: Int, separator: String)
  final case class PrintRule(tag: String, segments: Vector[Segment])

  val grammar = Grammar(
    start = "Module",
    punctuation = Vector("->", "(", ")", "{", "}", ":", ",", "=", "+", ";"),
    keywords = Set("module", "fn", "match"),
    categories = Vector(
      Category("Module", Vector(Constructor("Module", Vector(Elem.Lit("module"), Elem.Name, Elem.Lit("{"), Elem.SepBy("Function", ";", true), Elem.Lit("}"))))),
      Category("Function", Vector(Constructor("Function", Vector(Elem.Lit("fn"), Elem.Name, Elem.Lit("("), Elem.SepBy("Parameter", ",", true), Elem.Lit(")"), Elem.Lit("->"), Elem.Name, Elem.Lit("="), Elem.Block("Expr", 1, 1))))),
      Category("Parameter", Vector(Constructor("Parameter", Vector(Elem.Name, Elem.Lit(":"), Elem.Name)))),
      Category("Expr", Vector(
        Constructor("Match", Vector(Elem.Lit("match"), Elem.Category("Expr"), Elem.Block("Case", 1, Int.MaxValue))),
        Constructor("Add", Vector(Elem.Category("Atom"), Elem.Lit("+"), Elem.Category("Expr"))),
        Constructor("AtomExpr", Vector(Elem.Category("Atom")))
      )),
      Category("Atom", Vector(
        Constructor("Call", Vector(Elem.Name, Elem.Lit("("), Elem.SepBy("Expr", ",", true), Elem.Lit(")"))),
        Constructor("Reference", Vector(Elem.Name)),
        Constructor("Parenthesized", Vector(Elem.Lit("("), Elem.Category("Expr"), Elem.Lit(")")))
      )),
      Category("Case", Vector(Constructor("Case", Vector(Elem.Category("Pattern"), Elem.Lit("->"), Elem.Category("Expr"))))),
      Category("Pattern", Vector(
        Constructor("ConstructorPattern", Vector(Elem.Name, Elem.Lit("("), Elem.Name, Elem.Lit(")"))),
        Constructor("NullaryPattern", Vector(Elem.Name))
      ))
    )
  )

  private val printRules = Vector(
    PrintRule("Module", Vector(Segment.Text("module "), Segment.Field(0), Segment.Text(" {"), Segment.Block(1, ";"), Segment.Text("\n}"))),
    PrintRule("Function", Vector(Segment.Text("fn "), Segment.Field(0), Segment.Text("("), Segment.Children(1, ", "), Segment.Text(") -> "), Segment.Field(2), Segment.Text(" ="), Segment.Block(3, ""))),
    PrintRule("Parameter", Vector(Segment.Field(0), Segment.Text(": "), Segment.Field(1))),
    PrintRule("Match", Vector(Segment.Text("match "), Segment.Child(0), Segment.Block(1, ""))),
    PrintRule("Add", Vector(Segment.Child(0), Segment.Text(" + "), Segment.Child(1))),
    PrintRule("AtomExpr", Vector(Segment.Child(0))),
    PrintRule("Call", Vector(Segment.Field(0), Segment.Text("("), Segment.Children(1, ", "), Segment.Text(")"))),
    PrintRule("Reference", Vector(Segment.Field(0))),
    PrintRule("Parenthesized", Vector(Segment.Text("("), Segment.Child(0), Segment.Text(")"))),
    PrintRule("Case", Vector(Segment.Child(0), Segment.Text(" -> "), Segment.Child(1))),
    PrintRule("ConstructorPattern", Vector(Segment.Field(0), Segment.Text("("), Segment.Field(1), Segment.Text(")"))),
    PrintRule("NullaryPattern", Vector(Segment.Field(0)))
  ).map(rule => rule.tag -> rule).toMap

  def tokenize(source: String, spec: Grammar = grammar): Either[String, Vector[Token]] =
    val punctuation = spec.punctuation.sortBy(value => -value.length)
    val out = mutable.ArrayBuffer.empty[Token]
    var at = 0
    var line = 0
    var column = 0
    while at < source.length do
      if source.charAt(at) == '\n' then
        at += 1; line += 1; column = 0
      else if source.charAt(at).isWhitespace then
        at += 1; column += 1
      else
        punctuation.find(source.startsWith(_, at)) match
          case Some(value) =>
            out += Token(value, Span(at, at + value.length), line, column); at += value.length; column += value.length
          case None if source.charAt(at).isLetter || source.charAt(at) == '_' =>
            val start = at
            val startColumn = column
            at += 1
            while at < source.length && (source.charAt(at).isLetterOrDigit || source.charAt(at) == '_' || source.charAt(at) == '.') do at += 1
            out += Token(source.substring(start, at), Span(start, at), line, startColumn)
            column += at - start
          case None => return Left(s"unexpected character '${source.charAt(at)}' at byte $at")
    Right(out.toVector)

  def parse(source: String, spec: Grammar = grammar): Either[String, Tree] =
    tokenize(source, spec).flatMap { tokens =>
      val categories = spec.categories.map(category => category.name -> category).toMap
      def category(name: String, at: Int, active: Set[(String, Int)]): Vector[(Tree, Int)] =
        if active((name, at)) then Vector.empty
        else categories.get(name).toVector.flatMap(_.constructors).flatMap { constructor =>
          val floor = tokens.lift(at).map(_.column).getOrElse(0)
          elements(constructor.elements, at, active + ((name, at)), floor).map { case (fields, next) => Tree(constructor.tag, fields) -> next }
        }
      def elements(items: Vector[Elem], at: Int, active: Set[(String, Int)], floor: Int): Vector[(Vector[Field], Int)] =
        items.headOption match
          case None => Vector(Vector.empty -> at)
          case Some(head) =>
            element(head, at, active, floor).flatMap { case (field, next) =>
              elements(items.tail, next, active, floor).map { case (rest, end) => field.toVector ++ rest -> end }
            }
      def element(item: Elem, at: Int, active: Set[(String, Int)], floor: Int): Vector[(Option[Field], Int)] = item match
        case Elem.Lit(text) if tokens.lift(at).exists(_.text == text) => Vector(None -> (at + 1))
        case Elem.Lit(_) => Vector.empty
        case Elem.Name => tokens.lift(at).filter(token => !spec.keywords(token.text) && token.text.headOption.exists(ch => ch.isLetter || ch == '_')).toVector.map(token => Some(Field.Leaf(token.text)) -> (at + 1))
        case Elem.Category(name) => category(name, at, active).map { case (tree, next) => Some(Field.Node(tree)) -> next }
        case Elem.SepBy(name, separator, allowEmpty) =>
          val first = category(name, at, active)
          val empty = if allowEmpty then Vector(Some(Field.Nodes(Vector.empty)) -> at) else Vector.empty
          empty ++ first.flatMap { case (tree, next) =>
            def more(values: Vector[Tree], position: Int): Vector[(Option[Field], Int)] =
              if tokens.lift(position).exists(_.text == separator) then
                category(name, position + 1, active).flatMap { case (value, end) => more(values :+ value, end) }
              else Vector(Some(Field.Nodes(values)) -> position)
            more(Vector(tree), next)
          }
        case Elem.Block(name, min, max) =>
          tokens.lift(at).filter(token => token.column > floor && tokens.lift(at - 1).forall(_.line < token.line)).toVector.flatMap { firstToken =>
            val blockColumn = firstToken.column
            def loop(values: Vector[Tree], position: Int): Vector[(Option[Field], Int)] =
              if values.size < max && tokens.lift(position).exists(_.column == blockColumn) then
                category(name, position, active).flatMap { case (value, end) => loop(values :+ value, end) }
              else if values.size >= min then Vector(Some(Field.Nodes(values)) -> position)
              else Vector.empty
            loop(Vector.empty, at)
          }
      category(spec.start, 0, Set.empty).filter(_._2 == tokens.length).map(_._1).distinct match
        case Vector(tree) => Right(tree)
        case Vector() => Left(s"input does not match Trellis/${spec.start}")
        case trees => Left(s"ambiguous Trellis parse: ${trees.size} trees")
    }

  def print(tree: Tree): Either[String, String] =
    def render(current: Tree, indent: Int): Either[String, String] =
      printRules.get(current.tag).toRight(s"no print rule for ${current.tag}").flatMap { rule =>
        rule.segments.foldLeft[Either[String, String]](Right("")) { (result, segment) =>
          result.flatMap { prefix =>
            segment match
              case Segment.Text(value) => Right(prefix + value)
              case Segment.Field(index) => current.fields.lift(index) match
                case Some(Field.Leaf(value)) => Right(prefix + value)
                case _ => Left(s"${current.tag} field $index is not a leaf")
              case Segment.Child(index) => current.fields.lift(index) match
                case Some(Field.Node(value)) => render(value, indent).map(prefix + _)
                case _ => Left(s"${current.tag} field $index is not a child")
              case Segment.Children(index, separator) => current.fields.lift(index) match
                case Some(Field.Nodes(values)) => sequence(values.map(render(_, indent))).map(rendered => prefix + rendered.mkString(separator))
                case _ => Left(s"${current.tag} field $index is not a child list")
              case Segment.Block(index, separator) => current.fields.lift(index) match
                case Some(Field.Nodes(values)) =>
                  val childIndent = indent + 2
                  sequence(values.map(render(_, childIndent))).map { rendered =>
                    val joiner = separator + "\n" + (" " * childIndent)
                    prefix + "\n" + (" " * childIndent) + rendered.mkString(joiner)
                  }
                case _ => Left(s"${current.tag} field $index is not a block")
          }
        }
      }
    render(tree, 0)

  private def sequence[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty))((acc, value) => acc.flatMap(xs => value.map(xs :+ _)))

  val id = "trellis"
  val version = "0.1"
  val syntax: SyntaxCodec { type Tree = TrellisLanguage.Tree } = new SyntaxCodec:
    type Tree = TrellisLanguage.Tree
    def parse(source: String): Either[String, Tree] = TrellisLanguage.parse(source)
    def print(tree: Tree): Either[String, String] = TrellisLanguage.print(tree)

  def lower(tree: Tree, basis: Graph): Either[String, LoweredProgram] =
    Left("Trellis AST-to-IR lowering is not installed yet")
