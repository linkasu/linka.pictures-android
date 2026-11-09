@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package su.linka.pictures.platform.capabilities

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okio.*
import okio.ByteString.Companion.toByteString
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.Foundation.NSProcessInfo
import platform.ImageIO.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import kotlin.test.*

class IosImagePreparationPortTest {
    private val files = MemoryFiles()
    private var inspectionCalls = 0
    private var rejectInspection = false
    private var cancelInspection = false
    private var onInspection: (() -> Unit)? = null
    private val inspection = object : MediaInspectionPort {
        override suspend fun image(file: LocalFile): OperationResult<ImageInfo> {
            inspectionCalls++
            onInspection?.invoke()
            if (cancelInspection) return OperationResult.Cancelled
            if (rejectInspection) return failure(ProblemCode.UNSUPPORTED_MEDIA)
            return OperationResult.Completed(if (file.path == "source") ImageInfo(1, 1, "image/jpeg")
                else decode(files.bytes.getValue(file.path)))
        }
        override suspend fun audio(file: LocalFile): OperationResult<AudioInfo> = error("Not audio")
    }
    private val port = IosImagePreparationPort(files, inspection)

    @Test fun runtimeEvidence() {
        println("P07-I runtime: ${NSProcessInfo.processInfo.operatingSystemVersionString}; " +
            "UDID=${NSProcessInfo.processInfo.environment["SIMULATOR_UDID"]}")
    }

    @Test fun pngIsDecodedByImageIOAndContainsVisibleGlyphs() = runTest {
        val result = completed(port.imageFromText(TextImageRequest("LINKa")))
        val info = decode(files.bytes.getValue(result.path), requireInk = true)
        assertEquals(1024, info.width)
        assertTrue(info.height in 65..4096)
        assertEquals(1, inspectionCalls)
        assertEquals(1, files.sinkCloses)
        assertTrue(files.bytes.getValue(result.path).size > 1000)
    }

    @Test fun nativeWrappingIncreasesHeight() = runTest {
        val short = completed(port.imageFromText(TextImageRequest("hello")))
        val wrapped = completed(port.imageFromText(TextImageRequest("hello ".repeat(30))))
        assertTrue(decode(files.bytes.getValue(wrapped.path)).height > decode(files.bytes.getValue(short.path)).height)
    }

    @Test fun unicodeAndWhitespaceAreAccepted() = runTest {
        for (text in listOf("Привет 👩🏽‍🚀 世界", "  A\nB  ", "e\u0301", "   ")) {
            completed(port.imageFromText(TextImageRequest(text)))
        }
    }

    @Test fun invalidUtf16AndNulAreRejectedBeforeAllocation() = runTest {
        for (text in listOf("", "\u0000", "\ud800", "\udc00", "\ud800x")) {
            assertProblem(ProblemCode.INVALID_DATA, port.imageFromText(TextImageRequest(text)))
        }
        assertTrue(files.bytes.isEmpty())
    }

    @Test fun colorsAreUnsignedArgbOnly() = runTest {
        assertProblem(ProblemCode.INVALID_DATA, port.imageFromText(TextImageRequest("a", -1)))
        assertProblem(ProblemCode.INVALID_DATA, port.imageFromText(TextImageRequest("a", backgroundArgb = 0x100000000)))
        completed(port.imageFromText(TextImageRequest("a", 0, 0xffffffff)))
    }

    @Test fun textAndCanvasLimitsNeverTruncate() = runTest {
        assertProblem(ProblemCode.LIMIT_EXCEEDED, port.imageFromText(TextImageRequest("a".repeat(4097))))
        assertProblem(ProblemCode.LIMIT_EXCEEDED, port.imageFromText(TextImageRequest("a\n".repeat(2048))))
        assertTrue(files.bytes.isEmpty())
    }

    @Test fun exactCopyPreservesSourceBytesAndClosesStreams() = runTest {
        val original = ByteArray(32771) { (it * 31).toByte() }
        files.bytes["source"] = original.copyOf()
        val result = completed(port.prepareImage(LocalFile("source", "original.jpg", "image/jpeg")))
        assertContentEquals(original, files.bytes.getValue("source"))
        assertContentEquals(original, files.bytes.getValue(result.path))
        assertEquals(2, files.sourceCloses)
        assertEquals(1, files.sinkCloses)
    }

