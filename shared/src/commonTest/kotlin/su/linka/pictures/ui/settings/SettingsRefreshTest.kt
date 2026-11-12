package su.linka.pictures.ui.settings

import su.linka.pictures.testing.testSQLiteDriver
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import su.linka.pictures.application.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.PortableSettings
import su.linka.pictures.storage.StorageRuntime
import kotlin.test.*

class SettingsRefreshTest {
    private class Gate : AssistantGate {
        var allowed = true
        override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): OperationResult<Unit> =
            if (allowed && ownerId == "owner") OperationResult.Completed(Unit)
            else OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
        override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard =
            { check(action, capability, ownerId) }
    }

    private suspend fun fresh(test: suspend (LibraryStore, Gate) -> Unit) {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "u14-settings-${kotlin.random.Random.nextLong()}"
        fs.createDirectory(root, mustCreate = true)
        val runtime = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(root, fs, testSQLiteDriver())).value
        try { test(runtime.library, Gate()) } finally { runtime.close(); fs.deleteRecursively(root) }
    }

    @Test fun twoKnownSavesEachReloadOnceAndCaptureFreshRevisionForTheNextSave() = runTest { fresh { real, gate ->
        var reads = 0
        val library = object : LibraryStore by real {
            override suspend fun settings(): OperationResult<PortableSettings> { reads++; return real.settings() }
        }
        val controller = LibraryController(library, gate)
        var shown = assertIs<OperationResult.Completed<SettingsSnapshot>>(readSettingsSnapshot(library)).value
        val saved = mutableListOf<PortableSettings>()
        val values = listOf(PortableSettings(highContrast = true, messageRowPercent = 16),
            PortableSettings(1.7f, true, "xx-Unknown", messageRowPercent = 32))
        values.forEachIndexed { index, value ->
            val previous = shown
            val write = controller.settings("owner", null, value, shown.listing.revision, shown.listing.epoch)
            assertIs<OperationResult.Completed<Long>>(write)
            shown = assertIs<OperationResult.Completed<SettingsSnapshot>>(reloadSettingsAfterSave(library, write, value) {
                saved += it
                assertEquals(index + 1, reads, "onSaved must run before the authoritative read")
            }).value
            assertTrue(shown.listing.revision > previous.listing.revision)
            assertEquals(previous.listing.epoch, shown.listing.epoch)
            assertEquals(value, shown.value)
            assertEquals(index + 2, reads)
        }
        assertEquals(values, saved)
    } }

    @Test fun failedWritesDoNotReadOrReplaceTheCapturedInputAndSnapshot() = runTest { fresh { real, gate ->
        val shown = assertIs<OperationResult.Completed<SettingsSnapshot>>(readSettingsSnapshot(real)).value
        val entered = PortableSettings(1.8f, true, "en-US", messageRowPercent = 28)
        var reads = 0
        val library = object : LibraryStore by real {
            override suspend fun settings(): OperationResult<PortableSettings> { reads++; return real.settings() }
        }
        val controller = LibraryController(library, gate)
        gate.allowed = false
        val denied = controller.settings("owner", null, entered, shown.listing.revision, shown.listing.epoch)
        assertEquals(ProblemCode.UNAUTHORIZED, assertIs<OperationResult.Failed>(denied).problem.code)
        gate.allowed = true
        assertIs<OperationResult.Completed<Long>>(controller.settings("owner", null, PortableSettings(highContrast = true), shown.listing.revision, shown.listing.epoch))
        val conflict = controller.settings("owner", null, entered, shown.listing.revision, shown.listing.epoch)
        assertEquals(ProblemCode.CONFLICT, assertIs<OperationResult.Failed>(conflict).problem.code)
        var retainedSnapshot = shown
        var retainedInput = entered
        for (outcome in listOf(denied, conflict, OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST)),
            OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)), OperationResult.Cancelled, OperationResult.UnconfirmedCommit)) {
            val reload = reloadSettingsAfterSave(library, outcome, entered) { fail("A failed or unknown write is not known saved") }
            if (reload is OperationResult.Completed) { retainedSnapshot = reload.value; retainedInput = reload.value.value }
            assertNull(reload)
        }
        assertSame(shown, retainedSnapshot)
        assertSame(entered, retainedInput)
        assertEquals(0, reads)
    } }

    @Test fun postCommitReadFailureKeepsKnownSaveAndOnlyExplicitReadRecoversWithoutAnotherWrite() = runTest { fresh { real, gate ->
        var writes = 0
        var reads = 0
        var failRead = false
        val events = mutableListOf<String>()
        val library = object : LibraryStore by real {
            override suspend fun settings(): OperationResult<PortableSettings> {
                reads++; events += "read"
                return if (failRead) OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) else real.settings()
            }
            override suspend fun saveSettings(value: PortableSettings, expectedLibraryRevision: Long, permit: WritePermit): OperationResult<Long> {
                writes++
                return real.saveSettings(value, expectedLibraryRevision, permit)
            }
        }
        val shown = assertIs<OperationResult.Completed<SettingsSnapshot>>(readSettingsSnapshot(library)).value
        val value = PortableSettings(1.5f, true, "en-US", messageRowPercent = 24)
        val write = LibraryController(library, gate).settings("owner", null, value, shown.listing.revision, shown.listing.epoch)
        assertIs<OperationResult.Completed<Long>>(write)
        failRead = true
        events.clear()
        val reload = reloadSettingsAfterSave(library, write, value) { assertEquals(value, it); events += "saved" }
        assertEquals(ProblemCode.IO_FAILURE, assertIs<OperationResult.Failed>(reload).problem.code)
        assertEquals(listOf("saved", "read"), events)
        assertEquals(value, assertIs<OperationResult.Completed<PortableSettings>>(real.settings()).value)
        assertEquals(2, reads)
        assertEquals(1, writes)
        failRead = false
        val recovered = assertIs<OperationResult.Completed<SettingsSnapshot>>(readSettingsSnapshot(library)).value
        assertTrue(recovered.listing.revision > shown.listing.revision)
        assertEquals(value, recovered.value)
        assertEquals(3, reads)
        assertEquals(1, writes)
    } }

    @Test fun revisionChangedDuringReadDoesNotReenableAnObsoleteSnapshot() = runTest { fresh { real, gate ->
        val listing = real.listing.value
        val library = object : LibraryStore by real {
            override suspend fun settings(): OperationResult<PortableSettings> {
                val read = real.settings()
                assertIs<OperationResult.Completed<Long>>(LibraryController(real, gate).settings(
                    "owner", null, PortableSettings(highContrast = true), listing.revision, listing.epoch))
                return read
            }
        }
        val read = readSettingsSnapshot(library)
        assertEquals(ProblemCode.STALE_REQUEST, assertIs<OperationResult.Failed>(read).problem.code)
    } }
}
