@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.audio

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.platform.capabilities.IosMediaInspectionPort
import su.linka.pictures.platform.files.IosPlatformFilesPort

class IosAudioDecoderIntegrationTest {
    private class Fixture {
        val fs = FileSystem.SYSTEM
        val root = (NSTemporaryDirectory() + "linka-decoder-test-" + NSUUID().UUIDString).toPath()
        val temp = root / "temp"
        val media = root / "media"
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val files = IosPlatformFilesPort(temp, media, fs, { null }, { false }, scope)
        init { fs.createDirectories(temp); fs.createDirectories(media) }
        fun file(bytes: ByteArray): TemporaryFile {
            val file = completed(files.createTemporaryFile("wrong-extension.bin", "application/octet-stream"))
            completed(files.sink(file)).buffer().use { it.write(bytes) }
            return file
        }
        fun bytes(file: LocalFile): ByteArray = completed(files.source(file)).buffer().use { it.readByteArray() }
        fun sha(file: LocalFile): ByteString {
            val source = HashingSource.sha256(completed(files.source(file)))
            source.buffer().use { it.readAll(blackholeSink()) }
            return source.hash
        }
        suspend fun close() {
            job.children.toList().joinAll()
            job.cancel()
            fs.deleteRecursively(root)
        }
    }

    private suspend fun owned(block: suspend Fixture.() -> Unit) = withContext(Dispatchers.IO) {
        val fixture = Fixture()
        try { fixture.block() } finally { withContext(NonCancellable) { fixture.close() } }
    }

    @Test fun realMainCInteropAndOwnedBrokerProduceFinalizedWaveWithoutChangingOriginal() = runTest { owned {
        val source = file(wave(16))
        val original = sha(source)
        val decoder = IosAudioDecoder(files)
        val inspector = IosMediaInspectionPort(decoder::inspect)
        val info = completed(inspector.audio(source))
        assertEquals(AudioInfo(100, "audio/wav"), info)
        val converted = completed(decoder.decodeToWave(source))
        assertNotEquals(source.path, converted.path)
        assertTrue(converted.path.endsWith(".wav"))
        val output = Buffer().write(bytes(converted))
        assertEquals("RIFF", output.readUtf8(4))
        assertEquals(6436, output.readIntLe())
        assertEquals("WAVEfmt ", output.readUtf8(8))
        assertEquals(16, output.readIntLe())
        assertEquals(1, output.readShortLe().toInt())
        assertEquals(2, output.readShortLe().toInt())
        assertEquals(16000, output.readIntLe())
        assertEquals(64000, output.readIntLe())
        assertEquals(4, output.readShortLe().toInt())
        assertEquals(16, output.readShortLe().toInt())
        assertEquals("data", output.readUtf8(4))
        assertEquals(6400, output.readIntLe())
        assertEquals(6400L, output.size)
        assertEquals(info, completed(decoder.inspect(converted)))
        assertEquals(original, sha(source))
        files.discard(converted)
        assertIs<OperationResult.Failed>(files.source(converted))
    } }

    @Test fun nativeEarlyFailuresKeepTheirStatusThroughKotlinInsteadOfBecomingInvalidMime() = runTest { owned {
        val decoder = IosAudioDecoder(files)
        val unsupported = file(wave(8))
        val before = sha(unsupported)
        assertEquals(ProblemCode.UNSUPPORTED_MEDIA, failed(decoder.inspect(unsupported)))
        assertEquals(ProblemCode.IO_FAILURE, failed(decoder.inspect(LocalFile((temp / "missing").toString()))))
        val large = file(byteArrayOf())
        fs.openReadWrite(large.path.toPath(), mustExist = true).use { it.resize(64L * 1024 * 1024 + 1) }
        assertEquals(ProblemCode.LIMIT_EXCEEDED, failed(decoder.inspect(large)))
        assertEquals(before, sha(unsupported))
    } }

    @Test fun nonemptyFactoryAliasIsNotClaimedOrDiscarded() = runTest { owned {
        val source = file(wave(16))
        val before = sha(source)
        var discarded = 0
        val underlying = files
        val aliased = object : PlatformFilesPort by underlying {
            override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> = OperationResult.Completed(source)
            override fun discard(file: TemporaryFile) { discarded++; underlying.discard(file) }
        }
        assertEquals(ProblemCode.CONFLICT, failed(IosAudioDecoder(aliased).decodeToWave(source)))
        assertEquals(0, discarded)
        assertEquals(before, sha(source))
    } }

    @Test fun cancellationInsideFactoryNeverPublishesOrKeepsTheClaimedOutput() = runTest { owned {
        val source = file(wave(16))
        val before = sha(source)
        var created: TemporaryFile? = null
        lateinit var task: Deferred<OperationResult<TemporaryFile>>
        val underlying = files
        val cancelling = object : PlatformFilesPort by underlying {
            override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> =
                underlying.createTemporaryFile(suggestedName, mediaType).also {
                    if (it is OperationResult.Completed) created = it.value
                    task.cancel()
                }
        }
        coroutineScope {
            task = async(start = CoroutineStart.LAZY) { IosAudioDecoder(cancelling).decodeToWave(source) }
            task.start()
            assertFailsWith<CancellationException> { task.await() }
        }
        val output = assertNotNull(created)
        assertIs<OperationResult.Failed>(files.source(output))
        assertEquals(before, sha(source))
    } }

    companion object {
        private fun <T> completed(value: OperationResult<T>): T = assertIs<OperationResult.Completed<T>>(value).value
        private fun failed(value: OperationResult<*>): ProblemCode = assertIs<OperationResult.Failed>(value).problem.code
        private fun wave(bits: Int): ByteArray {
            val count = 1600
            val sampleBytes = bits / 8
            return Buffer().apply {
                writeUtf8("RIFF"); writeIntLe(36 + count * sampleBytes); writeUtf8("WAVEfmt ")
                writeIntLe(16); writeShortLe(1); writeShortLe(1); writeIntLe(16000)
                writeIntLe(16000 * sampleBytes); writeShortLe(sampleBytes); writeShortLe(bits)
                writeUtf8("data"); writeIntLe(count * sampleBytes)
                repeat(count) { index ->
                    if (bits == 16) writeShortLe(if (index % 32 < 16) 1000 else -1000) else writeByte(128)
                }
            }.readByteArray()
        }
    }
}