    @Test fun realPngCopyPreservesSourceHashAndDecodes() = runTest {
        val generated = completed(port.imageFromText(TextImageRequest("source PNG 👋")))
        val original = files.bytes.getValue(generated.path)
        files.bytes["source"] = original.copyOf()
        val hash = original.toByteString().sha256()
        val copy = completed(port.prepareImage(LocalFile("source", "image.png", "image/png")))
        assertEquals(hash, files.bytes.getValue("source").toByteString().sha256())
        assertEquals(hash, files.bytes.getValue(copy.path).toByteString().sha256())
        assertEquals(decode(original), decode(files.bytes.getValue(copy.path)))
    }

    @Test fun maximumUtf16LengthIsAcceptedWhenCanvasFits() = runTest {
        completed(port.imageFromText(TextImageRequest("\u200b".repeat(4096))))
    }

    @Test fun rejectsInspectionBeforeOpeningSource() = runTest {
        rejectInspection = true
        assertProblem(ProblemCode.UNSUPPORTED_MEDIA, port.prepareImage(LocalFile("source")))
        assertEquals(0, files.sourceCloses)
    }

    @Test fun nonemptyAndAliasedAllocationsAreNeverTruncatedOrDeleted() = runTest {
        files.bytes["source"] = byteArrayOf(1, 2, 3)
        files.allocatedPath = "source"
        assertProblem(ProblemCode.CONFLICT, port.prepareImage(LocalFile("source")))
        files.allocatedPath = "foreign"
        files.bytes["foreign"] = byteArrayOf(4, 5)
        assertProblem(ProblemCode.CONFLICT, port.prepareImage(LocalFile("source")))
        assertContentEquals(byteArrayOf(4, 5), files.bytes["foreign"])
        assertEquals(0, files.sinkCloses)
        assertTrue(files.discarded.isEmpty())
    }

    @Test fun copyLimitCleansOnlyOwnedOutput() = runTest {
        files.bytes["source"] = byteArrayOf(1)
        files.generatedSize = 64L * 1024 * 1024 + 1
        assertProblem(ProblemCode.LIMIT_EXCEEDED, port.prepareImage(LocalFile("source")))
        assertEquals(listOf("temp1"), files.discarded)
        assertContentEquals(byteArrayOf(1), files.bytes["source"])
        assertEquals(2, files.sourceCloses)
        assertEquals(1, files.sinkCloses)
    }

    @Test fun exactByteLimitIsAccepted() = runTest {
        files.bytes["source"] = byteArrayOf(1)
        files.generatedSize = 64L * 1024 * 1024
        val result = completed(port.prepareImage(LocalFile("source")))
        assertEquals(64 * 1024 * 1024, files.bytes.getValue(result.path).size)
        assertTrue(files.discarded.isEmpty())
    }

    @Test fun readFailureClosesStreamsAndCleansOutput() = runTest {
        files.bytes["source"] = byteArrayOf(1)
        files.onRead = { throw IOException("Test read failure") }
        assertProblem(ProblemCode.IO_FAILURE, port.prepareImage(LocalFile("source")))
        assertEquals(listOf("temp1"), files.discarded)
        assertEquals(2, files.sourceCloses)
        assertEquals(1, files.sinkCloses)
    }

    @Test fun inspectionFailureCleansGeneratedPng() = runTest {
        rejectInspection = true
        assertProblem(ProblemCode.UNSUPPORTED_MEDIA, port.imageFromText(TextImageRequest("hello")))
        assertEquals(listOf("temp1"), files.discarded)
    }

    @Test fun cancelledLateGenerationDoesNotPublishFile() = runTest {
        val job = Job()
        onInspection = { job.cancel() }
        assertFailsWith<CancellationException> {
            withContext(job) { port.imageFromText(TextImageRequest("late")) }
        }
        assertEquals(listOf("temp1"), files.discarded)
        assertTrue(files.bytes.isEmpty())
    }

    @Test fun cancellationDuringCopyClosesAndCleansOutput() = runTest {
        files.bytes["source"] = ByteArray(32768)
        val job = Job()
        files.onRead = { job.cancel() }
        assertFailsWith<CancellationException> { withContext(job) { port.prepareImage(LocalFile("source")) } }
        assertEquals(listOf("temp1"), files.discarded)
        assertEquals(2, files.sourceCloses)
        assertEquals(1, files.sinkCloses)
    }

    @Test fun discardFailureDoesNotReplaceKnownProblem() = runTest {
        files.failDiscard = true
        rejectInspection = true
        assertProblem(ProblemCode.UNSUPPORTED_MEDIA, port.imageFromText(TextImageRequest("failure")))
        assertEquals(listOf("temp1"), files.discarded)
    }

