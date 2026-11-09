package su.linka.pictures.bootstrap

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.Sink
import okio.Source
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import kotlin.test.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class OwnedFilesTest {
    @Test fun foreignNativeDestinationIsNeverClaimedOrClosedByAnyRootOwner() = runTest {
        val owners = activeOwners()
        var closes = 0
        val native = Destinations(listOf(object : ExportDestination {
            override val ownerId = "b"
            override suspend fun write(file: LocalFile, guard: CommitGuard): OperationResult<Unit> = error("Not used")
            override fun close() { closes++ }
        }))
        val files = OwnedFiles(native, owners)
        assertEquals(OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED)), files.chooseDestination("backup", "application/octet-stream", "a"))
        files.revoke("a")
        files.revoke("b")
        assertEquals(0, closes)
    }

    @Test fun oneThrowingDestinationDoesNotPreventReleasingAnotherAndFailureIsReturnedToRoot() = runTest {
        val owners = activeOwners()
        val closed = mutableSetOf<Int>()
        val native = Destinations((0..1).map { index -> object : ExportDestination {
            override val ownerId = "a"
            override suspend fun write(file: LocalFile, guard: CommitGuard): OperationResult<Unit> = error("Not used")
            override fun close() { closed += index; if (index == 0) error("controlled native close failure") }
        } })
        val files = OwnedFiles(native, owners)
        repeat(2) { assertIs<OperationResult.Completed<ExportDestination>>(files.chooseDestination("backup", "application/octet-stream", "a")) }
        owners.close("a")
        assertEquals(Problem(ProblemCode.IO_FAILURE), files.revoke("a"))
        assertEquals(setOf(0, 1), closed)
    }

    @Test fun cancelledNativeDeliveryIsDiscardedInsteadOfLeakingALateUri() = runTest {
        val owners = OwnerWindows()
        owners.openWindow("w"); owners.setActive("w", true); owners.open("owner", "w")
        val gate = CompletableDeferred<Unit>()
        val temporary = TemporaryFile("/owned-test/input", "input", "application/octet-stream")
        val discarded = mutableListOf<TemporaryFile>()
        val native = object : PlatformFilesPort {
            override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> {
                suspendCoroutine<Unit> { continuation -> gate.invokeOnCompletion { continuation.resume(Unit) } }
                return OperationResult.Completed(temporary)
            }
            override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> = error("Not used")
            override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> = error("Not used")
            override fun source(file: LocalFile): OperationResult<Source> = error("Not used")
            override fun sink(file: TemporaryFile): OperationResult<Sink> = error("Not used")
            override fun discard(file: TemporaryFile) { discarded += file }
        }
        val files = OwnedFiles(native, owners)
        var delivered = false
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            files.pick(PickedFileKind.IMAGE, "owner")
            delivered = true
        }
        owners.close("owner")
        files.revoke("owner")
        gate.complete(Unit)
        worker.join()
        assertTrue(worker.isCancelled)
        assertFalse(delivered)
        assertEquals(listOf(temporary), discarded)
    }

    private fun activeOwners() = OwnerWindows().also {
        it.openWindow("w"); it.setActive("w", true); it.open("a", "w"); it.open("b", "w")
    }
    private class Destinations(private val values: List<ExportDestination>) : PlatformFilesPort {
        private var index = 0
        override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> =
            OperationResult.Completed(values[index++])
        override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> = error("Not used")
        override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> = error("Not used")
        override fun source(file: LocalFile): OperationResult<Source> = error("Not used")
        override fun sink(file: TemporaryFile): OperationResult<Sink> = error("Not used")
        override fun discard(file: TemporaryFile) { error("Not used") }
    }
}
