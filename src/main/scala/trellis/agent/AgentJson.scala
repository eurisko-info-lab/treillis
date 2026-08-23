package trellis.agent

import scala.annotation.tailrec

/** Minimal JSON read/write for the local agent API — no external dependencies. */
object AgentJson:
  def quote(value: String): String =
    "\"" + value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c => c.toString
    } + "\""

  def objectFields(pairs: Iterable[(String, String)]): String =
    pairs.toVector.sortBy(_._1).map { case (key, value) => s"${quote(key)}:$value" }.mkString("{", ",", "}")

  def stringArray(values: Iterable[String]): String =
    values.toVector.map(quote).mkString("[", ",", "]")

  def number(value: Int): String = value.toString

  def boolean(value: Boolean): String = if value then "true" else "false"

  enum Json:
    case Str(value: String)
    case Num(value: Int)
    case Bool(value: Boolean)
    case Arr(values: Vector[Json])
    case Obj(fields: Map[String, Json])

  def parse(text: String): Either[String, Json] =
    val trimmed = text.trim
    if trimmed.isEmpty then Left("empty JSON body")
    else parseValue(trimmed, 0).flatMap { case (value, index) =>
      val end = skip(trimmed, index)
      if end == trimmed.length then Right(value)
      else Left(s"unexpected trailing input at $end")
    }

  def field(json: Json, name: String): Either[String, Json] = json match
    case Json.Obj(fields) => fields.get(name).toRight(s"missing field $name")
    case _ => Left(s"expected object for field $name")

  def asString(json: Json): Either[String, String] = json match
    case Json.Str(value) => Right(value)
    case _ => Left("expected string")

  def asObject(json: Json): Either[String, Map[String, Json]] = json match
    case Json.Obj(fields) => Right(fields)
    case _ => Left("expected object")

  def asArray(json: Json): Either[String, Vector[Json]] = json match
    case Json.Arr(values) => Right(values)
    case _ => Left("expected array")

  def asInt(json: Json): Either[String, Int] = json match
    case Json.Num(value) => Right(value)
    case Json.Str(value) => value.toIntOption.toRight(s"invalid integer $value")
    case _ => Left("expected integer")

  def sequenceEither[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)) { case (acc, item) =>
      acc.flatMap(xs => item.map(xs :+ _))
    }

  def render(json: Json): String = json match
    case Json.Str(value) => quote(value)
    case Json.Num(value) => value.toString
    case Json.Bool(value) => boolean(value)
    case Json.Arr(values) => values.map(render).mkString("[", ",", "]")
    case Json.Obj(fields) =>
      fields.toVector.sortBy(_._1).map { case (key, value) => s"${quote(key)}:${render(value)}" }.mkString("{", ",", "}")

  private def skip(text: String, index: Int): Int =
    @tailrec def loop(i: Int): Int =
      if i >= text.length then i
      else text.charAt(i) match
        case ' ' | '\n' | '\r' | '\t' => loop(i + 1)
        case _ => i
    loop(index)

  private def parseValue(text: String, index: Int): Either[String, (Json, Int)] =
    val start = skip(text, index)
    if start >= text.length then Left("unexpected end of input")
    else text.charAt(start) match
      case '"' => parseString(text, start)
      case '{' => parseObject(text, start)
      case '[' => parseArray(text, start)
      case 't' => parseLiteral(text, start, "true", Json.Bool(true))
      case 'f' => parseLiteral(text, start, "false", Json.Bool(false))
      case c if c == '-' || c.isDigit => parseNumber(text, start)
      case c => Left(s"unexpected character '$c' at $start")

  private def parseLiteral(text: String, index: Int, literal: String, value: Json): Either[String, (Json, Int)] =
    if text.startsWith(literal, index) then Right(value -> (index + literal.length))
    else Left(s"expected $literal at $index")

  private def parseNumber(text: String, index: Int): Either[String, (Json, Int)] =
    val end = text.indexWhere(c => !c.isDigit && c != '-', index)
    val slice = if end == -1 then text.substring(index) else text.substring(index, end)
    slice.toIntOption.map(num => Json.Num(num) -> (if end == -1 then text.length else end)).toRight(s"invalid number at $index")

  private def parseString(text: String, index: Int): Either[String, (Json, Int)] =
    val builder = new StringBuilder
    var i = index + 1
    while i < text.length do
      text.charAt(i) match
        case '"' => return Right(Json.Str(builder.toString) -> (i + 1))
        case '\\' if i + 1 < text.length =>
          builder.append(text.charAt(i + 1) match
            case '"' => '"'
            case '\\' => '\\'
            case 'n' => '\n'
            case 'r' => '\r'
            case 't' => '\t'
            case c => c
          )
          i += 2
        case c =>
          builder.append(c)
          i += 1
    Left("unterminated string")

  private def parseArray(text: String, index: Int): Either[String, (Json, Int)] =
    val start = skip(text, index + 1)
    if start < text.length && text.charAt(start) == ']' then Right(Json.Arr(Vector.empty) -> (start + 1))
    else parseArrayValues(text, start, Vector.empty)

  private def parseArrayValues(text: String, index: Int, acc: Vector[Json]): Either[String, (Json, Int)] =
    parseValue(text, index).flatMap { case (value, next) =>
      val end = skip(text, next)
      if end >= text.length then Left("unterminated array")
      else text.charAt(end) match
        case ']' => Right(Json.Arr(acc :+ value) -> (end + 1))
        case ',' => parseArrayValues(text, end + 1, acc :+ value)
        case _ => Left(s"expected ',' or ']' at $end")
    }

  private def parseObject(text: String, index: Int): Either[String, (Json, Int)] =
    val start = skip(text, index + 1)
    if start < text.length && text.charAt(start) == '}' then Right(Json.Obj(Map.empty) -> (start + 1))
    else parseObjectFields(text, start, Map.empty)

  private def parseObjectFields(text: String, index: Int, acc: Map[String, Json]): Either[String, (Json, Int)] =
    parseString(text, index).flatMap { case (Json.Str(key), next) =>
      val colon = skip(text, next)
      if colon >= text.length || text.charAt(colon) != ':' then Left(s"expected ':' at $colon")
      else parseValue(text, colon + 1).flatMap { case (value, afterValue) =>
        val end = skip(text, afterValue)
        if end >= text.length then Left("unterminated object")
        else text.charAt(end) match
          case '}' => Right(Json.Obj(acc.updated(key, value)) -> (end + 1))
          case ',' => parseObjectFields(text, end + 1, acc.updated(key, value))
          case _ => Left(s"expected ',' or '}}' at $end")
      }
    }
