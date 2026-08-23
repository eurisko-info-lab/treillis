package trellis.agent

import scala.util.Try
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import trellis.{Canon, Check, Delta}
import trellis.Core.*
import trellis.Delta.*
import trellis.storage.RepositoryProducts.*

/** Persist and restore the local branch plus open workspace delta. */
object WorkspacePersistence:
  final case class Snapshot(
      version: Int,
      assembly: String,
      basisRoot: String,
      branch: String,
      frontier: Vector[String],
      changes: Vector[(ChangeId, Change)],
      session: WorkspaceSession,
      ledger: Ledger
  )

  def defaultPath: Path =
    sys.env.get("TRELLIS_WORKSPACE").map(Path.of(_)).getOrElse(Path.of(".trellis/workspace.json"))

  def save(path: Path, assemblyId: String, image: AgentApi.Image): Either[String, Unit] =
    for
      branch <- image.store.branches.get(image.session.branch).toRight(s"unknown branch ${image.session.branch.value}")
      snapshot = Snapshot(
        version = 1,
        assembly = assemblyId,
        basisRoot = Canon.graphId(branch.basis).value,
        branch = image.session.branch.value,
        frontier = branch.frontier.toVector.sortBy(_.value).map(_.value),
        changes = image.store.changes.toVector.sortBy(_._1.value),
        session = image.session,
        ledger = image.ledger
      )
      _ <- Try {
        Files.createDirectories(path.getParent)
        Files.writeString(path, encode(snapshot), StandardCharsets.UTF_8)
      }.toEither.left.map(err => s"cannot write workspace snapshot: ${err.getMessage}")
    yield ()

  def restore(base: AgentApi.Image, assemblyId: String, path: Path): Either[String, AgentApi.Image] =
    if !Files.isRegularFile(path) then Right(base)
    else
      for
        text <- Try(Files.readString(path, StandardCharsets.UTF_8))
          .toEither.left.map(err => s"cannot read workspace snapshot: ${err.getMessage}")
        snapshot <- decode(text)
        branchId = base.session.branch
        branch <- base.store.branches.get(branchId).toRight(s"unknown branch ${branchId.value}")
        basisRoot = Canon.graphId(branch.basis)
        _ <- Either.cond(snapshot.version == 1, (), s"unsupported workspace snapshot version ${snapshot.version}")
        _ <- Either.cond(snapshot.assembly == assemblyId, (), s"workspace snapshot assembly ${snapshot.assembly} != $assemblyId")
        _ <- Either.cond(snapshot.basisRoot == basisRoot.value, (), "workspace snapshot basis root mismatch; delete .trellis/workspace.json to reset")
        _ <- Either.cond(snapshot.branch == branchId.value, (), s"workspace snapshot branch ${snapshot.branch} != ${branchId.value}")
        storeWithChanges = snapshot.changes.foldLeft(Store()) { case (acc, (id, change)) =>
          if Change.id(change) == id then acc.copy(changes = acc.changes.updated(id, change)) else acc
        }
        restoredBranch = Branch(branchId, branch.basis, snapshot.frontier.map(ChangeId.apply).toSet, branch.upstream)
        store = storeWithChanges.addBranch(restoredBranch)
        _ <- materialize(store, restoredBranch).map(_ => ())
        image = AgentApi.Image(store, snapshot.session.copy(branch = branchId), snapshot.ledger)
        _ <- AgentApi.preview(image).flatMap { graph =>
          val errors = Check.validate(graph)
          if errors.isEmpty then Right(()) else Left(errors.mkString("; "))
        }
      yield image

  private def encode(snapshot: Snapshot): String =
    val changes = snapshot.changes.map { case (id, change) =>
      AgentJson.objectFields(Vector("id" -> AgentJson.quote(id.value), "change" -> AgentJson.quote(Delta.encodeChange(change))))
    }.mkString("[", ",", "]")
    val operations = snapshot.session.operations.map(op => AgentJson.quote(encodeOp(op))).mkString("[", ",", "]")
    AgentJson.objectFields(
      Vector(
        "version" -> AgentJson.number(snapshot.version),
        "assembly" -> AgentJson.quote(snapshot.assembly),
        "basisRoot" -> AgentJson.quote(snapshot.basisRoot),
        "branch" -> AgentJson.quote(snapshot.branch),
        "frontier" -> AgentJson.stringArray(snapshot.frontier),
        "changes" -> changes,
        "session" -> AgentJson.objectFields(
          Vector(
            "author" -> AgentJson.quote(snapshot.session.author),
            "operations" -> operations,
            "transcript" -> AgentJson.stringArray(snapshot.session.transcript)
          )
        ),
        "ledger" -> encodeLedger(snapshot.ledger)
      )
    )

  private def decode(text: String): Either[String, Snapshot] =
    for
      json <- AgentJson.parse(text)
      version <- AgentJson.field(json, "version").flatMap(AgentJson.asInt)
      assembly <- AgentJson.field(json, "assembly").flatMap(AgentJson.asString)
      basisRoot <- AgentJson.field(json, "basisRoot").flatMap(AgentJson.asString)
      branch <- AgentJson.field(json, "branch").flatMap(AgentJson.asString)
      frontier <- AgentJson.field(json, "frontier").flatMap(AgentJson.asArray).flatMap { items =>
        AgentJson.sequenceEither(items.map(item => AgentJson.asString(item)))
      }
      changesJson <- AgentJson.field(json, "changes").flatMap(AgentJson.asArray)
      changes <- AgentJson.sequenceEither(changesJson.map(decodeStoredChange))
      sessionJson <- AgentJson.field(json, "session").flatMap(AgentJson.asObject)
      author <- sessionJson.get("author").toRight("missing session.author").flatMap(AgentJson.asString)
      operations <- sessionJson.get("operations").toRight("missing session.operations").flatMap(AgentJson.asArray).flatMap { items =>
        AgentJson.sequenceEither(items.map(item => AgentJson.asString(item).flatMap(decodeOp)))
      }
      transcript <- sessionJson.get("transcript").fold(Right(Vector.empty[String]): Either[String, Vector[String]]) { value =>
        AgentJson.asArray(value).flatMap(items => AgentJson.sequenceEither(items.map(item => AgentJson.asString(item))))
      }
      ledger <- AgentJson.field(json, "ledger").flatMap(decodeLedger)
    yield Snapshot(
      version = version,
      assembly = assembly,
      basisRoot = basisRoot,
      branch = branch,
      frontier = frontier,
      changes = changes,
      session = WorkspaceSession(BranchId(branch), operations, transcript, author),
      ledger = ledger
    )

  private def decodeStoredChange(json: AgentJson.Json): Either[String, (ChangeId, Change)] =
    for
      fields <- AgentJson.asObject(json)
      idText <- fields.get("id").toRight("missing change id").flatMap(AgentJson.asString)
      changeText <- fields.get("change").toRight("missing change body").flatMap(AgentJson.asString)
      _ <- Canon.validateHash(idText, "stored change id")
      change <- Delta.decodeChange(changeText)
      id = ChangeId(idText)
      _ <- Either.cond(Change.id(change) == id, (), s"stored change id mismatch for $idText")
    yield id -> change

  private def encodeLedger(ledger: Ledger): String =
    ledger.records.map { publication =>
      AgentJson.objectFields(
        Vector(
          "id" -> AgentJson.quote(publication.id.value),
          "package" -> AgentJson.quote(publication.packageName),
          "branch" -> AgentJson.quote(publication.branch),
          "frontier" -> AgentJson.stringArray(publication.frontier.toVector.sortBy(_.value).map(_.value)),
          "graphRoot" -> AgentJson.quote(publication.graphRoot.value),
          "publisher" -> AgentJson.quote(publication.publisher),
          "signature" -> AgentJson.quote(publication.signature)
        )
      )
    }.mkString("[", ",", "]")

  private def decodeLedger(json: AgentJson.Json): Either[String, Ledger] =
    AgentJson.asArray(json).flatMap { items =>
      AgentJson.sequenceEither(items.map(decodePublication)).map(records => Ledger(records))
    }

  private def decodePublication(json: AgentJson.Json): Either[String, Publication] =
    for
      fields <- AgentJson.asObject(json)
      id <- fields.get("id").toRight("missing publication id").flatMap(AgentJson.asString).map(PublicationId.apply)
      packageName <- fields.get("package").toRight("missing publication package").flatMap(AgentJson.asString)
      branch <- fields.get("branch").toRight("missing publication branch").flatMap(AgentJson.asString)
      frontier <- fields.get("frontier").toRight("missing publication frontier").flatMap(AgentJson.asArray).flatMap { values =>
        AgentJson.sequenceEither(values.map(item => AgentJson.asString(item).map(ChangeId.apply)))
      }
      graphRoot <- fields.get("graphRoot").toRight("missing publication graphRoot").flatMap(AgentJson.asString).map(ContentId.apply)
      publisher <- fields.get("publisher").toRight("missing publication publisher").flatMap(AgentJson.asString)
      signature <- fields.get("signature").toRight("missing publication signature").flatMap(AgentJson.asString)
    yield Publication(id, packageName, branch, frontier.toSet, graphRoot, publisher, signature)
