package trellis

import java.nio.file.Files
import trellis.agent.{AgentApi, WorkspacePersistence}
import trellis.Core.*
import trellis.Delta.*
import trellis.TestSupport.*

object WorkspacePersistenceTest:
  private def right[A](value: Either[String, A]): A = value.fold(err => throw new AssertionError(err), identity)

  val tests = Vector(
    Test("workspace snapshot restores a sealed change and clean session", () => {
      val path = Files.createTempFile("trellis-workspace", ".json")
      try
        val (base, assemblyId) = right(AgentApi.openSqueakImage)
        val staged = right(AgentApi.stageOperations(
          base,
          Vector(Op.ReplaceEntity(EntityId("workspace.persisted"), Node("app.function", attrs = Map("source" -> "7")))),
          Vector("workspace.persisted := 7")
        ))
        val committed = right(AgentApi.commit(staged._1, "persisted workspace change"))
        right(WorkspacePersistence.save(path, assemblyId, committed._1))
        val (fresh, _) = right(AgentApi.openSqueakImage)
        val restored = right(WorkspacePersistence.restore(fresh, assemblyId, path))
        check(!restored.session.isDirty)
        check(restored.store.changes.contains(committed._2.changeId))
        check(right(AgentApi.preview(restored)).entities.contains(EntityId("workspace.persisted")))
      finally Files.deleteIfExists(path)
    }),
    Test("workspace snapshot restores an open workspace delta", () => {
      val path = Files.createTempFile("trellis-open-delta", ".json")
      try
        val (base, assemblyId) = right(AgentApi.openSqueakImage)
        val staged = right(AgentApi.stageOperations(
          base,
          Vector(Op.ReplaceEntity(EntityId("workspace.open"), Node("app.function", attrs = Map("source" -> "9")))),
          Vector("workspace.open := 9")
        ))
        right(WorkspacePersistence.save(path, assemblyId, staged._1))
        val (fresh, _) = right(AgentApi.openSqueakImage)
        val restored = right(WorkspacePersistence.restore(fresh, assemblyId, path))
        check(restored.session.isDirty)
        equal(restored.session.operations.size, 1)
        check(right(AgentApi.preview(restored)).entities.contains(EntityId("workspace.open")))
      finally Files.deleteIfExists(path)
    })
  )
