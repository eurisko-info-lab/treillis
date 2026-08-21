package trellis

import trellis.Core.*

/** Graph well-formedness and the tiny trusted resource discipline. */
object Check:
  def validate(graph: Graph): Vector[String] =
    val errors = Vector.newBuilder[String]

    graph.edges.foreach { case (edgeId, edge) =>
      val fromNode = graph.nodes.get(edge.from.node)
      val toNode = graph.nodes.get(edge.to.node)
      if fromNode.isEmpty then errors += s"edge ${edgeId.value.take(8)} source node missing"
      if toNode.isEmpty then errors += s"edge ${edgeId.value.take(8)} target node missing"
      for
        fn <- fromNode
        fp <- fn.port(edge.from.port)
        tn <- toNode
        tp <- tn.port(edge.to.port)
      do
        if fp.direction != Direction.Out then errors += s"edge ${edgeId.value.take(8)} source port is not output"
        if tp.direction != Direction.In then errors += s"edge ${edgeId.value.take(8)} target port is not input"
        if fp.ty != tp.ty then errors += s"edge ${edgeId.value.take(8)} type mismatch: ${fp.ty} != ${tp.ty}"
      if fromNode.exists(_.port(edge.from.port).isEmpty) then errors += s"edge ${edgeId.value.take(8)} source port missing"
      if toNode.exists(_.port(edge.to.port).isEmpty) then errors += s"edge ${edgeId.value.take(8)} target port missing"
    }

    // One producer per input port.
    graph.nodes.foreach { case (id, node) =>
      node.ports.filter(_.direction == Direction.In).foreach { p =>
        if graph.incoming(PortRef(id, p.name)).size > 1 then errors += s"multiple producers for ${id.value.take(8)}.${p.name}"
      }
    }

    // Non-unrestricted output capabilities may not fan out implicitly.
    graph.nodes.foreach { case (id, node) =>
      node.ports.filter(_.direction == Direction.Out).foreach { p =>
        val fanout = graph.outgoing(PortRef(id, p.name)).size
        if fanout > 1 && !p.ty.mode.duplicateAllowed then
          errors += s"illegal duplication of ${p.ty.mode} capability at ${id.value.take(8)}.${p.name}"
      }
    }

    graph.nodes.foreach { case (id, node) => errors ++= validateBuiltin(id, node) }
    errors.result()

  private def validateBuiltin(id: ContentId, node: Node): Vector[String] =
    val p = node.ports.map(x => x.name -> x).toMap
    node.kind match
      case "core.replicate" =>
        p.get("in").toVector.flatMap { in =>
          if in.ty.mode.duplicateAllowed then Vector.empty else Vector(s"replicate ${id.value.take(8)} requires unrestricted input")
        }
      case "core.erase" =>
        p.get("in").toVector.flatMap { in =>
          if in.ty.mode.discardAllowed then Vector.empty else Vector(s"erase ${id.value.take(8)} cannot discard linear input")
        }
      case "core.borrow.shared" => validateBorrow(id, p, Capability.Read)
      case "core.borrow.mut" => validateBorrow(id, p, Capability.Write)
      case "core.hole" =>
        if node.attrs.contains("expected") then Vector.empty else Vector(s"hole ${id.value.take(8)} lacks expected boundary description")
      case _ => Vector.empty

  private def validateBorrow(id: ContentId, ports: Map[String, Port], loanKind: Capability): Vector[String] =
    ports.get("owner") match
      case Some(Port(_, Direction.In, Ty.Cap(Capability.Own, _, inner, _))) =>
        ports.get("loan") match
          case Some(Port(_, Direction.Out, Ty.Cap(kind, _, loanInner, _))) if kind == loanKind && loanInner == inner => Vector.empty
          case _ => Vector(s"borrow ${id.value.take(8)} has invalid loan port")
      case _ => Vector(s"borrow ${id.value.take(8)} requires Own<T> input")
