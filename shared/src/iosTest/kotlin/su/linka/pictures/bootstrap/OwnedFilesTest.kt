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
}
