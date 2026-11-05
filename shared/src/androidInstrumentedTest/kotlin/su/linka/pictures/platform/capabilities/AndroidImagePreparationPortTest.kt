package su.linka.pictures.platform.capabilities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import okio.sink
import okio.source
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.platform.audio.OwnedAudioTestFiles

class AndroidImagePreparationPortTest {
    private class Fixture : AutoCloseable {
        val files = OwnedAudioTestFiles()
        val real = AndroidMediaInspectionPort()
        var image: suspend (LocalFile) -> OperationResult<ImageInfo> = { real.image(it) }
        var fault: String? = null
        var create: (() -> OperationResult<TemporaryFile>)? = null
        var sourceFactory: ((LocalFile) -> Source)? = null
        var sinkFactory: ((TemporaryFile) -> Sink)? = null
        var afterWrite: (() -> Unit)? = null
        var discardFails = false
        val discardAttempts = mutableListOf<TemporaryFile>()
        var sourceOpened = 0
        var sourceClosed = 0
        var sinkOpened = 0
        var sinkClosed = 0
        private val port = object : PlatformFilesPort by files {
            override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> = create?.invoke() ?: files.createTemporaryFile(suggestedName, mediaType)
            override fun source(file: LocalFile): OperationResult<Source> {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                if (fault == "source") return OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
                val original = sourceFactory?.invoke(file) ?: File(file.path).source()
                sourceOpened++
                return OperationResult.Completed(object : Source {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (fault == "read") throw IOException("controlled read fault")
                        if (fault == "zeroRead") return 0
                        return original.read(sink, byteCount)
                    }
                    override fun timeout() = original.timeout()
                    override fun close() { sourceClosed++; original.close(); if (fault == "sourceClose") throw IOException("controlled source close fault") }
                })
            }
            override fun sink(file: TemporaryFile): OperationResult<Sink> {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                if (fault == "sink") return OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
                val original = sinkFactory?.invoke(file) ?: File(file.path).sink()
                sinkOpened++
                return OperationResult.Completed(object : Sink {
                    override fun write(source: Buffer, byteCount: Long) {
                        if (fault == "write") throw IOException("controlled write fault")
                        original.write(source, byteCount); afterWrite?.invoke()
                    }
                    override fun flush() = original.flush()
                    override fun timeout() = original.timeout()
                    override fun close() { sinkClosed++; original.close(); if (fault == "sinkClose") throw IOException("controlled sink close fault") }
                })
            }
            override fun discard(file: TemporaryFile) {
                discardAttempts += file
                if (discardFails) throw IOException("controlled discard failure")
                files.discard(file)
            }
        }
        val service = AndroidImagePreparationPort(port, object : MediaInspectionPort {
            override suspend fun image(file: LocalFile): OperationResult<ImageInfo> {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                return image.invoke(file)
            }
            override suspend fun audio(file: LocalFile): OperationResult<AudioInfo> = error("Images must never inspect/play audio")
        })
        override fun close() { files.close() }
    }
    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun next(): Runnable = checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Expected actual dispatch, not a timed sleep" }
    }
    private fun completed(result: OperationResult<TemporaryFile>): TemporaryFile {
        assertTrue("Expected real prepared image: $result", result is OperationResult.Completed)
        return (result as OperationResult.Completed).value
    }
    private fun code(expected: ProblemCode, result: OperationResult<TemporaryFile>) {
        assertTrue(result is OperationResult.Failed)
        assertEquals(expected, (result as OperationResult.Failed).problem.code)
    }
    private fun jpegWithExif(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        try { bitmap.eraseColor(0xffaabbcc.toInt()); check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) } finally { bitmap.recycle() }
        val jpeg = output.toByteArray()
        val tiff = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN)
            .put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
            .putShort(1).putShort(0x010e).putShort(2).putInt(4).put("own\u0000".toByteArray()).putInt(0).array()
        val exif = "Exif\u0000\u0000".toByteArray() + tiff
        val size = exif.size + 2
        check(jpeg[0] == (-1).toByte() && jpeg[1] == (-40).toByte() && size < 65536)
        return jpeg.copyOfRange(0, 2) + byteArrayOf(-1, -31, (size ushr 8).toByte(), size.toByte()) + exif + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test fun readOnlyPngAndExifJpegAreCopiedExactlyWithoutReencodingOrSourceMutation() = runBlocking {
        Fixture().use { f ->
            for (bytes in listOf(TinyMediaFixtures.png(), jpegWithExif())) {
                val source = f.files.file(bytes)
                check(File(source.path).setReadOnly())
                val hash = LegacyCodecFixtures.sha256(bytes)
                val result = completed(f.service.prepareImage(source))
                assertNotEquals(source.path, result.path)
                assertArrayEquals(bytes, File(result.path).readBytes())
                assertEquals(hash, LegacyCodecFixtures.sha256(File(source.path).readBytes()))
                assertTrue(f.real.image(result) is OperationResult.Completed)
                assertTrue(f.discardAttempts.isEmpty())
            }
            assertEquals(2, f.sourceOpened); assertEquals(2, f.sourceClosed)
            assertEquals(2, f.sinkOpened); assertEquals(2, f.sinkClosed)
        }
    }

    @Test fun unicodeAndExplicitSpacesWrapWithoutTextChangesShrinkOrEllipsisAndRenderActualPng() = runBlocking {
        Fixture().use { f ->
            val text = "  Привет, мёд е\u0301 👩🏽‍💻 क्ष  \n" + "word ".repeat(20)
            val request = TextImageRequest(text, 0xff112233L, 0xffabcdefL)
            val layout = withContext(Dispatchers.IO) { f.service.textLayout(request) }
            assertEquals(text, layout.text.toString())
            assertEquals(64f, layout.paint.textSize, 0f)
            assertEquals(960, layout.width)
            assertTrue(layout.lineCount > 2)
            assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1))
            repeat(layout.lineCount) { assertEquals(0, layout.getEllipsisCount(it)) }
            val file = completed(f.service.imageFromText(request))
            val bitmap = BitmapFactory.decodeFile(file.path)
            assertNotNull(bitmap)
            try {
                assertEquals(1024, bitmap.width); assertEquals(layout.height + 64, bitmap.height)
                assertEquals(0xffabcdef.toInt(), bitmap.getPixel(0, 0))
                var ink = false
                for (y in 32 until bitmap.height - 32 step 2) for (x in 32 until 992 step 2) {
                    if (bitmap.getPixel(x, y) != 0xffabcdef.toInt()) ink = true
                }
                assertTrue("Canvas must draw actual glyphs, not only a PNG background", ink)
            } finally { bitmap.recycle() }
            assertEquals(1, f.sinkClosed)
        }
    }

    @Test fun emptyAndWhitespaceRequestsRemainExplicitValidImagesAndArgbEndpointsAreAllowed() = runBlocking {
        Fixture().use { f ->
            val empty = completed(f.service.imageFromText(TextImageRequest("", 0, 0xffffffffL)))
            val spaces = "   \n "
            val layout = withContext(Dispatchers.IO) { f.service.textLayout(TextImageRequest(spaces, 0xffffffffL, 0)) }
            assertEquals(spaces, layout.text.toString()); assertEquals(2, layout.lineCount)
            val transparent = completed(f.service.imageFromText(TextImageRequest(spaces, 0xffffffffL, 0)))
            val first = BitmapFactory.decodeFile(empty.path)
            val second = BitmapFactory.decodeFile(transparent.path)
            try {
                assertEquals(1024, first.width); assertTrue(first.height > 64)
                assertEquals(-1, first.getPixel(0, 0)); assertEquals(0, second.getPixel(0, 0))
                assertTrue(second.height > first.height)
            } finally { first.recycle(); second.recycle() }
            assertTrue(f.discardAttempts.isEmpty())
        }
    }

    @Test fun malformedUtf16NulAndOutOfRangeColoursAreRefusedBeforeCreatingMedia() = runBlocking {
        Fixture().use { f ->
            for (text in listOf("\uD800", "\uDC00", "a\u0000b")) code(ProblemCode.INVALID_DATA, f.service.imageFromText(TextImageRequest(text)))
            for (colour in listOf(-1L, 0x100000000L, Long.MAX_VALUE)) {
                code(ProblemCode.INVALID_DATA, f.service.imageFromText(TextImageRequest("text", foregroundArgb = colour)))
                code(ProblemCode.INVALID_DATA, f.service.imageFromText(TextImageRequest("text", backgroundArgb = colour)))
            }
            assertTrue(f.files.created.isEmpty())
        }
    }

    @Test fun textLengthAndComputedHeightLimitsFailWithoutTruncationOrFileCreation() = runBlocking {
        Fixture().use { f ->
            code(ProblemCode.LIMIT_EXCEEDED, f.service.imageFromText(TextImageRequest("x".repeat(4097))))
            code(ProblemCode.LIMIT_EXCEEDED, f.service.imageFromText(TextImageRequest("line\n".repeat(100))))
            assertTrue(f.files.created.isEmpty())
        }
    }

    @Test fun invalidSourceIsNotCopiedOrDeleted() = runBlocking {
        Fixture().use { f ->
            val bytes = byteArrayOf(1, 2, 3)
            val source = f.files.file(bytes)
            code(ProblemCode.INVALID_DATA, f.service.prepareImage(source))
            assertEquals(1, f.files.created.size); assertEquals(0, f.sourceOpened)
            assertTrue(f.discardAttempts.isEmpty()); assertArrayEquals(bytes, File(source.path).readBytes())
        }
    }

    @Test fun nonemptyFactoryResultsAndSourceAliasAreNotOverwrittenOrDiscarded() = runBlocking {
        Fixture().use { f ->
            val source = f.files.file(TinyMediaFixtures.png())
            f.files.initialBytes = byteArrayOf(4, 3, 2, 1)
            code(ProblemCode.CONFLICT, f.service.prepareImage(source))
            assertArrayEquals(f.files.initialBytes, File(f.files.created.last().path).readBytes())
            code(ProblemCode.CONFLICT, f.service.imageFromText(TextImageRequest("text")))
            assertArrayEquals(f.files.initialBytes, File(f.files.created.last().path).readBytes())
            f.create = { OperationResult.Completed(source as TemporaryFile) }
            code(ProblemCode.CONFLICT, f.service.prepareImage(source))
            assertArrayEquals(TinyMediaFixtures.png(), File(source.path).readBytes())
            assertTrue(f.discardAttempts.isEmpty()); assertEquals(0, f.sinkOpened)
        }
    }

    @Test fun sourceSinkReadWriteAndCloseFailuresCloseAllHandlesAndDiscardOnlyOutput() = runBlocking {
        for (fault in listOf("source", "sink", "read", "write", "sourceClose", "sinkClose", "zeroRead")) {
            Fixture().use { f ->
                val bytes = TinyMediaFixtures.png()
                val source = f.files.file(bytes)
                f.fault = fault
                code(ProblemCode.IO_FAILURE, f.service.prepareImage(source))
                assertEquals(f.sourceOpened, f.sourceClosed); assertEquals(f.sinkOpened, f.sinkClosed)
                assertEquals(listOf(f.files.created.last()), f.discardAttempts)
                assertArrayEquals(bytes, File(source.path).readBytes())
            }
        }
    }

    @Test fun streamingCopyBudgetUsesBoundedBuffersAndClosesControlledStreams() = runBlocking {
        Fixture().use { f ->
            val source = f.files.file(TinyMediaFixtures.png())
            f.image = { OperationResult.Completed(ImageInfo(1, 1, "image/png")) }
            f.sourceFactory = { object : Source {
                val zeros = ByteArray(8192)
                var remaining = MediaContainer.MAX_BYTES + 1
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (remaining == 0L) return -1
                    val count = minOf(remaining, byteCount, zeros.size.toLong()).toInt()
                    sink.write(zeros, 0, count); remaining -= count; return count.toLong()
                }
                override fun timeout() = Timeout.NONE
                override fun close() = Unit
            } }
            f.sinkFactory = { object : Sink {
                override fun write(source: Buffer, byteCount: Long) { source.skip(byteCount) }
                override fun timeout() = Timeout.NONE
                override fun flush() = Unit
                override fun close() = Unit
            } }
            code(ProblemCode.LIMIT_EXCEEDED, f.service.prepareImage(source))
            assertEquals(1, f.sourceClosed); assertEquals(1, f.sinkClosed); assertEquals(1, f.discardAttempts.size)
        }
    }

    @Test fun cancellationDuringCopyClosesBothHandlesAndKeepsOriginal() = runBlocking {
        Fixture().use { f ->
            val bytes = TinyMediaFixtures.png()
            val source = f.files.file(bytes)
            val request = async(start = CoroutineStart.LAZY) { f.service.prepareImage(source) }
            f.afterWrite = { request.cancel() }
            request.start(); withTimeout(5000) { request.join() }
            assertTrue(request.isCancelled)
            assertEquals(1, f.sourceClosed); assertEquals(1, f.sinkClosed)
            assertEquals(listOf(f.files.created.last()), f.discardAttempts)
            assertArrayEquals(bytes, File(source.path).readBytes())
        }
    }

    @Test fun lateInspectionResultAfterCancellationDoesNotTransferOwnedTemp() = runBlocking {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.image = { file ->
                val result = f.real.image(file)
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                result
            }
            val request = async { f.service.imageFromText(TextImageRequest("late")) }
            withTimeout(5000) { entered.await() }
            request.cancel(); release.complete(Unit)
            withTimeout(5000) { request.join() }
            assertTrue(request.isCancelled)
            assertEquals(listOf(f.files.created.single()), f.discardAttempts)
            assertFalse(File(f.files.created.single().path).exists())
        }
    }

    @Test fun cancelledCallerDispatchAfterValidatedIoResultStillDiscardsTemp() = runBlocking {
        Fixture().use { f ->
            val dispatcher = PausedDispatcher()
            val failures = mutableListOf<Throwable>()
            val caller = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, failure -> failures += failure })
            try {
                val request = caller.async { f.service.imageFromText(TextImageRequest("validated")) }
                dispatcher.next().run()
                val delivery = dispatcher.next()
                request.cancel(); delivery.run()
                while (!request.isCompleted) dispatcher.next().run()
                assertTrue(request.isCancelled)
                assertEquals(listOf(f.files.created.single()), f.discardAttempts)
                assertTrue(failures.isEmpty())
            } finally { caller.cancel() }
        }
    }

    @Test fun discardFailureDoesNotMaskPrimaryValidationFailureCancelOrUnknown() = runBlocking {
        for (result in listOf(OperationResult.Failed(Problem(ProblemCode.UNSUPPORTED_MEDIA)), OperationResult.Cancelled, OperationResult.UnconfirmedCommit)) {
            Fixture().use { f ->
                f.image = { result }
                f.discardFails = true
                assertEquals(result, f.service.imageFromText(TextImageRequest("reject")))
                assertEquals(listOf(f.files.created.single()), f.discardAttempts)
                assertTrue(File(f.files.created.single().path).isFile) // Physical deletion failed; fixture owner cleans it later.
            }
        }
    }

    @Test fun deliveredImageRemainsCallerOwnedAfterSubsequentCancellationAndPreparation() = runBlocking {
        Fixture().use { f ->
            val request = async { f.service.imageFromText(TextImageRequest("owned")) }
            val first = completed(request.await())
            request.cancel()
            val second = completed(f.service.imageFromText(TextImageRequest("another")))
            assertNotEquals(first.path, second.path)
            assertTrue(File(first.path).isFile); assertTrue(File(second.path).isFile)
            assertTrue(f.discardAttempts.isEmpty())
        }
    }

    @Test fun pngEncodeWriteAndCloseErrorsNeverReturnPartialCompletedFile() = runBlocking {
        for (fault in listOf("write", "sinkClose")) Fixture().use { f ->
            f.fault = fault
            code(ProblemCode.IO_FAILURE, f.service.imageFromText(TextImageRequest("ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(10))))
            assertEquals(1, f.sinkOpened); assertEquals(1, f.sinkClosed)
            assertEquals(listOf(f.files.created.single()), f.discardAttempts)
        }
    }
}
