@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package su.linka.pictures.platform.capabilities

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.ImageIO.*
import platform.posix.mkfifo
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.platform.files.IosPlatformFilesPort
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class IosMediaInspectionPortTest {
    private class Fixture {
        val root = (NSTemporaryDirectory() + "linka-inspection-" + NSUUID().UUIDString).toPath()
        val media = root / "media"
        val temp = root / "temp"
        val fs = FileSystem.SYSTEM
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val files = IosPlatformFilesPort(temp, media, fs, { null }, { it == "owner" }, scope)
        // Deliberate test-only audio callback, not an FFmpeg or native audio acceptance claim.
        val inspection = IosMediaInspectionPort { OperationResult.Completed(AudioInfo(7, "test/audio")) }
        val preparation = IosImagePreparationPort(files, inspection)
        init { fs.createDirectories(media); fs.createDirectories(temp) }

        fun image(name: String = "opaque", width: Int = 127, height: Int = 63, type: String = "public.png"): LocalFile = autoreleasepool {
            val path = media / name
            assertFalse(fs.exists(path))
            val space = CGColorSpaceCreateDeviceRGB()!!
            try {
                val bitmap = CGBitmapContextCreate(null, width.toULong(), height.toULong(), 8u, 0u,
                    space, CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value)!!
                try {
                    CGContextSetRGBFillColor(bitmap, 0.1, 0.3, 0.8, 1.0)
                    CGContextFillRect(bitmap, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
                    CGContextSetRGBFillColor(bitmap, 1.0, 0.8, 0.1, 1.0)
                    CGContextFillRect(bitmap, CGRectMake(0.0, 0.0, width / 2.0, height / 2.0))
                    val image = CGBitmapContextCreateImage(bitmap)!!
                    try {
                        val url = CFBridgingRetain(NSURL.fileURLWithPath(path.toString()))!!
                        try {
                            val identifier = CFStringCreateWithCString(null, type, kCFStringEncodingUTF8)!!
                            val destination = try { CGImageDestinationCreateWithURL(url.reinterpret(), identifier, 1u, null)!! }
                                finally { CFRelease(identifier) }
                            try {
                                CGImageDestinationAddImage(destination, image, null)
                                assertTrue(CGImageDestinationFinalize(destination))
                            } finally { CFRelease(destination) }
                        } finally { CFRelease(url) }
                    } finally { CGImageRelease(image) }
                } finally { CGContextRelease(bitmap) }
            } finally { CGColorSpaceRelease(space) }
            LocalFile(path.toString(), "misleading.gif", "audio/wav")
        }

        fun bytes(file: LocalFile): ByteArray = fs.source(file.path.toPath()).buffer().use { it.readByteArray() }
        fun write(name: String, bytes: ByteArray): LocalFile {
            val path = media / name
            fs.sink(path, mustCreate = true).buffer().use { it.write(bytes) }
            return LocalFile(path.toString())
        }
        fun hash(file: LocalFile): ByteString {
            val source = HashingSource.sha256(fs.source(file.path.toPath()))
            source.buffer().use { it.readAll(blackholeSink()) }
            return source.hash
        }
        fun corruptPng(): LocalFile {
            val bytes = bytes(image())
            val idat = (8 until bytes.size - 4).first { index ->
                bytes[index] == 73.toByte() && bytes[index + 1] == 68.toByte() &&
                    bytes[index + 2] == 65.toByte() && bytes[index + 3] == 84.toByte()
            }
            // Break the actual compressed stream, not merely a checksum some native codecs ignore.
            bytes.fill(0, idat + 4, minOf(bytes.size, idat + 12))
            return write("broken-pixels", bytes)
        }
        suspend fun close() {
            job.children.toList().joinAll()
            job.cancel()
            fs.deleteRecursively(root)
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) = withContext(Dispatchers.IO) {
        val fixture = Fixture()
        try { fixture.block() } finally { withContext(NonCancellable) { fixture.close() } }
    }

    @Test fun runtimeEvidence() {
        val process = NSProcessInfo.processInfo
        assertEquals("DD0F9A3F-DC32-4373-BA08-400FECE3A1CC", process.environment["SIMULATOR_UDID"])
        println("P07-inspection runtime: ${process.operatingSystemVersionString}; UDID=${process.environment["SIMULATOR_UDID"]}")
    }

    @Test fun nativePngAndJpegUseActualTypeWithoutExtensionOrHint() = runTest { fixture {
        for ((name, type, mime) in listOf(Triple("Привет 👩🏽‍🚀", "public.png", "image/png"),
            Triple("false.png", "public.jpeg", "image/jpeg"))) {
            val file = image(name, type = type)
            val before = hash(file)
            assertEquals(ImageInfo(127, 63, mime), completed(inspection.image(file)))
            assertEquals(before, hash(file))
        }
    } }

    @Test fun actualNativeAxisLimitIsInclusive() = runTest { fixture {
        assertEquals(ImageInfo(8192, 1, "image/png"), completed(inspection.image(image("edge", 8192, 1))))
        problemIs(ProblemCode.LIMIT_EXCEEDED, inspection.image(image("over", 8193, 1)))
    } }

    @Test fun actualNativePixelLimitIsInclusiveAndCheckedBeforeThumbnail() = runTest { fixture {
        assertEquals(ImageInfo(4000, 4000, "image/png"), completed(inspection.image(image("edge", 4000, 4000))))
        problemIs(ProblemCode.LIMIT_EXCEEDED, inspection.image(image("over", 4096, 4096)))
    } }

    @Test fun realCorruptPixelsAreNotAcceptedByMetadataAlone() = runTest { fixture {
        val corrupt = corruptPng()
        val before = hash(corrupt)
        problemIs(ProblemCode.INVALID_DATA, inspection.image(corrupt))
        assertEquals(before, hash(corrupt))
    } }

    @Test fun truncatedNativePngAndJpegAreRejected() = runTest { fixture {
        val results = listOf("public.png", "public.jpeg").map { type ->
            val original = image(type, type = type)
            val bytes = bytes(original)
            val short = write("short-$type", bytes.copyOf(bytes.size / 2))
            inspection.image(short)
        }
        results.forEach { problemIs(ProblemCode.INVALID_DATA, it) }
    } }

    @Test fun missingEmptyAndUnknownInputsAreDistinct() = runTest { fixture {
        problemIs(ProblemCode.NOT_FOUND, inspection.image(LocalFile((media / "missing").toString())))
        problemIs(ProblemCode.INVALID_DATA, inspection.image(write("empty", byteArrayOf())))
        problemIs(ProblemCode.UNSUPPORTED_MEDIA, inspection.image(write("unknown.png", "not an image".encodeToByteArray())))
    } }

    @Test fun nonregularAndSymlinkInputsAreRejectedWithoutFollowing() = runTest { fixture {
        val source = image()
        val before = hash(source)
        problemIs(ProblemCode.INVALID_DATA, inspection.image(LocalFile(media.toString())))
        val link = media / "alias"
        fs.createSymlink(link, source.path.toPath())
        problemIs(ProblemCode.INVALID_DATA, inspection.image(LocalFile(link.toString())))
        val fifo = media / "pipe"
        assertEquals(0, mkfifo(fifo.toString(), 384u))
        problemIs(ProblemCode.INVALID_DATA, inspection.image(LocalFile(fifo.toString())))
        assertEquals(before, hash(source))
    } }

    @Test fun unsafeNativePathsAreRejectedBeforeAnyDecoder() = runTest { fixture {
        val file = image()
        val before = hash(file)
        for (path in listOf("relative.png", "file://${file.path}", file.path + "\u0000suffix", "$media/\ud800", "$media/\udc00")) {
            problemIs(ProblemCode.INVALID_DATA, inspection.image(LocalFile(path)))
        }
        assertEquals(before, hash(file))
    } }

    @Test fun actualFileSizeIsBoundedBeforeDecoder() = runTest { fixture {
        val path = media / "oversized"
        fs.openReadWrite(path, mustCreate = true).use { it.resize(64L * 1024 * 1024 + 1) }
        problemIs(ProblemCode.LIMIT_EXCEEDED, inspection.image(LocalFile(path.toString())))
        fs.openReadWrite(path, mustExist = true).use { it.resize(64L * 1024 * 1024) }
        problemIs(ProblemCode.UNSUPPORTED_MEDIA, inspection.image(LocalFile(path.toString())))
    } }

    @Test fun realInspectionAndFileAdapterPrepareExactPngAndJpegCopies() = runTest { fixture {
        for (type in listOf("public.png", "public.jpeg")) {
            val source = image(type, type = type)
            val before = hash(source)
            val copy = completed(preparation.prepareImage(source))
            assertNotEquals(source.path, copy.path)
            assertEquals(before, hash(copy))
            assertEquals(before, hash(source))
            assertEquals(completed(inspection.image(source)), completed(inspection.image(copy)))
            files.discard(copy)
        }
    } }

    @Test fun realInspectionAndFileAdapterValidateTextPng() = runTest { fixture {
        val png = completed(preparation.imageFromText(TextImageRequest("LINKa\nПривет 👩🏽‍🚀 世界")))
        val before = hash(png)
        val info = completed(inspection.image(png))
        assertEquals(1024, info.width)
        assertTrue(info.height in 65..4096)
        assertEquals("image/png", info.mediaType)
        assertEquals(before, hash(png))
        files.discard(png)
    } }

    @Test fun realInspectionRejectsCorruptionBeforePreparationAllocates() = runTest { fixture {
        val source = corruptPng()
        val before = hash(source)
        problemIs(ProblemCode.INVALID_DATA, preparation.prepareImage(source))
        assertTrue(fs.list(temp).isEmpty())
        assertEquals(before, hash(source))
    } }

    @Test fun cancelledCallerGetsCancellationInsteadOfAFileProblem() = runTest { fixture {
        val job = Job()
        var imageCancelled = false
        assertFailsWith<CancellationException> {
            withContext(job) {
                job.cancel()
                try { inspection.image(LocalFile((media / "missing").toString())) }
                catch (cancelled: CancellationException) { imageCancelled = true; throw cancelled }
            }
        }
        assertTrue(imageCancelled)
    } }

    @Test fun cancellationOnNativeResultDeliveryDoesNotPublishLateSuccess() = runTest { fixture {
        val source = image()
        assertEquals(ImageInfo(127, 63, "image/png"), completed(inspection.image(source)))
        val before = hash(source)
        val job = Job()
        val lock = NSLock()
        var dispatches = 0
        var published = false
        val delivery = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                lock.lock()
                val cancel = try { ++dispatches == 2 } finally { lock.unlock() }
                if (cancel) job.cancel()
                Dispatchers.Default.dispatch(context, block)
            }
        }
        assertFailsWith<CancellationException> {
            withContext(job + delivery) { inspection.image(source); published = true }
        }
        assertFalse(published)
        assertTrue(dispatches >= 2)
        assertEquals(before, hash(source))
    } }

    @Test fun requiredAudioCallbackIsForwardedWithoutClaimingDecode() = runTest {
        val file = LocalFile("/synthetic-test-audio-not-opened")
        for (result in listOf(OperationResult.Completed(AudioInfo(7, "test/audio")),
            problem(ProblemCode.UNSUPPORTED_MEDIA), OperationResult.Cancelled, OperationResult.UnconfirmedCommit)) {
            var calls = 0
            val port = IosMediaInspectionPort { received -> assertSame(file, received); calls++; result }
            assertSame(result, port.audio(file))
            assertEquals(1, calls)
        }
    }

    @Test fun requiredAudioCallbackCancellationIsNotReclassified() = runTest {
        val primary = CancellationException("Test audio cancellation")
        val port = IosMediaInspectionPort { throw primary }
        assertSame(primary, assertFailsWith<CancellationException> { port.audio(LocalFile("/synthetic-audio")) })
    }

    private fun <T> completed(result: OperationResult<T>): T = assertIs<OperationResult.Completed<T>>(result).value
    private fun problem(code: ProblemCode) = OperationResult.Failed(Problem(code))
    private fun problemIs(code: ProblemCode, result: OperationResult<*>) = assertEquals(code, assertIs<OperationResult.Failed>(result).problem.code)

}
