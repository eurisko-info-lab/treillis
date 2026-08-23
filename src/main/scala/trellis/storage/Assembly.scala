package trellis.storage

import trellis.storage.Composition.*

/** Consumer-oriented recipe for selecting and compiling a graph branch. */
final case class Assembly(
    id: String,
    foundation: String,
    baseProfile: String,
    uses: Set[String],
    omits: Set[String],
    exposes: Vector[String],
    verifications: Vector[String],
    emits: Vector[String]
)

/** Assembly syntax is interpreted by the same table-described grammar as package manifests. */
object AssemblyLanguage:
  def parse(source: String): Either[String, Assembly] =
    ManifestLanguage.parse(source).flatMap { document =>
      val lines = document.lines
      for
        header <- lines.headOption.filter(_.tag == "assembly").toRight("first directive must be assembly")
        foundation <- exactlyOne(lines, "foundation")
        _ <- Either.cond(foundation == "F11", (), s"unsupported assembly foundation $foundation")
        base <- exactlyOne(lines, "base")
        uses = values(lines, "use").toSet
        omits = values(lines, "omit").toSet
        _ <- Either.cond((uses intersect omits).isEmpty, (), s"assembly both uses and omits ${(uses intersect omits).toVector.sorted.mkString(", ")}")
        exposes = values(lines, "expose")
        verifies = values(lines, "verify")
        emits = values(lines, "emit")
        _ <- Either.cond(verifies.nonEmpty, (), "assembly must declare verification")
        _ <- Either.cond(emits.nonEmpty, (), "assembly must declare output")
      yield Assembly(header.args.head, foundation, base, uses, omits, exposes, verifies, emits)
    }

  def print(assembly: Assembly): Either[String, String] =
    ManifestLanguage.print(ManifestLanguage.Document(Vector(
      ManifestLanguage.Line("assembly", Vector(assembly.id), 0),
      ManifestLanguage.Line("foundation", Vector(assembly.foundation), 2),
      ManifestLanguage.Line("base", Vector(assembly.baseProfile), 2)
    ) ++ assembly.uses.toVector.sorted.map(value => ManifestLanguage.Line("use", Vector(value), 2))
      ++ assembly.omits.toVector.sorted.map(value => ManifestLanguage.Line("omit", Vector(value), 2))
      ++ assembly.exposes.map(value => ManifestLanguage.Line("expose", Vector(value), 2))
      ++ assembly.verifications.map(value => ManifestLanguage.Line("verify", Vector(value), 2))
      ++ assembly.emits.map(value => ManifestLanguage.Line("emit", Vector(value), 2))))

  def profile(assembly: Assembly): Profile =
    Profile(s"assembly:${assembly.id}", assembly.uses, extendsProfiles = Set(assembly.baseProfile), basisCapabilities = Set("execute.parallel"))

  def validateSelection(assembly: Assembly, lock: Lock): Either[String, Unit] =
    val selectedCapabilities = lock.providers.keySet
    val forbidden = assembly.omits intersect selectedCapabilities
    Either.cond(forbidden.isEmpty, (), s"assembly ${assembly.id} selected omitted capabilities ${forbidden.toVector.sorted.mkString(", ")}")

  private def values(lines: Vector[ManifestLanguage.Line], tag: String): Vector[String] =
    lines.collect { case ManifestLanguage.Line(`tag`, Vector(value), _) => value }

  private def exactlyOne(lines: Vector[ManifestLanguage.Line], tag: String): Either[String, String] =
    values(lines, tag) match
      case Vector(value) => Right(value)
      case Vector() => Left(s"assembly lacks $tag")
      case _ => Left(s"assembly repeats $tag")

object AssemblyCatalog:
  private val known = Vector("SqueakDebug.assembly")

  lazy val assemblies: Vector[Assembly] = known.map { name =>
    val path = s"trellis/assemblies/$name"
    val input = Option(getClass.getClassLoader.getResourceAsStream(path)).getOrElse(throw IllegalStateException(s"missing $path"))
    val source = try new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) finally input.close()
    AssemblyLanguage.parse(source).fold(error => throw IllegalStateException(s"$name: $error"), identity)
  }

  def named(id: String): Either[String, Assembly] = assemblies.find(_.id == id).toRight(s"unknown assembly $id")
