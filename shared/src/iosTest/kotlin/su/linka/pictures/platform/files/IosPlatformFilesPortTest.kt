@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.files

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import platform.Foundation.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import kotlin.test.*

class IosPlatformFilesPortTest {
    private class Fixture {
        val root = (NSTemporaryDirectory() + "linka-files-test-" + NSUUID().UUIDString).toPath()
        val temp = root / "temp"
        val media = root / "media"
        val provider = root / "provider"
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        var live = true
        var releases = 0
        var providerOpens = 0
        var providerWrites = 0
        var providerBytesAdmitted = 0L
        var onProviderOpen: (() -> Unit)? = null
        var onProviderWrite: (() -> Unit)? = null
        var fakeSize = false
        var refuseDelete = false
        var afterRead: (() -> Unit)? = null
        var afterClose: (() -> Unit)? = null
        val deleted = CompletableDeferred<Unit>()
        val fs = object : ForwardingFileSystem(FileSystem.SYSTEM) {
            override fun sink(file: Path, mustCreate: Boolean): Sink {
                if (file.parent != provider) return super.sink(file, mustCreate)
                providerOpens++
                onProviderOpen?.invoke()
                val output = super.sink(file, mustCreate)
                return object : Sink by output {
                    override fun write(source: Buffer, byteCount: Long) {
                        providerWrites++
                        providerBytesAdmitted += byteCount
                        onProviderWrite?.invoke()
                        output.write(source, byteCount)
                    }
                }
            }
            override fun source(file: Path): Source {
                val input = super.source(file)
                if (file.parent != provider) return input
                return object : ForwardingSource(input) {
                    override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also { afterRead?.invoke() }
                    override fun close() { super.close(); afterClose?.invoke() }
                }
            }
            override fun metadataOrNull(path: Path): FileMetadata? {
                val actual = super.metadataOrNull(path)
                return if (fakeSize && path.parent == provider && actual != null)
                    FileMetadata(isRegularFile = true, size = 0) else actual
            }
            override fun delete(path: Path, mustExist: Boolean) {
                if (path.parent == temp) {
                    assertFalse(NSThread.isMainThread)
                    if (refuseDelete) { deleted.complete(Unit); throw IOException("Test unlink refusal") }
                }
                super.delete(path, mustExist)
                if (path.parent == temp) deleted.complete(Unit)
            }
        }
        val files = IosPlatformFilesPort(temp, media, fs, { null }, { it == "owner" && live }, scope)
        init { fs.createDirectories(temp); fs.createDirectories(media); fs.createDirectories(provider) }
        fun localUrl(path: Path) = NSURL.fileURLWithPath(path.toString())
        fun write(path: Path, bytes: ByteArray) { fs.sink(path, mustCreate = true).buffer().use { it.write(bytes) } }
        fun temporary(bytes: ByteArray = ByteArray(192 * 1024) { (it * 31).toByte() }): TemporaryFile {
            val file = files.createTemporaryFile("source.bin", "application/octet-stream").get()
            files.sink(file).get().buffer().use { it.write(bytes) }
            return file
        }
        fun destination(name: String = "export.bin", owner: String = "owner", current: () -> Boolean = { owner == "owner" && live }) =
            IosExportDestination(owner, localUrl(provider), name, fs, files::source, current, { releases++ })
        fun hash(path: Path): ByteString {
            val hash = HashingSource.sha256(fs.source(path))
            hash.buffer().use { it.readAll(blackholeSink()) }
            return hash.hash
        }
        suspend fun close() {
            job.children.toList().joinAll()
            job.cancel()
            fs.deleteRecursively(root)
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) = withContext(Dispatchers.IO) {
        val f = Fixture()
        try { f.block() } finally { withContext(NonCancellable) { f.close() } }
    }

    @Test fun runtimeEvidence() {
        val env = NSProcessInfo.processInfo.environment
        assertEquals("DD0F9A3F-DC32-4373-BA08-400FECE3A1CC", env["SIMULATOR_UDID"])
        println("P07-files runtime: ${NSProcessInfo.processInfo.operatingSystemVersionString}; UDID=${env["SIMULATOR_UDID"]}")
    }

    @Test fun uniqueMustCreatePathsAndOneSinkOnly() = runTest { fixture {
        val first = files.createTemporaryFile("passive.txt", "text/plain").get()
        val second = files.createTemporaryFile("passive.txt", "text/plain").get()
        assertNotEquals(first.path, second.path)
        assertFalse(first.path.endsWith("passive.txt"))
        files.sink(first).get().use { it.write(Buffer().writeUtf8("original"), 8) }
        problemIs(ProblemCode.CONFLICT, files.sink(first))
        assertEquals("original", fs.source(first.path.toPath()).buffer().use { it.readUtf8() })
    } }

