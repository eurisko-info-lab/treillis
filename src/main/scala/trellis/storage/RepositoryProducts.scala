package trellis.storage

import java.security.{KeyFactory, Signature}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.util.Base64
import scala.collection.mutable
import scala.util.Try
import trellis.{Canon, Check, Delta, Language, Navigate, Project}
import trellis.Core.*
import trellis.Delta.*

/** Pijul-inspired immutable change DAG, branch frontiers, and a tiny local global-ledger model. */
object RepositoryProducts:
  lazy val graph: Graph = ProductCatalog.latest

  final case class Upstream(
      packageName: String,
      branch: String,
      publication: PublicationId,
      basisRoot: ContentId,
      basisFrontier: Set[ChangeId]
  )

  /** `basis` is already materialized at the upstream basis frontier; `frontier` contains local changes only. */
  final case class Branch(id: BranchId, basis: Graph, frontier: Set[ChangeId], upstream: Option[Upstream])

  final case class Publication(
      id: PublicationId,
      packageName: String,
      branch: String,
      frontier: Set[ChangeId],
      graphRoot: ContentId,
      publisher: String,
      signature: String
  )

  final case class Provenance(
      publication: PublicationId,
      packageName: String,
      branch: String,
      basisRoot: ContentId,
      basisFrontier: Set[ChangeId],
      localFrontier: Set[ChangeId]
  )

  final case class Ledger(records: Vector[Publication] = Vector.empty):
    def latest(packageName: String, branch: String): Option[Publication] =
      records.reverse.find(p => p.packageName == packageName && p.branch == branch)

  final case class LedgerPolicy(
      entity: EntityId,
      ordering: String,
      identity: String,
      signature: String,
      claim: Option[String],
      duplicate: String,
      failure: String
  )

  final case class CasPolicy(entity: EntityId, replicas: Int, placement: String, read: String, failure: String)
  final case class CasReplica(id: String, objects: Map[ContentId, Vector[Byte]] = Map.empty)
  final case class DistributedCas(replicas: Map[String, CasReplica]):
    def replica(id: String): Option[CasReplica] = replicas.get(id)

  final case class ConsensusPolicy(entity: EntityId, quorum: Int, ordering: String, finality: String, failure: String)
  final case class ConsensusBlock(
      id: ContentId,
      height: Int,
      previous: ContentId,
      publications: Vector[PublicationId],
      votes: Vector[String]
  )
  final case class ConsensusChain(blocks: Vector[ConsensusBlock] = Vector.empty):
    def tip: ContentId = blocks.lastOption.map(_.id).getOrElse(ContentId("0" * 64))
  final case class DiscoveryPolicy(entity: EntityId, source: String, selection: String, ordering: String, checkout: String, failure: String)
  final case class DiscoveredPackage(publication: Publication, blockHeight: Int)
  final case class AttestationPolicy(entity: EntityId, source: String, recipe: String, artifact: String, reproducibility: String, signature: String, failure: String)
  final case class ArtifactAttestation(
      id: ContentId,
      publication: PublicationId,
      sourceRoot: ContentId,
      builder: String,
      recipe: String,
      artifact: ContentId,
      signature: String
  )

  final case class Store(
      changes: Map[ChangeId, Change] = Map.empty,
      branches: Map[BranchId, Branch] = Map.empty
  ):
    def put(change: Change): (Store, ChangeId) =
      val id = Change.id(change)
      (copy(changes = changes.updated(id, change)), id)

    def addBranch(branch: Branch): Store = copy(branches = branches.updated(branch.id, branch))

  final case class Materialized(graph: Graph, applied: Vector[ChangeId])

  def encodePublication(publication: Publication): String =
    Canon.record(
      "publication",
      Vector(
        publication.packageName,
        publication.branch,
        Canon.record("frontier", publication.frontier.toVector.sortBy(_.value).map(_.value)),
        publication.graphRoot.value,
        publication.publisher,
        publication.signature
      )
    )

  def encodePublicationClaim(publication: Publication): String =
    Canon.record(
      "publication-claim",
      Vector(
        publication.packageName,
        publication.branch,
        Canon.record("frontier", publication.frontier.toVector.sortBy(_.value).map(_.value)),
        publication.graphRoot.value,
        publication.publisher
      )
    )

  def publicationId(publication: Publication): PublicationId = PublicationId(Canon.sha256(encodePublication(publication)))

  def makePublication(
      packageName: String,
      branch: String,
      frontier: Set[ChangeId],
      graphRoot: ContentId,
      publisher: String,
      signature: String = "simulated"
  ): Publication =
    val value = Publication(PublicationId("pending"), packageName, branch, frontier, graphRoot, publisher, signature)
    value.copy(id = publicationId(value))

  def signPublication(
      packageName: String,
      branch: String,
      frontier: Set[ChangeId],
      graphRoot: ContentId,
      publisher: String,
      privateKeyPkcs8: String
  ): Either[String, Publication] =
    Try {
      val keyBytes = Base64.getDecoder.decode(privateKeyPkcs8)
      val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
      val unsigned = Publication(PublicationId("pending"), packageName, branch, frontier, graphRoot, publisher, "")
      val signer = Signature.getInstance("Ed25519")
      signer.initSign(key)
      signer.update(encodePublicationClaim(unsigned).getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val signed = unsigned.copy(signature = Base64.getEncoder.encodeToString(signer.sign()))
      signed.copy(id = publicationId(signed))
    }.toEither.left.map(err => s"cannot sign publication: ${err.getMessage}")

  def ledgerPolicy(graph: Graph): Either[String, LedgerPolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) =>
      graph.nodes.get(id).filter(_.kind == "network.ledger-policy").map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          ordering <- node.attrs.get("ordering").toRight(s"${entity.value} lacks ordering")
          identity <- node.attrs.get("identity").toRight(s"${entity.value} lacks identity")
          signature <- node.attrs.get("signature").toRight(s"${entity.value} lacks signature")
          claim = node.attrs.get("claim")
          duplicate <- node.attrs.get("duplicate").toRight(s"${entity.value} lacks duplicate")
          failure <- node.attrs.get("failure").toRight(s"${entity.value} lacks failure")
          _ <- Either.cond(
            ordering == "append-only" && identity == "canonical-sha256" &&
              (signature == "simulated" || (signature == "ed25519" && claim.contains("package-branch-frontier-root-publisher"))) &&
              duplicate == "reject" && failure == "strict",
            (),
            s"unsupported publication ledger policy ${entity.value}"
          )
        yield LedgerPolicy(entity, ordering, identity, signature, claim, duplicate, failure)
      case Vector() => Left("missing publication ledger policy")
      case _ => Left("multiple publication ledger policies")

  def publish(graph: Graph, ledger: Ledger, publication: Publication): Either[String, Ledger] =
    for
      policy <- ledgerPolicy(graph)
      _ <- Canon.validateHash(publication.graphRoot.value, "publication graph root")
      _ <- publication.frontier.toVector.sortBy(_.value).foldLeft[Either[String, Unit]](Right(())) { (acc, id) =>
        acc.flatMap(_ => Canon.validateHash(id.value, "publication frontier change"))
      }
      _ <- Either.cond(publication.packageName.nonEmpty && publication.branch.nonEmpty, (), "publication package and branch must be non-empty")
      namespace <- publicationNamespace(graph, publication.packageName)
      _ <- validateNamespaceAuthority(graph, namespace, policy.signature)
      publisher <- admittedPublisher(graph, publication.publisher, namespace)
      _ <- verifyPublicationSignature(policy.signature, publisher, publication)
      expected = publicationId(publication)
      _ <- Either.cond(publication.id == expected, (), s"publication id mismatch: expected ${expected.value}, found ${publication.id.value}")
      _ <- Either.cond(!ledger.records.exists(_.id == publication.id), (), s"duplicate publication ${publication.id.value}")
    yield ledger.copy(records = ledger.records :+ publication)

  private def publicationNamespace(graph: Graph, packageName: String): Either[String, String] =
    val matches = graph.entities.toVector.sortBy(_._1.value).flatMap { case (_, id) =>
      graph.nodes.get(id).filter(_.kind == "network.namespace").flatMap(_.attrs.get("prefix"))
    }.filter(prefix => packageName == prefix || packageName.startsWith(prefix + "/"))
    matches match
      case Vector() => Left(s"no publication namespace admits $packageName")
      case values =>
        val longest = values.map(_.length).max
        values.filter(_.length == longest) match
          case Vector(value) => Right(value)
          case _ => Left(s"ambiguous publication namespace for $packageName")

  private def admittedPublisher(graph: Graph, publisher: String, namespace: String): Either[String, Node] =
    val matches = graph.entities.toVector.flatMap { case (_, id) =>
      graph.nodes.get(id).filter(_.kind == "network.publisher").filter { node =>
        node.attrs.get("name").contains(publisher) && node.attrs.get("namespace").contains(namespace)
      }
    }
    matches match
      case Vector(node) => Right(node)
      case Vector() => Left(s"publisher $publisher is not admitted for namespace $namespace")
      case _ => Left(s"publisher $publisher is ambiguous for namespace $namespace")

  private def validateNamespaceAuthority(graph: Graph, namespace: String, signature: String): Either[String, Unit] =
    if signature != "ed25519" then Right(())
    else
      val authorities = graph.entities.toVector.flatMap { case (_, id) =>
        graph.nodes.get(id).filter(_.kind == "network.namespace").filter(_.attrs.get("prefix").contains(namespace)).flatMap(_.attrs.get("authority"))
      }
      authorities match
        case Vector("signed-publisher") => Right(())
        case _ => Left(s"namespace $namespace lacks unique signed-publisher authority")

  private def verifyPublicationSignature(scheme: String, publisher: Node, publication: Publication): Either[String, Unit] =
    scheme match
      case "simulated" => Either.cond(publication.signature == "simulated", (), s"unsupported publication signature ${publication.signature}")
      case "ed25519" =>
        Try {
          val encoded = publisher.attrs.getOrElse("public-key", throw new IllegalArgumentException("publisher lacks public-key"))
          val algorithm = publisher.attrs.getOrElse("algorithm", throw new IllegalArgumentException("publisher lacks algorithm"))
          if algorithm != "ed25519" then throw new IllegalArgumentException(s"unsupported publisher algorithm $algorithm")
          val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder.decode(encoded)))
          val verifier = Signature.getInstance("Ed25519")
          verifier.initVerify(key)
          verifier.update(encodePublicationClaim(publication).getBytes(java.nio.charset.StandardCharsets.UTF_8))
          verifier.verify(Base64.getDecoder.decode(publication.signature))
        }.toEither.left.map(err => s"invalid Ed25519 publication signature: ${err.getMessage}").flatMap { valid =>
          Either.cond(valid, (), "invalid Ed25519 publication signature")
        }
      case other => Left(s"unsupported publication signature scheme $other")

  def casPolicy(graph: Graph): Either[String, CasPolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) =>
      graph.nodes.get(id).filter(_.kind == "network.cas-policy").map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          replicas <- node.attrs.get("replicas").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer replicas")
          placement <- node.attrs.get("placement").toRight(s"${entity.value} lacks placement")
          read <- node.attrs.get("read").toRight(s"${entity.value} lacks read")
          failure <- node.attrs.get("failure").toRight(s"${entity.value} lacks failure")
          _ <- Either.cond(replicas > 0 && placement == "stable-replica-id" && read == "verify-all" && failure == "strict", (), s"unsupported distributed CAS policy ${entity.value}")
        yield CasPolicy(entity, replicas, placement, read, failure)
      case Vector() => Left("missing distributed CAS policy")
      case _ => Left("multiple distributed CAS policies")

  def distributedCas(graph: Graph): Either[String, DistributedCas] =
    val ids = graph.entities.toVector.sortBy(_._1.value).flatMap { case (_, id) =>
      graph.nodes.get(id).filter(_.kind == "network.cas-replica").flatMap(_.attrs.get("id"))
    }
    ids.groupBy(identity).collectFirst { case (id, duplicates) if duplicates.size > 1 => id } match
      case Some(id) => Left(s"duplicate distributed CAS replica $id")
      case None if ids.isEmpty => Left("missing distributed CAS replicas")
      case None => Right(DistributedCas(ids.map(id => id -> CasReplica(id)).toMap))

  def putCas(graph: Graph, cas: DistributedCas, bytes: Array[Byte]): Either[String, (DistributedCas, ContentId)] =
    for
      policy <- casPolicy(graph)
      _ <- Either.cond(cas.replicas.size >= policy.replicas, (), s"distributed CAS has ${cas.replicas.size} replicas, needs ${policy.replicas}")
      id = ContentId(Canon.sha256Bytes(bytes))
      targets = cas.replicas.keys.toVector.sorted.take(policy.replicas)
      _ <- targets.foldLeft[Either[String, Unit]](Right(())) { (acc, target) =>
        acc.flatMap { _ =>
          cas.replicas(target).objects.get(id) match
            case Some(existing) if existing != bytes.toVector => Left(s"corrupt existing CAS object ${id.value} at $target")
            case _ => Right(())
        }
      }
      stored = targets.foldLeft(cas) { (current, target) =>
        val replica = current.replicas(target)
        current.copy(replicas = current.replicas.updated(target, replica.copy(objects = replica.objects.updated(id, bytes.toVector))))
      }
    yield stored -> id

  def getCas(graph: Graph, cas: DistributedCas, id: ContentId): Either[String, Array[Byte]] =
    for
      policy <- casPolicy(graph)
      _ <- Canon.validateHash(id.value, "distributed CAS content id")
      copies = cas.replicas.toVector.sortBy(_._1).flatMap { case (replica, store) => store.objects.get(id).map(replica -> _) }
      _ <- Either.cond(copies.size >= policy.replicas, (), s"distributed CAS object ${id.value} has ${copies.size} copies, needs ${policy.replicas}")
      _ <- copies.foldLeft[Either[String, Unit]](Right(())) { case (acc, (replica, bytes)) =>
        acc.flatMap(_ => Either.cond(Canon.sha256Bytes(bytes.toArray) == id.value, (), s"corrupt CAS object ${id.value} at $replica"))
      }
      first = copies.head._2
      _ <- Either.cond(copies.forall(_._2 == first), (), s"inconsistent CAS object ${id.value}")
    yield first.toArray

  def consensusPolicy(graph: Graph): Either[String, ConsensusPolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) =>
      graph.nodes.get(id).filter(_.kind == "network.consensus-policy").map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          quorum <- node.attrs.get("quorum").flatMap(_.toIntOption).toRight(s"${entity.value} lacks integer quorum")
          ordering <- node.attrs.get("ordering").toRight(s"${entity.value} lacks ordering")
          finality <- node.attrs.get("finality").toRight(s"${entity.value} lacks finality")
          failure <- node.attrs.get("failure").toRight(s"${entity.value} lacks failure")
          _ <- Either.cond(quorum > 0 && ordering == "canonical-publication-id" && finality == "immediate-quorum" && failure == "strict", (), s"unsupported consensus policy ${entity.value}")
        yield ConsensusPolicy(entity, quorum, ordering, finality, failure)
      case Vector() => Left("missing consensus policy")
      case _ => Left("multiple consensus policies")

  def consensusValidators(graph: Graph): Either[String, Vector[String]] =
    val validators = graph.entities.toVector.sortBy(_._1.value).flatMap { case (_, id) =>
      graph.nodes.get(id).filter(_.kind == "network.consensus-validator").flatMap(_.attrs.get("id"))
    }
    validators.groupBy(identity).collectFirst { case (id, values) if values.size > 1 => id } match
      case Some(id) => Left(s"duplicate consensus validator $id")
      case None if validators.isEmpty => Left("missing consensus validators")
      case None => Right(validators.sorted)

  def encodeConsensusBlock(block: ConsensusBlock): String =
    Canon.record(
      "consensus-block",
      Vector(
        block.height.toString,
        block.previous.value,
        Canon.record("publications", block.publications.map(_.value)),
        Canon.record("votes", block.votes)
      )
    )

  def consensusBlockId(block: ConsensusBlock): ContentId = ContentId(Canon.sha256(encodeConsensusBlock(block)))

  def finalizeBlock(
      graph: Graph,
      chain: ConsensusChain,
      ledger: Ledger,
      publications: Set[PublicationId],
      votes: Set[String]
  ): Either[String, ConsensusChain] =
    for
      policy <- consensusPolicy(graph)
      validators <- consensusValidators(graph)
      _ <- Either.cond(policy.quorum <= validators.size, (), s"consensus quorum ${policy.quorum} exceeds ${validators.size} validators")
      orderedPublications = publications.toVector.sortBy(_.value)
      orderedVotes = votes.toVector.sorted
      _ <- Either.cond(orderedPublications.nonEmpty, (), "consensus block must contain publications")
      _ <- orderedPublications.foldLeft[Either[String, Unit]](Right(())) { (acc, id) =>
        acc.flatMap(_ => Canon.validateHash(id.value, "consensus publication id"))
      }
      known = ledger.records.map(_.id).toSet
      _ <- Either.cond(orderedPublications.forall(known.contains), (), "consensus block contains unknown publication")
      committed = chain.blocks.flatMap(_.publications).toSet
      _ <- Either.cond(orderedPublications.forall(id => !committed.contains(id)), (), "consensus publication already finalized")
      _ <- Either.cond(orderedVotes.forall(validators.contains), (), "consensus block contains unknown validator vote")
      _ <- Either.cond(orderedVotes.size >= policy.quorum, (), s"consensus quorum not reached: ${orderedVotes.size} < ${policy.quorum}")
      unsigned = ConsensusBlock(ContentId("pending"), chain.blocks.size, chain.tip, orderedPublications, orderedVotes)
      block = unsigned.copy(id = consensusBlockId(unsigned))
    yield chain.copy(blocks = chain.blocks :+ block)

  def verifyConsensusChain(graph: Graph, ledger: Ledger, chain: ConsensusChain): Either[String, Unit] =
    chain.blocks.zipWithIndex.foldLeft[Either[String, (ContentId, Set[PublicationId])]](Right(ContentId("0" * 64) -> Set.empty)) {
      case (acc, (block, height)) =>
        for
          state <- acc
          (previous, committed) = state
          policy <- consensusPolicy(graph)
          validators <- consensusValidators(graph)
          _ <- Either.cond(policy.quorum <= validators.size, (), s"consensus quorum ${policy.quorum} exceeds ${validators.size} validators")
          _ <- Either.cond(block.height == height, (), s"consensus block height mismatch at $height")
          _ <- Either.cond(block.previous == previous, (), s"consensus previous hash mismatch at $height")
          _ <- Either.cond(block.id == consensusBlockId(block), (), s"consensus block id mismatch at $height")
          _ <- Either.cond(block.publications == block.publications.sortBy(_.value) && block.publications.distinct == block.publications, (), s"non-canonical publications at $height")
          _ <- Either.cond(block.publications.nonEmpty, (), s"empty consensus block at $height")
          _ <- Either.cond(block.votes == block.votes.sorted && block.votes.distinct == block.votes, (), s"non-canonical votes at $height")
          _ <- Either.cond(block.votes.forall(validators.contains) && block.votes.size >= policy.quorum, (), s"invalid consensus quorum at $height")
          known = ledger.records.map(_.id).toSet
          _ <- Either.cond(block.publications.forall(known.contains), (), s"unknown finalized publication at $height")
          _ <- Either.cond(block.publications.forall(id => !committed.contains(id)), (), s"replayed finalized publication at $height")
        yield block.id -> (committed ++ block.publications)
    }.map(_ => ())

  def discoveryPolicy(graph: Graph): Either[String, DiscoveryPolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) =>
      graph.nodes.get(id).filter(_.kind == "network.discovery-policy").map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          source <- node.attrs.get("source").toRight(s"${entity.value} lacks source")
          selection <- node.attrs.get("selection").toRight(s"${entity.value} lacks selection")
          ordering <- node.attrs.get("ordering").toRight(s"${entity.value} lacks ordering")
          checkout <- node.attrs.get("checkout").toRight(s"${entity.value} lacks checkout")
          failure <- node.attrs.get("failure").toRight(s"${entity.value} lacks failure")
          _ <- Either.cond(source == "finalized-consensus" && selection == "latest-finalized" && ordering == "package-branch-publication-id" && checkout == "verified-cas-root" && failure == "strict", (), s"unsupported package discovery policy ${entity.value}")
        yield DiscoveryPolicy(entity, source, selection, ordering, checkout, failure)
      case Vector() => Left("missing package discovery policy")
      case _ => Left("multiple package discovery policies")

  def discoverPackages(
      graph: Graph,
      ledger: Ledger,
      chain: ConsensusChain,
      prefix: String = ""
  ): Either[String, Vector[DiscoveredPackage]] =
    for
      _ <- discoveryPolicy(graph)
      _ <- verifyConsensusChain(graph, ledger, chain)
      finalized = chain.blocks.flatMap(block => block.publications.map(_ -> block.height))
      records <- finalized.foldLeft[Either[String, Vector[DiscoveredPackage]]](Right(Vector.empty)) { case (acc, (id, height)) =>
        for
          current <- acc
          publication <- ledger.records.find(_.id == id).toRight(s"finalized publication ${id.value} is absent from ledger")
          _ <- publish(graph, Ledger(), publication).map(_ => ())
        yield current :+ DiscoveredPackage(publication, height)
      }
      latest = records.groupBy(value => value.publication.packageName -> value.publication.branch).values.map(_.maxBy(value => (value.blockHeight, value.publication.id.value))).toVector
    yield latest.filter(_.publication.packageName.startsWith(prefix)).sortBy(value => (value.publication.packageName, value.publication.branch, value.publication.id.value))

  def branchFromLatestPublication(
      graph: Graph,
      cas: DistributedCas,
      ledger: Ledger,
      chain: ConsensusChain,
      packageName: String,
      branch: String,
      localId: BranchId
  ): Either[String, Branch] =
    for
      packages <- discoverPackages(graph, ledger, chain, packageName)
      discovered <- packages.find(value => value.publication.packageName == packageName && value.publication.branch == branch).toRight(s"no finalized publication for $packageName/$branch")
      bytes <- getCas(graph, cas, discovered.publication.graphRoot)
      basis <- Canon.decodeGraphBytes(bytes)
      local <- branchFromPublication(localId, basis, discovered.publication)
    yield local

  def attestationPolicy(graph: Graph): Either[String, AttestationPolicy] =
    val candidates = graph.entities.toVector.sortBy(_._1.value).flatMap { case (entity, id) =>
      graph.nodes.get(id).filter(_.kind == "network.attestation-policy").map(entity -> _)
    }
    candidates match
      case Vector((entity, node)) =>
        for
          source <- node.attrs.get("source").toRight(s"${entity.value} lacks source")
          recipe <- node.attrs.get("recipe").toRight(s"${entity.value} lacks recipe")
          artifact <- node.attrs.get("artifact").toRight(s"${entity.value} lacks artifact")
          reproducibility <- node.attrs.get("reproducibility").toRight(s"${entity.value} lacks reproducibility")
          signature <- node.attrs.get("signature").toRight(s"${entity.value} lacks signature")
          failure <- node.attrs.get("failure").toRight(s"${entity.value} lacks failure")
          _ <- Either.cond(source == "finalized-publication" && recipe == "canonical-graph-manifest-v1" && artifact == "verified-cas-content" && reproducibility == "exact-bytes" && signature == "ed25519" && failure == "strict", (), s"unsupported artifact attestation policy ${entity.value}")
        yield AttestationPolicy(entity, source, recipe, artifact, reproducibility, signature, failure)
      case Vector() => Left("missing artifact attestation policy")
      case _ => Left("multiple artifact attestation policies")

  def encodeArtifactBytes(publication: Publication, recipe: String): Array[Byte] =
    Canon.record("build-artifact", Vector(publication.id.value, publication.graphRoot.value, recipe)).getBytes(java.nio.charset.StandardCharsets.UTF_8)

  def encodeAttestationClaim(attestation: ArtifactAttestation): String =
    Canon.record("artifact-attestation-claim", Vector(attestation.publication.value, attestation.sourceRoot.value, attestation.builder, attestation.recipe, attestation.artifact.value))

  def encodeAttestation(attestation: ArtifactAttestation): String =
    Canon.record("artifact-attestation", Vector(encodeAttestationClaim(attestation), attestation.signature))

  def attestationId(attestation: ArtifactAttestation): ContentId = ContentId(Canon.sha256(encodeAttestation(attestation)))

  private def signAttestation(value: ArtifactAttestation, privateKeyPkcs8: String): Either[String, ArtifactAttestation] =
    Try {
      val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder.decode(privateKeyPkcs8)))
      val signer = Signature.getInstance("Ed25519")
      signer.initSign(key)
      signer.update(encodeAttestationClaim(value).getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val signed = value.copy(signature = Base64.getEncoder.encodeToString(signer.sign()))
      signed.copy(id = attestationId(signed))
    }.toEither.left.map(err => s"cannot sign artifact attestation: ${err.getMessage}")

  private def builderNode(graph: Graph, builder: String): Either[String, Node] =
    val matches = graph.entities.toVector.flatMap { case (_, id) =>
      graph.nodes.get(id).filter(_.kind == "network.builder").filter(_.attrs.get("name").contains(builder))
    }
    matches match
      case Vector(node) => Right(node)
      case Vector() => Left(s"unknown artifact builder $builder")
      case _ => Left(s"ambiguous artifact builder $builder")

  def buildArtifact(
      graph: Graph,
      cas: DistributedCas,
      ledger: Ledger,
      chain: ConsensusChain,
      publicationId: PublicationId,
      builder: String,
      privateKeyPkcs8: String
  ): Either[String, (DistributedCas, ArtifactAttestation)] =
    for
      policy <- attestationPolicy(graph)
      packages <- discoverPackages(graph, ledger, chain)
      publication <- packages.find(_.publication.id == publicationId).map(_.publication).toRight(s"publication ${publicationId.value} is not latest finalized")
      _ <- builderNode(graph, builder)
      sourceBytes <- getCas(graph, cas, publication.graphRoot)
      _ <- Canon.decodeGraphBytes(sourceBytes)
      bytes = encodeArtifactBytes(publication, policy.recipe)
      stored <- putCas(graph, cas, bytes)
      (nextCas, artifact) = stored
      unsigned = ArtifactAttestation(ContentId("pending"), publication.id, publication.graphRoot, builder, policy.recipe, artifact, "")
      signed <- signAttestation(unsigned, privateKeyPkcs8)
    yield nextCas -> signed

  def verifyArtifactAttestation(
      graph: Graph,
      cas: DistributedCas,
      ledger: Ledger,
      chain: ConsensusChain,
      attestation: ArtifactAttestation
  ): Either[String, Unit] =
    for
      policy <- attestationPolicy(graph)
      _ <- Either.cond(attestation.id == attestationId(attestation), (), "artifact attestation id mismatch")
      packages <- discoverPackages(graph, ledger, chain)
      publication <- packages.find(_.publication.id == attestation.publication).map(_.publication).toRight(s"attested publication ${attestation.publication.value} is not latest finalized")
      _ <- Either.cond(attestation.sourceRoot == publication.graphRoot, (), "artifact attestation source root mismatch")
      sourceBytes <- getCas(graph, cas, publication.graphRoot)
      _ <- Canon.decodeGraphBytes(sourceBytes)
      _ <- Either.cond(attestation.recipe == policy.recipe, (), s"unsupported artifact recipe ${attestation.recipe}")
      node <- builderNode(graph, attestation.builder)
      bytes <- getCas(graph, cas, attestation.artifact)
      expected = encodeArtifactBytes(publication, policy.recipe)
      _ <- Either.cond(java.util.Arrays.equals(bytes, expected), (), "artifact bytes are not reproducible")
      valid <- Try {
        val algorithm = node.attrs.getOrElse("algorithm", throw new IllegalArgumentException("builder lacks algorithm"))
        if algorithm != "ed25519" then throw new IllegalArgumentException(s"unsupported builder algorithm $algorithm")
        val publicKey = node.attrs.getOrElse("public-key", throw new IllegalArgumentException("builder lacks public-key"))
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder.decode(publicKey)))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(key)
        verifier.update(encodeAttestationClaim(attestation).getBytes(java.nio.charset.StandardCharsets.UTF_8))
        verifier.verify(Base64.getDecoder.decode(attestation.signature))
      }.toEither.left.map(err => s"invalid artifact attestation signature: ${err.getMessage}")
      _ <- Either.cond(valid, (), "invalid artifact attestation signature")
    yield ()


  def closure(store: Store, frontier: Set[ChangeId]): Either[String, Set[ChangeId]] =
    val seen = mutable.Set.empty[ChangeId]
    def visit(id: ChangeId): Either[String, Unit] =
      if seen(id) then Right(())
      else
        store.changes.get(id) match
          case None => Left(s"missing change ${id.value}")
          case Some(change) =>
            seen += id
            change.dependencies.toVector.sortBy(_.value).foldLeft[Either[String, Unit]](Right(())) {
              (acc, dep) => acc.flatMap(_ => visit(dep))
            }
    frontier.toVector.sortBy(_.value).foldLeft[Either[String, Unit]](Right(()))((a, id) => a.flatMap(_ => visit(id)))
      .map(_ => seen.toSet)

  private def ancestors(store: Store, id: ChangeId): Set[ChangeId] =
    store.changes.get(id).toVector.flatMap(_.dependencies).flatMap { d => ancestors(store, d) + d }.toSet

  def validateConcurrency(store: Store, ids: Set[ChangeId]): Either[String, Unit] =
    val ordered = ids.toVector.sortBy(_.value)
    val conflict = for
      (a, i) <- ordered.zipWithIndex
      b <- ordered.drop(i + 1)
      if !ancestors(store, a).contains(b) && !ancestors(store, b).contains(a)
      ca <- store.changes.get(a).toVector
      cb <- store.changes.get(b).toVector
      overlap = Delta.footprint(ca).intersect(Delta.footprint(cb))
      if overlap.nonEmpty
    yield s"concurrent semantic conflict ${a.value.take(8)} / ${b.value.take(8)} on ${overlap.toVector.sorted.mkString(", ")}"
    conflict.headOption.toLeft(())

  def topological(store: Store, ids: Set[ChangeId]): Either[String, Vector[ChangeId]] =
    val remaining = mutable.Set.from(ids)
    val done = mutable.ArrayBuffer.empty[ChangeId]
    while remaining.nonEmpty do
      val ready = remaining.toVector.filter { id =>
        store.changes.get(id).exists(_.dependencies.forall(d => !ids.contains(d) || done.contains(d)))
      }.sortBy(_.value)
      if ready.isEmpty then return Left("change dependency cycle or missing dependency")
      ready.foreach { id =>
        done += id
        remaining -= id
      }
    Right(done.toVector)

  def materialize(store: Store, branch: Branch): Either[String, Materialized] =
    for
      _ <- validateProvenance(branch)
      ids <- closure(store, branch.frontier)
      _ <- validateConcurrency(store, ids)
      order <- topological(store, ids)
      graph <- order.foldLeft[Either[String, Graph]](Right(branch.basis)) { (acc, id) =>
        acc.flatMap(g => store.changes.get(id).toRight(s"missing change ${id.value}").flatMap(Delta.applyChange(g, _)))
      }
      errors = Check.validate(graph)
      _ <- if errors.isEmpty then Right(()) else Left(errors.mkString("; "))
    yield Materialized(graph, order)

  def advance(store: Store, branchId: BranchId, change: Change): Either[String, Store] =
    store.branches.get(branchId).toRight(s"unknown branch ${branchId.value}").map { branch =>
      val (s1, id) = store.put(change)
      val next = branch.copy(frontier = (branch.frontier -- change.dependencies) + id)
      s1.addBranch(next)
    }

  def branchFromPublication(id: BranchId, graph: Graph, p: Publication): Either[String, Branch] =
    val actualRoot = Canon.graphId(graph)
    if actualRoot != p.graphRoot then
      Left(s"publication ${p.id.value} claims ${p.graphRoot.value}, supplied basis is ${actualRoot.value}")
    else
      Right(
        Branch(
          id,
          graph,
          Set.empty,
          Some(Upstream(p.packageName, p.branch, p.id, p.graphRoot, p.frontier))
        )
      )

  def provenance(branch: Branch): Option[Provenance] = branch.upstream.map { u =>
    Provenance(u.publication, u.packageName, u.branch, u.basisRoot, u.basisFrontier, branch.frontier)
  }

  def validateProvenance(branch: Branch): Either[String, Unit] = branch.upstream match
    case None => Right(())
    case Some(upstream) =>
      val actual = Canon.graphId(branch.basis)
      if actual == upstream.basisRoot then Right(())
      else Left(s"branch ${branch.id.value} basis provenance mismatch: expected ${upstream.basisRoot.value}, found ${actual.value}")
