package su.linka.pictures.ui.app

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import su.linka.pictures.application.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.storage.StorageRuntime
import kotlin.test.*

class BoardSettingsTest {
    private val gate = object : AssistantGate {
        override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String) = OperationResult.Completed(Unit)
        override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard = { check(action, capability, ownerId) }
    }

    @Test fun settingsCommitPreservesOtherPreferencesAndAppliesOnlyExplicitModeAfterCompleted() = runTest {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "u14-board-settings-${kotlin.random.Random.nextLong()}"
        fs.createDirectory(root, mustCreate = true)
        val runtime = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(root, fs, BundledSQLiteDriver())).value
        try {
            val original = PortableSettings(1.5f, true, "ru-RU", mapOf(BoardId("other") to BoardPresentation(false)))
            val snapshot = BoardSettingsSnapshot(runtime.library.listing.value, original)
            val controller = LibraryController(runtime.library, gate)
            val modes = mutableListOf<InteractionMode>()
            var saved: PortableSettings? = null
            assertIs<OperationResult.Completed<Long>>(saveBoardSettings(controller, "owner", null, snapshot,
                BoardId("board"), false, InteractionMode.DIRECT, { saved = it }, { modes += it }))
            assertEquals(original.copy(boards = original.boards + (BoardId("board") to BoardPresentation(false))), saved)
            assertEquals(listOf(InteractionMode.DIRECT), modes)
            assertEquals(saved, assertIs<OperationResult.Completed<PortableSettings>>(runtime.library.settings()).value)

            // Reusing the captured revision conflicts: neither the snapshot nor mode is silently replayed.
            saved = null
            val stale = saveBoardSettings(controller, "owner", null, snapshot, BoardId("board"), true,
                InteractionMode.COMPOSE, { saved = it }, { modes += it })
            assertEquals(ProblemCode.CONFLICT, assertIs<OperationResult.Failed>(stale).problem.code)
            assertNull(saved)
            assertEquals(listOf(InteractionMode.DIRECT), modes)

            val current = BoardSettingsSnapshot(runtime.library.listing.value,
                assertIs<OperationResult.Completed<PortableSettings>>(runtime.library.settings()).value)
            assertIs<OperationResult.Completed<Long>>(saveBoardSettings(controller, "owner", null, current,
                BoardId("board"), true, null, {}, { modes += it }))
            assertEquals(listOf(InteractionMode.DIRECT), modes)

            val unknownStore = object : LibraryStore by runtime.library {
                override suspend fun saveSettings(value: PortableSettings, expectedLibraryRevision: Long, permit: WritePermit): OperationResult<Long> =
                    OperationResult.UnconfirmedCommit
            }
            val unknownController = LibraryController(unknownStore, gate)
            val fresh = current.copy(listing = runtime.library.listing.value)
            assertEquals(OperationResult.UnconfirmedCommit, saveBoardSettings(unknownController, "owner", null, fresh,
                BoardId("board"), false, InteractionMode.COMPOSE, { fail("Unknown must not apply settings") }, { fail("Unknown must not set mode") }))
            assertTrue(unknownController.requiresRestart)
        } finally { runtime.close(); fs.deleteRecursively(root) }
    }
}