    @Test fun waveReservationHasNativeContainerSuffixWithoutUsingTheSuggestedNameAsPath() = runTest { fixture {
        val file = files.createTemporaryFile("private spoken title.wav", "audio/wav").get()
        assertTrue(file.path.endsWith(".wav"))
        assertFalse(file.path.contains("private spoken title"))
        assertEquals("private spoken title.wav", file.displayName)
        assertEquals(0L, fs.metadata(file.path.toPath()).size)
        assertFalse(files.createTemporaryFile("private spoken title.wav", "application/octet-stream").get().path.endsWith(".wav"))
    } }

    @Test fun foreignTemporaryAndNonemptyOwnedAllocationAreNotTruncated() = runTest { fixture {
        val file = files.createTemporaryFile("a", "text/plain").get()
        val foreign = TemporaryFile(file.path)
        problemIs(ProblemCode.NOT_FOUND, files.source(foreign))
        problemIs(ProblemCode.NOT_FOUND, files.sink(foreign))
        files.discard(foreign)
        assertFailsWith<IOException> { write(file.path.toPath(), byteArrayOf(42)) }
        assertTrue(fs.exists(file.path.toPath()))
    } }

    @Test fun nonemptyOwnedSentinelIsNotTruncated() = runTest { fixture {
        val file = files.createTemporaryFile("a", "text/plain").get()
        FileSystem.SYSTEM.sink(file.path.toPath()).buffer().use { it.writeUtf8("sentinel") }
        problemIs(ProblemCode.CONFLICT, files.sink(file))
        assertEquals("sentinel", fs.source(file.path.toPath()).buffer().use { it.readUtf8() })
    } }

    @Test fun sourceAllowsOnlyOwnedHandlesOrMediaSubtree() = runTest { fixture {
        val asset = media / "immutable.bin"
        write(asset, byteArrayOf(3, 4))
        write(root / "credentials", byteArrayOf(99))
        files.source(LocalFile(asset.toString())).get().use { assertEquals(2L, it.read(Buffer(), 16)) }
        files.discard(TemporaryFile(asset.toString()))
        assertTrue(fs.exists(asset))
        problemIs(ProblemCode.INVALID_DATA, files.source(LocalFile(asset.toString() + "\u0000suffix")))
        problemIs(ProblemCode.NOT_FOUND, files.source(LocalFile((root / "credentials").toString())))
        problemIs(ProblemCode.NOT_FOUND, files.source(LocalFile((media / ".." / "credentials").toString())))
        val link = media / "link"
        fs.createSymlink(link, root / "credentials")
        problemIs(ProblemCode.NOT_FOUND, files.source(LocalFile(link.toString())))
    } }

    @Test fun anyThreadDiscardRevokesImmediatelyAndUnlinksOnWorker() = runTest {
        val f = withContext(Dispatchers.IO) { Fixture() }
        try {
            val file = withContext(Dispatchers.IO) { f.temporary() }
            assertTrue(NSThread.isMainThread)
            f.files.discard(file); f.files.discard(file)
            withContext(Dispatchers.IO) {
                problemIs(ProblemCode.NOT_FOUND, f.files.source(file))
                withTimeout(5000) { f.deleted.await() }
                assertFalse(f.fs.exists(file.path.toPath()))
            }
        } finally { withContext(NonCancellable + Dispatchers.IO) { f.close() } }
    }

    @Test fun coordinatedLocalUrlCopyPreservesSourceSha() = runTest { fixture {
        val original = provider / "source.bin"
        write(original, ByteArray(200123) { it.toByte() })
        val before = hash(original)
        val result = files.copyPickedUrl(localUrl(original), PickedFileKind.IMAGE) { live }.get()
        assertEquals(before, hash(original))
        assertEquals(before, hash(result.path.toPath()))
    } }

    @Test fun metadataPrechecksUseAllFourRealBudgets() = runTest { fixture {
        for (kind in PickedFileKind.entries) {
            val path = provider / kind.name
            fs.openReadWrite(path, mustCreate = true).use { it.resize(IosPlatformFilesPort.budget(kind) + 1) }
            problemIs(ProblemCode.LIMIT_EXCEEDED, files.copyPickedUrl(localUrl(path), kind) { true })
        }
        assertTrue(fs.list(temp).isEmpty())
    } }

    @Test fun actualChunkCountRejectsLyingMetadata() = runTest { fixture {
        val path = provider / "sparse.bin"
        fs.openReadWrite(path, mustCreate = true).use { it.resize(64L * 1024 * 1024 + 1) }
        fakeSize = true
        problemIs(ProblemCode.LIMIT_EXCEEDED, files.copyPickedUrl(localUrl(path), PickedFileKind.AUDIO) { true })
        withTimeout(5000) { deleted.await() }
        assertTrue(fs.list(temp).isEmpty())
        assertEquals(64L * 1024 * 1024 + 1, FileSystem.SYSTEM.metadata(path).size)
    } }

