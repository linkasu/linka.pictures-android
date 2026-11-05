package su.linka.pictures.application.library

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.storage.StorageRuntime
import kotlin.test.*

class LibraryControllerTest {
    private class Gate : AssistantGate {
        val capability = AssistantCapability("test-only", "owner")
        var generation = 0
        var allowed = true
        val actions = mutableListOf<ProtectedAction>()
        override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): OperationResult<Unit> =
            if (allowed && capability === this.capability && ownerId == "owner") OperationResult.Completed(Unit)
            else OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
        override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard {
            actions += action
            val issued = generation
            return { if (issued != generation) OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED)) else check(action, capability, ownerId) }
        }
    }
    private suspend fun fresh(test: suspend (StorageRuntime, Gate) -> Unit) {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "p13-library-${kotlin.random.Random.nextLong()}"
        fs.createDirectory(root, mustCreate = true)
        val runtime = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(root, fs, BundledSQLiteDriver())).value
        try { test(runtime, Gate()) } finally { runtime.close(); fs.deleteRecursively(root) }
    }
    private fun permit(runtime: StorageRuntime) = WritePermit(runtime.library.listing.value.epoch) { OperationResult.Completed(Unit) }
    private fun document() = BoardDocument(BoardId("board"), "Исходная доска", listOf(BoardPage(PageId("page"), 1, 1, emptyList())))
    private fun OperationResult<*>.hasProblem(code: ProblemCode) = assertEquals(code, assertIs<OperationResult.Failed>(this).problem.code)

    @Test fun unauthorizedRenameDeleteDraftAndSettingsLeaveRealSqliteUnchanged() = runTest { fresh { runtime, gate ->
        val library = runtime.library
        assertIs<OperationResult.Completed<PublishedBoard>>(library.addBoard(document(), permit(runtime)))
        val draft = assertIs<OperationResult.Completed<StoredDraft>>(library.saveDraft(
            EditorDraft(DraftId("draft"), null, null, document().copy(id = BoardId("draft-board"))), null, permit(runtime))).value
        val listing = library.listing.value
        val entry = listing.items.single()
        val summary = assertIs<OperationResult.Completed<List<DraftSummary>>>(library.drafts()).value.single()
        val controller = LibraryController(library, gate)
        controller.rename("owner", null, entry, "Запрещено", listing.epoch).hasProblem(ProblemCode.UNAUTHORIZED)
        controller.delete("foreign", gate.capability, entry, listing.epoch).hasProblem(ProblemCode.UNAUTHORIZED)
        controller.deleteDraft("owner", null, summary, listing.epoch).hasProblem(ProblemCode.UNAUTHORIZED)
        controller.settings("owner", null, PortableSettings(highContrast = true), listing.revision, listing.epoch).hasProblem(ProblemCode.UNAUTHORIZED)
        assertEquals(listing, library.listing.value)
        val retained = assertIs<OperationResult.Completed<LeasedDraft>>(library.draft(draft.draft.id)).value
        retained.lease.close()
        assertEquals(PortableSettings(), assertIs<OperationResult.Completed<PortableSettings>>(library.settings()).value)
        assertEquals(listOf(ProtectedAction.RENAME_BOARD, ProtectedAction.DELETE_BOARD, ProtectedAction.DELETE_DRAFT, ProtectedAction.CHANGE_SETTINGS), gate.actions)
    } }

    @Test fun shownRevisionsAndEpochNeverOverwriteNewerData() = runTest { fresh { runtime, gate ->
        val library = runtime.library
        assertIs<OperationResult.Completed<PublishedBoard>>(library.addBoard(document(), permit(runtime)))
        val shown = library.listing.value
        val entry = shown.items.single()
        assertIs<OperationResult.Completed<PublishedBoard>>(library.renameBoard(entry.id, "Другая правка", entry.revision, permit(runtime)))
        val newer = library.listing.value
        val controller = LibraryController(library, gate)
        controller.rename("owner", gate.capability, entry, "Старая правка", shown.epoch).hasProblem(ProblemCode.CONFLICT)
        controller.delete("owner", gate.capability, entry, shown.epoch).hasProblem(ProblemCode.CONFLICT)
        controller.settings("owner", gate.capability, PortableSettings(highContrast = true), shown.revision, shown.epoch).hasProblem(ProblemCode.CONFLICT)
        controller.delete("owner", gate.capability, newer.items.single(), shown.epoch + 1).hasProblem(ProblemCode.STALE_REQUEST)
        assertEquals(newer, library.listing.value)
        assertEquals("Другая правка", library.listing.value.items.single().name)
    } }

    @Test fun guardRevokedAfterAdmissionRollsBackInsideRealStorage() = runTest { fresh { runtime, gate ->
        val real = runtime.library
        assertIs<OperationResult.Completed<PublishedBoard>>(real.addBoard(document(), permit(runtime)))
        val shown = real.listing.value
        val library = object : LibraryStore by real {
            override suspend fun renameBoard(id: BoardId, name: String, expectedRevision: Long, permit: WritePermit): OperationResult<PublishedBoard> {
                gate.generation++
                return real.renameBoard(id, name, expectedRevision, permit)
            }
        }
        LibraryController(library, gate).rename("owner", gate.capability, shown.items.single(), "Не должно записаться", shown.epoch)
            .hasProblem(ProblemCode.UNAUTHORIZED)
        assertEquals(shown, real.listing.value)
        assertEquals(1, gate.actions.size)
    } }

    @Test fun unconfirmedOutcomeFreezesFurtherWritesUntilNewApplicationInstance() = runTest { fresh { runtime, gate ->
        val real = runtime.library
        assertIs<OperationResult.Completed<PublishedBoard>>(real.addBoard(document(), permit(runtime)))
        val shown = real.listing.value
        var calls = 0
        val library = object : LibraryStore by real {
            override suspend fun renameBoard(id: BoardId, name: String, expectedRevision: Long, permit: WritePermit): OperationResult<PublishedBoard> {
                calls++
                return OperationResult.UnconfirmedCommit
            }
        }
        val controller = LibraryController(library, gate)
        repeat(2) { assertEquals(OperationResult.UnconfirmedCommit, controller.rename("owner", gate.capability, shown.items.single(), "Unknown", shown.epoch)) }
        assertEquals(OperationResult.UnconfirmedCommit, controller.delete("owner", gate.capability, shown.items.single(), shown.epoch))
        assertEquals(1, calls)
        assertEquals(shown, real.listing.value)
    } }

    @Test fun knownSuccessfulWritesUpdateListingAndPreserveOpaqueSettings() = runTest { fresh { runtime, gate ->
        val library = runtime.library
        assertIs<OperationResult.Completed<PublishedBoard>>(library.addBoard(document(), permit(runtime)))
        val controller = LibraryController(library, gate)
        val shown = library.listing.value
        assertIs<OperationResult.Completed<PublishedBoard>>(controller.rename("owner", gate.capability, shown.items.single(), "Новое имя", shown.epoch))
        val renamed = library.listing.value
        val settings = PortableSettings(1.7f, true, "en-US", mapOf(BoardId("board") to BoardPresentation(false)))
        assertIs<OperationResult.Completed<Long>>(controller.settings("owner", gate.capability, settings, renamed.revision, renamed.epoch))
        assertEquals(settings, assertIs<OperationResult.Completed<PortableSettings>>(library.settings()).value)
        val current = library.listing.value
        assertIs<OperationResult.Completed<Unit>>(controller.delete("owner", gate.capability, current.items.single(), current.epoch))
        assertTrue(library.listing.value.items.isEmpty())
    } }
}
