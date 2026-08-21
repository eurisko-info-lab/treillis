package trellis

import scala.collection.mutable
import trellis.Core.*
import trellis.Delta.*

/** Pijul-inspired immutable change DAG, branch frontiers, and a tiny local global-ledger model. */
object Repo:
  final case class Upstream(packageName: String, branch: String, publication: PublicationId)
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

  final case class Ledger(records: Vector[Publication] = Vector.empty):
    def publish(p: Publication): Ledger = copy(records = records :+ p)
    def latest(packageName: String, branch: String): Option[Publication] =
      records.reverse.find(p => p.packageName == packageName && p.branch == branch)

  final case class Store(
      changes: Map[ChangeId, Change] = Map.empty,
      branches: Map[BranchId, Branch] = Map.empty
  ):
    def put(change: Change): (Store, ChangeId) =
      val id = Change.id(change)
      (copy(changes = changes.updated(id, change)), id)

    def addBranch(branch: Branch): Store = copy(branches = branches.updated(branch.id, branch))

  final case class Materialized(graph: Graph, applied: Vector[ChangeId])

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

  def branchFromPublication(id: BranchId, graph: Graph, p: Publication): Branch =
    Branch(id, graph, p.frontier, Some(Upstream(p.packageName, p.branch, p.id)))