    @Test fun discardFailureDoesNotReplaceCancelledResult() = runTest {
        files.failDiscard = true
        cancelInspection = true
        assertEquals(OperationResult.Cancelled, port.imageFromText(TextImageRequest("cancelled")))
        assertEquals(listOf("temp1"), files.discarded)
    }

    @Test fun discardFailureDoesNotReplaceCancellationException() = runTest {
        files.failDiscard = true
        files.bytes["source"] = byteArrayOf(1)
        val primary = CancellationException("primary cancellation")
        files.onRead = { throw primary }
        val thrown = assertFailsWith<CancellationException> { port.prepareImage(LocalFile("source")) }
        assertSame(primary, thrown)
        assertEquals(listOf("temp1"), files.discarded)
        assertEquals(2, files.sourceCloses)
        assertEquals(1, files.sinkCloses)
    }

    @Test fun discardFailureDoesNotPublishCancelledLateResult() = runTest {
        files.failDiscard = true
        val job = Job()
        onInspection = { job.cancel() }
        assertFailsWith<CancellationException> {
            withContext(job) { port.imageFromText(TextImageRequest("late")) }
        }
        assertEquals(listOf("temp1"), files.discarded)
        assertEquals(1, files.sinkCloses)
    }

    private fun completed(result: OperationResult<TemporaryFile>) = assertIs<OperationResult.Completed<TemporaryFile>>(result).value
    private fun assertProblem(code: ProblemCode, result: OperationResult<*>) {
        assertEquals(code, assertIs<OperationResult.Failed>(result).problem.code)
    }

    // Real ImageIO decode is independent of the injected inspection boundary.
    private fun decode(bytes: ByteArray, requireInk: Boolean = false): ImageInfo = bytes.usePinned { pinned ->
        val data = CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong())!!
        try {
            val source = CGImageSourceCreateWithData(data, null)!!
            try {
                assertEquals(1uL, CGImageSourceGetCount(source))
                val image = CGImageSourceCreateImageAtIndex(source, 0u, null)!!
                try {
                    val pixels = CGDataProviderCopyData(CGImageGetDataProvider(image))!!
                    try {
                        val buffer = CFDataGetBytePtr(pixels)!!
                        val size = CFDataGetLength(pixels).toInt()
                        assertTrue(size > 0)
                        if (requireInk) assertTrue((0 until size).any { buffer[it] != 255.toUByte() })
                    } finally { CFRelease(pixels) }
                    ImageInfo(CGImageGetWidth(image).toInt(), CGImageGetHeight(image).toInt(), "image/png")
                } finally { CGImageRelease(image) }
            } finally { CFRelease(source) }
        } finally { CFRelease(data) }
    }

    private class MemoryFiles : PlatformFilesPort {
        val bytes = mutableMapOf<String, ByteArray>()
        val discarded = mutableListOf<String>()
        var sourceCloses = 0
        var sinkCloses = 0
        var allocatedPath: String? = null
        var generatedSize: Long? = null
        var onRead: (() -> Unit)? = null
        var failDiscard = false
        private var serial = 0
        override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> {
            val path = allocatedPath ?: "temp${++serial}"
            bytes.getOrPut(path) { ByteArray(0) }
            return OperationResult.Completed(TemporaryFile(path, suggestedName, mediaType))
        }
        override fun source(file: LocalFile): OperationResult<Source> {
            val content = bytes[file.path] ?: return failure(ProblemCode.NOT_FOUND)
            var remaining = if (file.path == "source") generatedSize else null
            val input = Buffer().write(content)
            return OperationResult.Completed(object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (file.path == "source") onRead?.invoke()
                    val size = remaining ?: return input.read(sink, byteCount)
                    if (size == 0L) return -1
                    val count = minOf(size, byteCount)
                    sink.write(ByteArray(count.toInt()))
                    remaining = size - count
                    return count
                }
                override fun timeout() = Timeout.NONE
                override fun close() { sourceCloses++; input.close() }
            })
        }
        override fun sink(file: TemporaryFile): OperationResult<Sink> {
            val output = Buffer()
            return OperationResult.Completed(object : Sink {
                override fun write(source: Buffer, byteCount: Long) { output.write(source, byteCount) }
                override fun flush() = Unit
                override fun timeout() = Timeout.NONE
                override fun close() { sinkCloses++; bytes[file.path] = output.readByteArray() }
            })
        }
        override fun discard(file: TemporaryFile) {
            discarded += file.path
            if (failDiscard) throw IOException("Test discard failure")
            bytes.remove(file.path)
        }
        override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> = error("No picker")
        override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> = error("No destination")
    }

    companion object {
        private fun failure(code: ProblemCode) = OperationResult.Failed(Problem(code))
    }
}
