package trellis.agent

import java.nio.file.Path
import trellis.Core.*
import trellis.engine.Engines
import trellis.Navigate

/** Shared local graph image used by the HTTP agent API and MCP server. */
object AgentRuntime:
  private var assemblyId = ""
  private var image: AgentApi.Image = AgentApi.Image(
    trellis.storage.RepositoryProducts.Store(),
    trellis.storage.RepositoryProducts.WorkspaceSession(BranchId("pending")),
    trellis.storage.RepositoryProducts.Ledger()
  )
  private var workspacePath: Path = WorkspacePersistence.defaultPath
  private var started = false

  def start(workspaceFile: Option[Path] = None): Either[String, String] =
    synchronized:
      if started then Right(assemblyId)
      else
        workspaceFile.foreach(path => workspacePath = path)
        AgentApi.openSqueakImage.flatMap { case (base, id) =>
          WorkspacePersistence.restore(base, id, workspacePath).flatMap { restored =>
            AgentApi.preview(restored).map { _ =>
              assemblyId = id
              image = restored
              started = true
              installShutdownHook()
              id
            }
          }
        }

  def assembly: String = synchronized(assemblyId)

  def save(): Either[String, Unit] = synchronized:
    if !started then Right(())
    else WorkspacePersistence.save(workspacePath, assemblyId, image)

  def withGraph[A](operation: Graph => Either[String, A]): Either[String, A] =
    synchronized(AgentApi.preview(image).flatMap(operation))

  def mutate(operation: AgentApi.Image => Either[String, (AgentApi.Image, String)]): Either[String, String] =
    synchronized:
      operation(image).flatMap { case (next, body) =>
        image = next
        persist()
        Right(body)
      }

  private def persist(): Unit =
    save().fold(err => System.err.println(s"workspace persistence failed: $err"), _ => ())

  private def installShutdownHook(): Unit =
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      persist()
      System.err.println(s"saved workspace delta to ${workspacePath.toAbsolutePath}")
    }))

  def listEntities(prefix: Option[String], kind: Option[String], limit: Int): Either[String, String] =
    withGraph(graph => Right(AgentApi.encodeEntities(graph, prefix, kind, limit)))

  def entity(path: String): Either[String, String] =
    withGraph(graph => AgentApi.encodeEntityDetail(graph, path))

  def navigate(center: String): Either[String, String] =
    for
      selection <- AgentApi.parseSelection(center)
      body <- withGraph(graph => Navigate.graphView(graph, selection).map(AgentApi.encodeNavigate))
    yield body

  def history(entityPath: String): Either[String, String] =
    synchronized(Right(AgentApi.encodeHistory(image.store, entityPath)))

  def status: Either[String, String] =
    withGraph(graph => Right(AgentApi.encodeStatus(assemblyId, image, graph)))

  def graph: Either[String, String] =
    withGraph(g => Right(AgentApi.encodeGraph(g, assemblyId)))

  def lspDocument: Either[String, String] =
    withGraph(AgentApi.encodeLspDocument)

  def applyOps(body: String): Either[String, String] =
    for
      parsed <- AgentApi.decodeOpsRequest(body)
      (operations, transcript) = parsed
      body <- mutate(AgentApi.stageOperations(_, operations, transcript).map { case (next, graph) =>
        next -> AgentApi.encodeGraphRoot(graph)
      })
    yield body

  def commit(message: String): Either[String, String] =
    mutate(img => AgentApi.commit(img, message).map { case (next, committed) =>
      next -> AgentApi.encodeCommit(committed.changeId, committed.graph)
    })

  def publish(packageName: String, branch: String, publisher: String): Either[String, String] =
    mutate(img => AgentApi.publish(img, packageName, branch, publisher).map { case (next, publication) =>
      next -> AgentApi.encodePublish(publication)
    })

  def execute(workspace: String, arg: BigInt, engineName: String): Either[String, String] =
    synchronized:
      for
        _ <- Either.cond(arg >= 0, (), "arg must be a Nat")
        engine <- Engines.named(engineName)
        graph <- AgentApi.preview(image)
        started = System.nanoTime()
        run <- engine.execute(trellis.engine.ExecutionRequest(graph, EntityId(workspace), Vector(arg)))
        elapsed = (System.nanoTime() - started).toDouble / 1000000.0
      yield AgentApi.encodeRun(engine.id, run, elapsed)

  def replaceEntity(entity: String, kind: String, source: String): Either[String, String] =
    applyOps(
      AgentJson.objectFields(
        Vector(
          "operations" -> s"""[{"replaceEntity":{"entity":${AgentJson.quote(entity)},"kind":${AgentJson.quote(kind)},"attrs":{"source":${AgentJson.quote(source)}}}}]""",
          "transcript" -> AgentJson.stringArray(Vector(s"$entity := $source"))
        )
      )
    )
