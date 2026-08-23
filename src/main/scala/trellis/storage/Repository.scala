package trellis.storage

import trellis.Core.*
import trellis.Delta.*

/** Language-neutral storage boundary. Implementations know graphs and changes, never syntax or evaluation. */
trait Repository:
  def graph: Graph
  def applyChange(change: Change): Either[String, Repository]

final case class MemoryRepository(graph: Graph) extends Repository:
  def applyChange(change: Change): Either[String, Repository] =
    trellis.Delta.applyChange(graph, change).map(MemoryRepository.apply)

/** Stable imports for storage clients; concrete language packages must not be imported here. */
object Model:
  export trellis.Core.{BranchId, ChangeId, ContentId, Edge, EntityId, Graph, Node, Port, PortRef}
  export trellis.Delta.{Change, Op}
