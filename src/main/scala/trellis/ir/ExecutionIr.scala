package trellis.ir

import trellis.Core.*

/** Versioned contract shared by language lowerings and execution engines. */
object ExecutionIr:
  val NatV1 = "trellis.ir.nat/v1"
  val nodeKinds: Set[String] = Set(
    "ir.function", "ir.parameter", "ir.reference", "ir.constructor", "ir.binary",
    "ir.call", "ir.match", "ir.case", "ir.pattern.constructor"
  )

  def validateReachable(graph: Graph, root: EntityId): Either[String, Set[ContentId]] =
    graph.entities.get(root).toRight(s"unknown IR root ${root.value}").flatMap { rootId =>
      val seen = scala.collection.mutable.Set(rootId)
      val queue = scala.collection.mutable.Queue(rootId)
      while queue.nonEmpty do
        val current = queue.dequeue()
        graph.edges.valuesIterator.filter(_.from.node == current).foreach { edge =>
          if seen.add(edge.to.node) then queue.enqueue(edge.to.node)
        }
      val unsupported = seen.toVector.flatMap(id => graph.node(id).filter(node => node.kind.startsWith("ir.") && !nodeKinds(node.kind)).map(node => s"${id.value}:${node.kind}"))
      if unsupported.isEmpty then Right(seen.toSet) else Left(s"unsupported $NatV1 nodes: ${unsupported.sorted.mkString(", ")}")
    }
