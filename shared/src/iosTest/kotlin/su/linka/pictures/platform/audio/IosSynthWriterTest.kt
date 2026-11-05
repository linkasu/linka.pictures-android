@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.audio

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import platform.AVFAudio.*
import platform.Foundation.*
import platform.posix.link
import platform.posix.symlink
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import kotlin.test.*

class IosSynthWriterTest {
    @Test fun nativeConverterFinalizesPcm16WavOnIos17() = runTest {
        println("P07-A ExtAudioFile: ${NSProcessInfo.processInfo.operatingSystemVersionString}; UDID=${NSProcessInfo.processInfo.environment["SIMULATOR_UDID"]}")
        for (common in listOf(AVAudioPCMFormatFloat32, AVAudioPCMFormatInt32, AVAudioPCMFormatInt16)) {
            for (interleaved in listOf(false, true)) owned { reservation ->
                val format = AVAudioFormat(common, 22050.0, 2u, interleaved)
                val source = pcm(format)
                val writer = IosSynthWriter(reservation.files, reservation.file, format)
                try { repeat(3) { writer.write(ownedPcm(source)) } } finally { writer.close() }
                writer.close() // An already-disposed handle is never disposed a second time.
                val audio = AVAudioFile(NSURL.fileURLWithPath(reservation.file.path), error = null)
                assertEquals(22050.0, audio.fileFormat.sampleRate)
                assertEquals(2u, audio.fileFormat.channelCount)
                assertEquals(AVAudioPCMFormatInt16, audio.fileFormat.commonFormat)
                assertEquals(768L, audio.length)
                val decoded = AVAudioPCMBuffer(audio.processingFormat, 768u)!!
                assertTrue(audio.readIntoBuffer(decoded, null))
                assertEquals(768u, decoded.frameLength)
                assertTrue(decoded.floatChannelData!![0]!![0] > 0.2f)
                assertTrue(decoded.floatChannelData!![1]!![0] < -0.2f)
                assertWaveHeader(reservation.file, 22050, 2, 768)
                assertProblem(ProblemCode.IO_FAILURE) { writer.write(ownedPcm(source)) }
            }
        }
    }

    @Test fun changedFormatAndEmptyFrameNeverWriteOrLeakTheHandle() = runTest {
        owned { reservation ->
            val format = AVAudioFormat(AVAudioPCMFormatInt16, 16000.0, 1u, false)
            val source = pcm(format)
            val writer = IosSynthWriter(reservation.files, reservation.file, format)
            try {
                writer.write(ownedPcm(source))
                val changed = pcm(AVAudioFormat(AVAudioPCMFormatInt16, 22050.0, 1u, false))
                assertProblem(ProblemCode.INVALID_DATA) { writer.write(ownedPcm(changed)) }
                val zero = AVAudioPCMBuffer(format, 1u)!!
                zero.frameLength = 0u
                assertProblem(ProblemCode.INVALID_DATA) { writer.write(zero) }
            } finally { writer.close() }
            assertWaveHeader(reservation.file, 16000, 1, 256)
        }
    }

    @Test fun nanIsRejectedBeforeWritingAndDisposeStillFinalizesPreviousFrames() = runTest {
        owned { reservation ->
            val format = AVAudioFormat(AVAudioPCMFormatFloat32, 16000.0, 1u, false)
            val source = pcm(format)
            val writer = IosSynthWriter(reservation.files, reservation.file, format)
            try {
                writer.write(ownedPcm(source))
                source.floatChannelData!![0]!![0] = Float.POSITIVE_INFINITY
                assertProblem(ProblemCode.INVALID_DATA) { writer.write(ownedPcm(source)) }
            } finally { writer.close() }
            assertWaveHeader(reservation.file, 16000, 1, 256)
        }
    }

    @Test fun cancelledWorkerDisposesBeforeOwnedDiscard() = runTest {
        owned { reservation ->
            val written = CompletableDeferred<Unit>()
            val task = launch(Dispatchers.IO) {
                val format = AVAudioFormat(AVAudioPCMFormatFloat32, 16000.0, 1u, false)
                val writer = IosSynthWriter(reservation.files, reservation.file, format)
                try { writer.write(ownedPcm(pcm(format))); written.complete(Unit); awaitCancellation() }
                finally { writer.close() }
            }
            written.await(); task.cancelAndJoin()
            assertWaveHeader(reservation.file, 16000, 1, 256)
            reservation.files.discard(reservation.file)
            assertFalse(FileSystem.SYSTEM.exists(reservation.file.path.toPath()))
        }
    }

