package trellis

import java.util.Arrays
import trellis.Core.*
import trellis.TestSupport.*

object CanonTest:
  private def rawGraph(
      nodes: Vector[String] = Vector.empty,
      edges: Vector[String] = Vector.empty,
      entities: Vector[String] = Vector.empty,
      roots: Vector[String] = Vector.empty
  ): String =
    Canon.record(
      "graph",
      Vector(
        Canon.record("nodes", nodes),
        Canon.record("edges", edges),
        Canon.record("entities", entities),
        Canon.record("roots", roots)
      )
    )

  private def nodeEntry(node: Node): (ContentId, String) =
    val id = Canon.nodeId(node)
    id -> Canon.record("n", Vector(id.value, Canon.encodeNode(node)))

  private def fixture(name: String): Array[Byte] =
    val path = s"/trellis/canon/adversarial/$name"
    val stream = Option(getClass.getResourceAsStream(path)).getOrElse(
      throw new IllegalStateException(s"missing shared canonical fixture $path")
    )
    try stream.readAllBytes()
    finally stream.close()

  val tests = Vector(
    Test("canonical maps ignore insertion order", () => {
      val n1 = Node("x", attrs = Map("b" -> "2", "a" -> "1"))
      val n2 = Node("x", attrs = Map("a" -> "1", "b" -> "2"))
      equal(Canon.nodeId(n1), Canon.nodeId(n2))

      val a = Node("a")
      val b = Node("b")
      val aid = Canon.nodeId(a)
      val bid = Canon.nodeId(b)
      val g1 = Graph(nodes = Map(aid -> a, bid -> b))
      val g2 = Graph(nodes = Map(bid -> b, aid -> a))
      check(Arrays.equals(Canon.encodeGraphBytes(g1), Canon.encodeGraphBytes(g2)))
    }),
    Test("F0 graph has its frozen cross-process foundation root", () => {
      equal(Canon.graphId(Bootstrap.f0).value, Bootstrap.F0Root)
    }),
    Test("canonical graph text and bytes round trip exactly", () => {
      val graph = Bootstrap.graph
      val text = Canon.encodeGraph(graph)
      val bytes = Canon.encodeGraphBytes(graph)
      equal(Canon.decodeGraph(text), Right(graph))
      equal(Canon.decodeGraphBytes(bytes), Right(graph))
      check(Arrays.equals(bytes, Canon.encodeGraphBytes(Canon.decodeGraphBytes(bytes).toOption.get)))
    }),
    Test("decoder rejects duplicate map keys", () => {
      val (_, entry) = nodeEntry(Node("x"))
      check(Canon.decodeGraph(rawGraph(nodes = Vector(entry, entry))).isLeft)
    }),
    Test("decoder rejects unsorted canonical collections", () => {
      val entries = Vector(nodeEntry(Node("a")), nodeEntry(Node("b"))).sortBy(_._1.value)
      val reversed = entries.reverse.map(_._2)
      check(Canon.decodeGraph(rawGraph(nodes = reversed)).isLeft)
    }),
    Test("decoder rejects malformed content ids", () => {
      val node = Node("x")
      val badEntry = Canon.record("n", Vector("not-a-hash", Canon.encodeNode(node)))
      check(Canon.decodeGraph(rawGraph(nodes = Vector(badEntry))).isLeft)
    }),
    Test("decoder rejects malformed UTF-8 bytes", () => {
      check(Canon.decodeGraphBytes(Array[Byte]('1'.toByte, ':'.toByte, 0xff.toByte)).isLeft)
    }),
    Test("decoder rejects trailing bytes and noncanonical atom lengths", () => {
      val canonical = Canon.encodeGraph(Bootstrap.graph)
      check(Canon.decodeGraph(canonical + Canon.record("junk", Vector.empty)).isLeft)
      check(canonical.startsWith("5:graph"))
      check(Canon.decodeGraph("05:graph" + canonical.drop("5:graph".length)).isLeft)
    }),
    Test("decoder rejects invalid graph references", () => {
      val absent = "0" * 64
      val entity = Canon.record("entity", Vector("missing", absent))
      check(Canon.decodeGraph(rawGraph(entities = Vector(entity))).isLeft)

      val source = Node("source")
      val target = Node("target")
      val (g1, sid) = Canon.addNode(Graph(), source)
      val (g2, tid) = Canon.addNode(g1, target)
      val badEdge = Edge(PortRef(sid, "missing"), PortRef(tid, "also-missing"))
      val g3 = Canon.addEdge(g2, badEdge)._1
      check(Canon.decodeGraph(Canon.encodeGraph(g3)).isLeft)
    }),
    Test("shared adversarial canonical fixtures are rejected", () => {
      Vector(
        "malformed-utf8.bin",
        "nonminimal-atom.bin",
        "trailing-data.bin",
        "duplicate-node-key.bin",
        "unordered-nodes.bin",
        "missing-reference.bin"
      ).foreach { name =>
        check(Canon.decodeGraphBytes(fixture(name)).isLeft, s"fixture unexpectedly accepted: $name")
      }
    }),
    Test("F0 self-describes its constitutional vocabulary", () => {
      check(Bootstrap.f0ConstitutionalEntities.subsetOf(Bootstrap.f0.entities.keySet))
      Bootstrap.f0ConstitutionalEntities.foreach { entity =>
        check(Bootstrap.f0.entity(entity).exists(_.kind == "meta.node-kind"), s"missing F0 self-description for ${entity.value}")
      }
    })
  )
