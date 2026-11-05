package su.linka.pictures.platform.files

import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import okio.buffer
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*

class AndroidPlatformFilesPortTest {
    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun next() = checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Expected actual dispatch" }
    }
    private fun <T> completed(result: OperationResult<T>): T {
        assertTrue("Expected native result: $result", result is OperationResult.Completed)
        return (result as OperationResult.Completed).value
    }
    private fun code(expected: ProblemCode, result: OperationResult<*>) { assertTrue(result is OperationResult.Failed); assertEquals(expected, (result as OperationResult.Failed).problem.code) }

    @Test fun uuidTempRegistryNeverUsesDisplayNameAsPathOrAcceptsForeignIdentity() = runBlocking {
        val f = FilesTestRig()
        try { withContext(Dispatchers.IO) {
            val file = completed(f.port.createTemporaryFile("мёд data.bin", "application/octet-stream"))
            assertEquals("мёд data.bin", file.displayName)
            assertNotEquals("мёд data.bin", File(file.path).name)
            assertEquals(f.temp.toString(), File(file.path).parent)
            code(ProblemCode.NOT_FOUND, f.port.source(TemporaryFile(file.path)))
            code(ProblemCode.NOT_FOUND, f.port.sink(TemporaryFile(file.path)))
            f.port.discard(TemporaryFile(file.path)); assertTrue(File(file.path).exists())
            completed(f.port.sink(file)).buffer().use { it.writeUtf8("exact") }
            code(ProblemCode.CONFLICT, f.port.sink(file))
            assertEquals("exact", completed(f.port.source(file)).buffer().use { it.readUtf8() })
        } } finally { f.close() }
    }
    @Test fun invalidLeafMimeAndSyncMainCallsFailWithoutFilesystemWork() = runBlocking {
        val f = FilesTestRig()
        try {
            withContext(Dispatchers.Main) { code(ProblemCode.IO_FAILURE, f.port.createTemporaryFile("file", "image/png")) }
            withContext(Dispatchers.IO) {
                for (name in listOf("", ".", "..", "../escape", "x\\y", "a\u0000b", "\uD800")) code(ProblemCode.INVALID_DATA, f.port.createTemporaryFile(name, "image/png"))
                for (type in listOf("", "image", "image/*", "image/png\n", "имя/png")) code(ProblemCode.INVALID_DATA, f.port.createTemporaryFile("file", type))
            }
            assertFalse(File(f.temp.toString()).exists())
        } finally { f.close() }
    }
    @Test fun discardOnMainInvalidatesImmediatelyAndUnlinksOnlyOnIo() = runBlocking {
        val f = FilesTestRig()
        try {
            val file = f.temporary("discard".toByteArray())
            withContext(Dispatchers.Main) { f.port.discard(file); f.port.discard(file) }
            withContext(Dispatchers.IO) { code(ProblemCode.NOT_FOUND, f.port.source(file)) }
            assertEquals(file.path, withTimeout(5000) { f.deleted.receive() }.toString())
            assertFalse(File(file.path).exists())
        } finally { f.close() }
    }
    @Test fun nonemptyOwnedTempCannotBeTruncatedAndMediaSourceCannotReadCredentialsOrSymlinks() = runBlocking {
        val f = FilesTestRig()
        try {
            withContext(Dispatchers.IO) {
                val file = completed(f.port.createTemporaryFile("file", "application/octet-stream"))
                File(file.path).writeText("prior bytes") // Native producer may fill a temp without files.sink (e.g. TTS).
                code(ProblemCode.CONFLICT, f.port.sink(file)); assertEquals("prior bytes", File(file.path).readText())
                val credential = File(f.root, "credentials.json").also { it.writeText("private fixture") }
                code(ProblemCode.NOT_FOUND, f.port.source(LocalFile(credential.path)))
                val fakeMedia = File(f.media.toString(), "credentials.json").also { it.parentFile!!.mkdirs(); it.writeText("private fixture") }
                code(ProblemCode.NOT_FOUND, f.port.source(LocalFile(fakeMedia.path)))
                f.port.discard(TemporaryFile(credential.path)); assertTrue(credential.isFile)
            }
            val media = f.immutable("immutable".toByteArray())
            withContext(Dispatchers.IO) {
                assertEquals("immutable", completed(f.port.source(media)).buffer().use { it.readUtf8() })
                val alias = f.media / "ff" / "f".repeat(64)
                f.fs.createDirectories(alias.parent!!)
                android.system.Os.symlink(File(f.root,"credentials.json").path, alias.toString())
                code(ProblemCode.NOT_FOUND, f.port.source(LocalFile(alias.toString())))
                f.port.discard(TemporaryFile(alias.toString()))
                assertTrue(File(f.root,"credentials.json").isFile)
            }
        } finally { f.close() }
    }
    @Test fun pickCopiesActualBytesAndClosesCursorAndTransientProviderStreamWithMinimalIntents() = runBlocking {
        val f = FilesTestRig()
        try {
            val before = f.provider.file.readBytes()
            for (kind in PickedFileKind.entries) {
                val result = completed(f.port.pick(kind, "owner"))
                assertArrayEquals(before, File(result.path).readBytes()); assertEquals(f.provider.name, result.displayName)
                assertEquals("r", withTimeout(5000) { f.provider.closed.receive() })
                val intent = f.intents.last()
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action); assertTrue(intent.categories.contains(Intent.CATEGORY_OPENABLE))
                assertEquals(0, intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                assertEquals(if (kind == PickedFileKind.IMAGE) "image/*" else if (kind == PickedFileKind.AUDIO) "audio/*" else "*/*", intent.type)
                f.port.discard(result)
            }
            assertEquals(4, f.provider.queryClosed); assertEquals(4, f.ids.toSet().size)
            assertArrayEquals(before, f.provider.file.readBytes()); assertEquals(0, f.provider.deletes)
            assertEquals(64L * 1024 * 1024, NativeFileRules.budget(PickedFileKind.IMAGE))
            assertEquals(64L * 1024 * 1024, NativeFileRules.budget(PickedFileKind.AUDIO))
            assertEquals(500L * 1024 * 1024, NativeFileRules.budget(PickedFileKind.LINKA))
            assertEquals(2L * 1024 * 1024 * 1024, NativeFileRules.budget(PickedFileKind.BACKUP))
        } finally { f.close() }
    }
    @Test fun pickRejectsForeignUriAndUnsafeMetadataRatherThanUsingThemAsPaths() = runBlocking {
        val f = FilesTestRig()
        try {
            f.requester = { _, _, _ -> OperationResult.Completed(Uri.fromFile(f.provider.file)) }
            code(ProblemCode.INVALID_DATA, f.port.pick(PickedFileKind.IMAGE, "owner"))
            f.requester = { _, _, _ -> OperationResult.Completed(f.provider.uri) }; f.provider.name = "../escape"
            code(ProblemCode.INVALID_DATA, f.port.pick(PickedFileKind.IMAGE, "owner"))
            assertEquals(0, f.provider.reads); assertFalse(File(f.temp.toString()).exists())
        } finally { f.close() }
    }
    @Test fun cancelAndBackgroundAfterRequesterNeverReturnLateOwnedFile() = runBlocking {
        val f = FilesTestRig()
        try {
            f.requester = { _, _, _ -> OperationResult.Cancelled }
            assertEquals(OperationResult.Cancelled, f.port.pick(PickedFileKind.IMAGE, "owner"))
            f.requester = { _, _, _ -> f.active.set(false); OperationResult.Completed(f.provider.uri) }
            assertEquals(OperationResult.Cancelled, f.port.pick(PickedFileKind.IMAGE, "owner"))
            assertEquals(0, f.provider.reads); assertFalse(File(f.temp.toString()).exists())
        } finally { f.close() }
    }
    @Test fun pickActualStreamBudgetCannotBeBypassedByProviderMetadata() = runBlocking {
        val f = FilesTestRig()
        try {
            RandomAccessFile(f.provider.file, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
            code(ProblemCode.LIMIT_EXCEEDED, f.port.pick(PickedFileKind.IMAGE, "owner"))
            assertEquals("r", withTimeout(5000) { f.provider.closed.receive() })
            withTimeout(5000) { f.deleted.receive() }
            assertEquals(0, f.provider.deletes)
        } finally { f.close() }
    }
    @Test fun choosingDestinationDoesNotWriteAndGuardAndPrivateSourceAreCheckedBeforeTruncate() = runBlocking {
        val f = FilesTestRig()
        try {
            val before = f.provider.file.readBytes()
            val destination = completed(f.port.chooseDestination("backup.linkabackup", "application/octet-stream", "owner"))
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, f.intents.last().action)
            assertEquals("backup.linkabackup", f.intents.last().getStringExtra(Intent.EXTRA_TITLE))
            assertEquals(0, f.provider.writes)
            val source = f.temporary("source".toByteArray())
            assertEquals(OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED)), destination.write(source) { OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED)) })
            assertEquals(0, f.provider.writes); assertArrayEquals(before, f.provider.file.readBytes())
            code(ProblemCode.STALE_REQUEST, destination.write(source) { OperationResult.Completed(Unit) }); destination.close()
            val foreign = completed(f.port.chooseDestination("file", "application/octet-stream", "owner"))
            code(ProblemCode.NOT_FOUND, foreign.write(LocalFile(File(f.root,"credentials.json").path)) { OperationResult.Completed(Unit) })
            assertEquals(0, f.provider.writes); foreign.close()
        } finally { f.close() }
    }
    @Test fun destinationWritesExactBytesOnceAndCloseForbidsLateWrite() = runBlocking {
        val f = FilesTestRig()
        try {
            val bytes = ByteArray(20000) { it.toByte() }; val source = f.temporary(bytes)
            val destination = completed(f.port.chooseDestination("out.bin", "application/octet-stream", "owner"))
            assertEquals(OperationResult.Completed(Unit), destination.write(source) { OperationResult.Completed(Unit) })
            assertArrayEquals(bytes, f.provider.file.readBytes()); assertEquals(listOf("wt"), f.provider.modes)
            assertEquals("wt", withTimeout(5000) { f.provider.closed.receive() })
            code(ProblemCode.STALE_REQUEST, destination.write(source) { OperationResult.Completed(Unit) })
            destination.close(); destination.close(); assertEquals(0, f.provider.deletes)
            val closed = completed(f.port.chooseDestination("closed.bin", "application/octet-stream", "owner")); closed.close()
            code(ProblemCode.STALE_REQUEST, closed.write(source) { OperationResult.Completed(Unit) })
            assertEquals(1, f.provider.writes)
        } finally { f.close() }
    }
    @Test fun chunkGuardRevocationStopsAndMayLeavePartialProviderDocumentWithoutRollbackOrDelete() = runBlocking {
        val f = FilesTestRig()
        try {
            val source = f.temporary(ByteArray(30000) { 7 })
            val destination = completed(f.port.chooseDestination("partial.bin", "application/octet-stream", "owner"))
            var checks = 0
            val result = destination.write(source) { if (++checks < 5) OperationResult.Completed(Unit) else OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED)) }
            code(ProblemCode.UNAUTHORIZED, result)
            assertEquals(8192L, f.provider.file.length()); assertEquals(0, f.provider.deletes)
            assertEquals("wt", withTimeout(5000) { f.provider.closed.receive() })
            destination.close()
        } finally { f.close() }
    }
    @Test fun knownOversizedPrivateInputFailsBeforeProviderOpenAndOwnerRevocationIsRespected() = runBlocking {
        val f = FilesTestRig()
        try {
            val source = withContext(Dispatchers.IO) { completed(f.port.createTemporaryFile("large", "application/octet-stream")) }
            RandomAccessFile(source.path,"rw").use { it.setLength(NativeFileRules.OUTPUT_LIMIT+1) }
            val destination = completed(f.port.chooseDestination("out", "application/octet-stream", "owner"))
            code(ProblemCode.LIMIT_EXCEEDED, destination.write(source) { OperationResult.Completed(Unit) })
            assertEquals(0, f.provider.writes)
            val next = completed(f.port.chooseDestination("out", "application/octet-stream", "owner"))
            f.active.set(false)
            assertEquals(OperationResult.Cancelled, next.write(source) { OperationResult.Completed(Unit) })
            assertEquals(0, f.provider.writes); destination.close(); next.close()
        } finally { f.close() }
    }
    @Test fun rootAndCallerCancellationDismissOnlyOwnedRequestAndNoPickerResultIsReplayed() = runBlocking {
        for (root in listOf(false,true)) {
            val f = FilesTestRig()
            try {
                val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
                f.requester={_,_,_->entered.complete(Unit); withContext(NonCancellable){release.await()};OperationResult.Completed(f.provider.uri)}
                val request=async{ f.port.pick(PickedFileKind.AUDIO,"owner") };withTimeout(5000){entered.await()}
                if(root) f.scope.cancel() else request.cancel()
                release.complete(Unit)
                if(root) assertEquals(OperationResult.Cancelled,withTimeout(5000){request.await()}) else withTimeout(5000){request.join()}
                assertEquals(0,f.provider.reads);assertFalse(File(f.temp.toString()).exists())
            } finally { f.close() }
        }
    }
    @Test fun cancellationAfterCopyBeforeCallerDispatchDiscardsUndeliveredTempWithoutMainIo() = runBlocking {
        val f=FilesTestRig();val dispatcher=PausedDispatcher();val failures=mutableListOf<Throwable>()
        val caller=CoroutineScope(SupervisorJob()+dispatcher+CoroutineExceptionHandler{_,failure->failures+=failure})
        try {
            val request=caller.async{f.port.pick(PickedFileKind.IMAGE,"owner")}
            dispatcher.next().run()
            val delivery=dispatcher.next()
            val path=withContext(Dispatchers.IO){f.fs.list(f.temp).single()}
            request.cancel()
            withContext(Dispatchers.Main){delivery.run()}
            while(!request.isCompleted)dispatcher.next().run()
            assertEquals(path,withTimeout(5000){f.deleted.receive()})
            assertFalse(File(path.toString()).exists());assertTrue(failures.isEmpty())
        } finally {caller.cancel();f.close()}
    }
    @Test fun destinationClosedDuringOpenCannotWriteChunksOrBecomeReusable() = runBlocking {
        val f=FilesTestRig()
        try {
            val source=f.temporary(ByteArray(16000){3})
            val destination=completed(f.port.chooseDestination("closed.bin","application/octet-stream","owner"))
            f.provider.onOpen={mode->if(mode=="wt")destination.close()}
            assertEquals(OperationResult.Cancelled,destination.write(source){OperationResult.Completed(Unit)})
            assertEquals(0L,f.provider.file.length())
            assertEquals("wt",withTimeout(5000){f.provider.closed.receive()})
            code(ProblemCode.STALE_REQUEST,destination.write(source){OperationResult.Completed(Unit)})
            assertEquals(0,f.provider.deletes)
        } finally {f.close()}
    }
    @Test fun physicalUnlinkFailureDoesNotReviveHandleOrKillRemainingCleanup() = runBlocking {
        val f=FilesTestRig()
        try {
            val first=f.temporary("first".toByteArray());f.failDelete=first.path.toPath()
            withContext(Dispatchers.Main){f.port.discard(first)}
            withContext(Dispatchers.IO){code(ProblemCode.NOT_FOUND,f.port.source(first))}
            val second=f.temporary("second".toByteArray());f.port.discard(second)
            assertEquals(second.path,withTimeout(5000){f.deleted.receive()}.toString())
            assertTrue(File(first.path).exists()) // Physical failure is not reported as rollback/deletion success.
        } finally {f.close()}
    }
    @Test fun providerOpenFailureNeverReportsCompletedAndKeepsPrivateSource() = runBlocking {
        val f=FilesTestRig()
        try {
            val source=f.temporary("kept".toByteArray())
            val destination=completed(f.port.chooseDestination("fail.bin","application/octet-stream","owner"))
            f.provider.onOpen={throw java.io.FileNotFoundException("controlled provider failure")}
            code(ProblemCode.NOT_FOUND,destination.write(source){OperationResult.Completed(Unit)})
            assertEquals("kept",File(source.path).readText());assertEquals(0,f.provider.deletes)
            destination.close()
        } finally {f.close()}
    }
}