    @Test fun lateCopyRevocationCleansOwnedOutput() = runTest { fixture {
        val path = provider / "source.bin"
        write(path, ByteArray(128))
        afterClose = { live = false }
        assertEquals(OperationResult.Cancelled, files.copyPickedUrl(localUrl(path), PickedFileKind.LINKA) { live })
        withTimeout(5000) { deleted.await() }
        assertTrue(fs.list(temp).isEmpty())
        assertTrue(fs.exists(path))
    } }

    @Test fun copyCancellationClosesAndUnlinksOnlyOwnedOutput() = runTest { fixture {
        val path = provider / "source.bin"
        write(path, ByteArray(100000) { it.toByte() })
        val before = hash(path)
        val job = Job()
        afterRead = { job.cancel() }
        assertFailsWith<CancellationException> {
            withContext(job) { files.copyPickedUrl(localUrl(path), PickedFileKind.IMAGE) { true } }
        }
        withTimeout(5000) { deleted.await() }
        afterRead = null
        assertTrue(fs.list(temp).isEmpty())
        assertEquals(before, hash(path))
    } }

    @Test fun cancelledCleanupScopeStillRevokesHandleWithoutTouchingMedia() = runTest { fixture {
        val file = temporary()
        val immutable = media / "asset"
        write(immutable, byteArrayOf(9))
        job.cancel()
        files.discard(file)
        problemIs(ProblemCode.NOT_FOUND, files.source(file))
        assertTrue(fs.exists(immutable))
    } }

    @Test fun unlinkRefusalLeavesCacheButDoesNotRestoreOwnershipOrThrow() = runTest { fixture {
        val file = temporary()
        refuseDelete = true
        files.discard(file)
        withTimeout(5000) { deleted.await() }
        problemIs(ProblemCode.NOT_FOUND, files.source(file))
        assertTrue(fs.exists(file.path.toPath()))
        refuseDelete = false
    } }

    @Test fun filenameAndMimeValidationNeverRenames() {
        for (name in listOf("", ".", "..", "../x", "x/y", "x\\y", "a\u0000", "\ud800")) assertFalse(IosPlatformFilesPort.validLeaf(name))
        assertTrue(IosPlatformFilesPort.validLeaf("  exact name.txt  "))
        for (mime in listOf("", "text", "text/plain; charset=utf8", "text/\u0000", " text/plain")) assertFalse(IosPlatformFilesPort.validMime(mime))
        assertTrue(IosPlatformFilesPort.validMime("application/zip"))
    }

    @Test fun destinationGuardRunsBeforeExternalOpen() = runTest { fixture {
        val destination = destination()
        val file = temporary()
        problemIs(ProblemCode.UNAUTHORIZED, destination.write(file) { problem(ProblemCode.UNAUTHORIZED) })
        assertEquals(0, providerOpens)
        assertTrue(fs.list(provider).isEmpty())
        assertEquals(1, releases)
        problemIs(ProblemCode.CONFLICT, destination.write(file) { OperationResult.Completed(Unit) })
    } }

    @Test fun destinationOneUseCompletesOnlyAfterFullCopy() = runTest { fixture {
        val file = temporary()
        val before = hash(file.path.toPath())
        val destination = destination(" exact.bin ")
        assertEquals(OperationResult.Completed(Unit), destination.write(file) { OperationResult.Completed(Unit) })
        assertEquals(before, hash(provider / " exact.bin "))
        assertEquals(before, hash(file.path.toPath()))
        problemIs(ProblemCode.CONFLICT, destination.write(file) { OperationResult.Completed(Unit) })
        destination.close(); destination.close()
        assertEquals(1, releases)
    } }

    @Test fun existingProviderTargetIsNeverOverwrittenOrDeleted() = runTest { fixture {
        val path = provider / "export.bin"
        write(path, byteArrayOf(42))
        val hash = hash(path)
        problemIs(ProblemCode.CONFLICT, destination().write(temporary()) { OperationResult.Completed(Unit) })
        assertEquals(hash, hash(path))
    } }

    @Test fun closedAndWrongOwnerDestinationsNeverOpen() = runTest { fixture {
        val file = temporary()
        val closed = destination()
        closed.close(); closed.close()
        assertEquals(OperationResult.Cancelled, closed.write(file) { OperationResult.Completed(Unit) })
        assertEquals(OperationResult.Cancelled, destination(owner = "another-owner").write(file) { OperationResult.Completed(Unit) })
        assertEquals(0, providerOpens)
    } }