    @Test fun foreignNonemptySymlinkAndHardlinkedReservationsCannotBeErased() = runTest {
        owned { reservation ->
            val format = AVAudioFormat(AVAudioPCMFormatFloat32, 16000.0, 1u, false)
            val foreign = TemporaryFile((reservation.root / "foreign.wav").toString(), "foreign.wav", "audio/wav")
            FileSystem.SYSTEM.write(foreign.path.toPath()) { writeUtf8("foreign") }
            assertProblem(ProblemCode.NOT_FOUND) { IosSynthWriter(reservation.files, foreign, format) }
            assertEquals("foreign", FileSystem.SYSTEM.read(foreign.path.toPath()) { readUtf8() })
            FileSystem.SYSTEM.write(reservation.file.path.toPath()) { writeUtf8("do not truncate") }
            assertProblem(ProblemCode.CONFLICT) { IosSynthWriter(reservation.files, reservation.file, format) }
            assertEquals("do not truncate", FileSystem.SYSTEM.read(reservation.file.path.toPath()) { readUtf8() })
            FileSystem.SYSTEM.delete(reservation.file.path.toPath())
            FileSystem.SYSTEM.write(foreign.path.toPath()) {} // Empty but still foreign.
            assertEquals(0, symlink(foreign.path, reservation.file.path))
            assertProblem(ProblemCode.CONFLICT) { IosSynthWriter(reservation.files, reservation.file, format) }
            FileSystem.SYSTEM.delete(reservation.file.path.toPath())
            assertEquals(0, link(foreign.path, reservation.file.path))
            assertProblem(ProblemCode.CONFLICT) { IosSynthWriter(reservation.files, reservation.file, format) }
            assertEquals(0L, FileSystem.SYSTEM.metadata(foreign.path.toPath()).size)
        }
    }

    @Test fun timeBudgetFailsBeforeExtraFramesReachFile() = runTest {
        owned { reservation ->
            val format = AVAudioFormat(AVAudioPCMFormatInt16, 8000.0, 1u, false)
            val buffer = ownedPcm(pcm(format, 16384u))
            val writer = IosSynthWriter(reservation.files, reservation.file, format)
            val accepted = 600 * 8000 / 16384
            try {
                repeat(accepted) { writer.write(buffer) }
                assertProblem(ProblemCode.LIMIT_EXCEEDED) { writer.write(buffer) }
            } finally { writer.close() }
            assertWaveHeader(reservation.file, 8000, 1, accepted * 16384)
        }
    }

    @Test fun byteBudgetFailsBeforeExtraFramesReachFile() = runTest {
        owned { reservation ->
            val format = AVAudioFormat(AVAudioPCMFormatInt16, 192000.0, 2u, false)
            val buffer = ownedPcm(pcm(format, 16384u))
            val writer = IosSynthWriter(reservation.files, reservation.file, format)
            val accepted = ((AUDIO_BYTE_LIMIT - 4096) / (16384 * 2 * 2)).toInt()
            try {
                repeat(accepted) { writer.write(buffer) }
                assertProblem(ProblemCode.LIMIT_EXCEEDED) { writer.write(buffer) }
            } finally { writer.close() }
            assertWaveHeader(reservation.file, 192000, 2, accepted * 16384)
            assertTrue(FileSystem.SYSTEM.metadata(reservation.file.path.toPath()).size!! < AUDIO_BYTE_LIMIT)
        }
    }