    @Test fun closeInsideGuardCannotRaceIntoExternalOpen() = runTest { fixture {
        val file = temporary()
        val destination = destination()
        assertEquals(OperationResult.Cancelled, destination.write(file) {
            destination.close(); destination.close()
            assertEquals(0, releases)
            OperationResult.Completed(Unit)
        })
        assertEquals(0, providerOpens)
        assertEquals(1, releases)
    } }

    @Test fun revocationBetweenChunksLeavesPartialWithoutProviderCleanup() = runTest { fixture {
        val file = temporary()
        var checks = 0
        val result = destination().write(file) {
            checks++
            if (checks == 5) live = false
            OperationResult.Completed(Unit)
        }
        assertEquals(OperationResult.Cancelled, result)
        val target = provider / "export.bin"
        assertTrue(fs.exists(target))
        assertTrue(fs.metadata(target).size!! < fs.metadata(file.path.toPath()).size!!)
        assertEquals(1, releases)
    } }

    @Test fun cancellationBeforeOpenAndDuringChunksDoesNotPublishSuccess() = runTest { fixture {
        val file = temporary()
        val job = Job()
        val destination = destination()
        assertFailsWith<CancellationException> {
            withContext(job) { destination.write(file) { job.cancel(); OperationResult.Completed(Unit) } }
        }
        assertEquals(0, providerOpens)
        assertEquals(1, releases)
    } }

    @Test fun cancellationAfterOpenLeavesPartialWithoutRollback() = runTest { fixture {
        val file = temporary()
        val before = hash(file.path.toPath())
        val job = Job()
        var checks = 0
        val destination = destination()
        assertFailsWith<CancellationException> {
            withContext(job) { destination.write(file) {
                if (++checks == 5) job.cancel()
                OperationResult.Completed(Unit)
            } }
        }
        val path = provider / "export.bin"
        assertTrue(fs.exists(path))
        assertTrue(fs.metadata(path).size!! < fs.metadata(file.path.toPath()).size!!)
        assertEquals(before, hash(file.path.toPath()))
        assertEquals(1, releases)
    } }

    @Test fun closeDoesNotWaitForAdmittedProviderOpen() = runTest { closeWhileProviderBlocked(blockOpen = true) }

    @Test fun closeDoesNotWaitForAdmittedProviderWrite() = runTest { closeWhileProviderBlocked(blockOpen = false) }

    @Test fun releaseFailurePreservesDeniedAndCancelledResults() = runTest { fixture {
        val file = temporary()
        for (primary in listOf(problem(ProblemCode.UNAUTHORIZED), OperationResult.Cancelled)) {
            val destination = IosExportDestination("owner", localUrl(provider), "export.bin", fs,
                files::source, { live }, { releases++; throw IOException("Test release failure") })
            assertEquals(primary, destination.write(file) { primary })
            destination.close(); destination.close()
        }
        assertEquals(2, releases)
        assertEquals(0, providerOpens)
    } }

    private suspend fun closeWhileProviderBlocked(blockOpen: Boolean) = fixture {
        val entered = CompletableDeferred<Unit>()
        val allowIO = CompletableDeferred<Unit>()
        val holdProvider = {
            entered.complete(Unit)
            runBlocking { withTimeout(10000) { allowIO.await() } }
        }
        if (blockOpen) onProviderOpen = holdProvider else onProviderWrite = holdProvider
        val file = temporary()
        val destination = destination()
        val writer = scope.async { destination.write(file) { OperationResult.Completed(Unit) } }
        var closer: Deferred<Unit>? = null
        var outcome: OperationResult<Unit>? = null
        try {
            withTimeout(5000) { entered.await() }
            // Run synchronous close on a worker: even the broken implementation cannot hang Main.
            val closing = scope.async { destination.close(); destination.close() }
            closer = closing
            val returned = withTimeoutOrNull(3000) { closing.await(); true } ?: false
            assertTrue(returned, "close waited for provider IO")
            assertFalse(allowIO.isCompleted)
            assertEquals(0, releases, "scope must stay alive until streams close")
        } finally {
            allowIO.complete(Unit)
            withContext(NonCancellable) {
                closer?.join()
                outcome = writer.await()
            }
        }
        assertEquals(OperationResult.Cancelled, outcome)
        assertEquals(1, providerOpens)
        assertEquals(if (blockOpen) 0 else 1, providerWrites)
        assertEquals(providerBytesAdmitted, fs.metadata(provider / "export.bin").size)
        if (!blockOpen) assertTrue(providerBytesAdmitted in 1L..65536L)
        assertEquals(1, releases)
        assertEquals(OperationResult.Cancelled, destination.write(file) { OperationResult.Completed(Unit) })
    }

    private fun problemIs(code: ProblemCode, result: OperationResult<*>) = assertEquals(code, assertIs<OperationResult.Failed>(result).problem.code)
}