    private fun pcm(format: AVAudioFormat, frames: UInt = 256u): AVAudioPCMBuffer {
        val buffer = AVAudioPCMBuffer(format, frames)!!
        buffer.frameLength = frames
        for (channel in 0 until format.channelCount.toInt()) for (frame in 0 until frames.toInt()) {
            val plane = if (format.interleaved) 0 else channel
            val sample = if (format.interleaved) frame * format.channelCount.toInt() + channel else frame
            val sign = if (channel % 2 == 0) 1 else -1
            when (format.commonFormat) {
                AVAudioPCMFormatFloat32 -> buffer.floatChannelData!![plane]!![sample] = sign * 0.25f
                AVAudioPCMFormatInt32 -> buffer.int32ChannelData!![plane]!![sample] = sign * 536870912
                else -> buffer.int16ChannelData!![plane]!![sample] = (sign * 8192).toShort()
            }
        }
        return buffer
    }

    private fun assertWaveHeader(file: TemporaryFile, rate: Int, channels: Int, frames: Int) {
        FileSystem.SYSTEM.source(file.path.toPath()).buffer().use { source ->
            assertEquals("RIFF", source.readUtf8(4))
            val declared = source.readIntLe().toLong() and 0xffffffffL
            assertEquals(FileSystem.SYSTEM.metadata(file.path.toPath()).size, declared + 8)
            assertEquals("WAVE", source.readUtf8(4))
            var formatFound = false
            var dataFound = false
            while (!source.exhausted()) {
                val chunk = source.readUtf8(4)
                val bytes = source.readIntLe().toLong() and 0xffffffffL
                when (chunk) {
                    "fmt " -> {
                        assertTrue(bytes >= 16)
                        val tag = source.readShortLe().toInt() and 0xffff
                        // Multi-channel PCM can use WAVE_FORMAT_EXTENSIBLE.
                        assertTrue(tag == 1 || tag == 0xfffe)
                        assertEquals(channels, source.readShortLe().toInt())
                        assertEquals(rate, source.readIntLe())
                        assertEquals(rate * channels * 2, source.readIntLe())
                        assertEquals(channels * 2, source.readShortLe().toInt())
                        assertEquals(16, source.readShortLe().toInt())
                        source.skip(bytes - 16); formatFound = true
                    }
                    "data" -> {
                        assertEquals(frames.toLong() * channels * 2, bytes)
                        assertTrue(source.readShortLe().toInt() in 8190..8194)
                        if (channels > 1) assertTrue(source.readShortLe().toInt() in -8194..-8190)
                        source.skip(bytes - if (channels > 1) 4 else 2); dataFound = true
                    }
                    else -> source.skip(bytes)
                }
                if (bytes % 2 != 0L) source.skip(1)
            }
            assertTrue(formatFound && dataFound)
        }
    }

    private fun assertProblem(code: ProblemCode, action: () -> Unit) {
        assertEquals(code, assertIs<OperationResult.Failed>(assertFailsWith<IosAudioFailure> { action() }.result).problem.code)
    }

    private suspend fun owned(test: suspend (Reservation) -> Unit) = withContext(Dispatchers.IO) {
        val reservation = Reservation()
        try { test(reservation) } finally { reservation.cleanup() }
    }

    // Base factory is extensionless; this fixture owns its private .wav reservation, not a production override.
    private class Reservation {
        val root = NSTemporaryDirectory().toPath() / "p07-synth-${NSUUID().UUIDString}"
        val file = TemporaryFile((root / "reservation.wav").toString(), "reservation.wav", "audio/wav")
        private var registered = true
        init { FileSystem.SYSTEM.createDirectories(root); FileSystem.SYSTEM.openReadWrite(file.path.toPath(), mustCreate = true).close() }
        val files = object : PlatformFilesPort {
            override fun source(file: LocalFile): OperationResult<Source> =
                if (registered && file === this@Reservation.file) OperationResult.Completed(FileSystem.SYSTEM.source(file.path.toPath()))
                else OperationResult.Failed(Problem(ProblemCode.NOT_FOUND))
            override fun discard(file: TemporaryFile) {
                check(file === this@Reservation.file); registered = false; FileSystem.SYSTEM.delete(file.path.toPath(), mustExist = false)
            }
            override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> = error("Already reserved")
            override fun sink(file: TemporaryFile): OperationResult<Sink> = error("Native writer only")
            override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> = error("No picker")
            override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> = error("No export")
        }
        fun cleanup() {
            FileSystem.SYSTEM.list(root).forEach { FileSystem.SYSTEM.delete(it) }
            FileSystem.SYSTEM.delete(root)
        }
    }
}
