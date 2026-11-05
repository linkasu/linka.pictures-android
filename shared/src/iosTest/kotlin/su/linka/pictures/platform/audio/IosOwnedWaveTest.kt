@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.audio

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import platform.AVFAudio.*
import platform.Foundation.*
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.TemporaryFile
import su.linka.pictures.platform.files.IosPlatformFilesPort
import kotlin.test.*

class IosOwnedWaveTest {
    @Test fun ownedFileContainsRealNativeDecodablePcm16Mono16000() = runTest {
        withContext(Dispatchers.IO) {
            val root = NSTemporaryDirectory().toPath() / "p07-audio-${NSUUID().UUIDString}"
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val port = IosPlatformFilesPort(root / "temporary", root / "media", FileSystem.SYSTEM, { null }, { false }, owner)
            FileSystem.SYSTEM.createDirectories(root / "media")
            var file: TemporaryFile? = null
            try {
                val created = assertIs<OperationResult.Completed<TemporaryFile>>(port.createTemporaryFile("fixture.wav", "audio/wav")).value
                file = created
                // Tiny fixture, not a replacement WAV writer for production synthesis.
                val wav = Buffer().writeUtf8("RIFF").writeIntLe(36 + 320).writeUtf8("WAVEfmt ")
                    .writeIntLe(16).writeShortLe(1).writeShortLe(1).writeIntLe(16000)
                    .writeIntLe(32000).writeShortLe(2).writeShortLe(16).writeUtf8("data").writeIntLe(320)
                repeat(160) { wav.writeShortLe(if (it % 2 == 0) 1000 else -1000) }
                val sink = assertIs<OperationResult.Completed<Sink>>(port.sink(created)).value
                sink.use { it.write(wav, wav.size) }
                val audio = AVAudioFile(NSURL.fileURLWithPath(created.path), error = null)
                assertEquals(16000.0, audio.fileFormat.sampleRate)
                assertEquals(1u, audio.fileFormat.channelCount)
                assertEquals(AVAudioPCMFormatInt16, audio.fileFormat.commonFormat)
                assertEquals(160L, audio.length)
                val buffer = AVAudioPCMBuffer(audio.processingFormat, 160u)!!
                assertTrue(audio.readIntoBuffer(buffer, null))
                assertEquals(160u, buffer.frameLength)
                assertTrue(buffer.floatChannelData!![0]!![0] > 0)
                assertTrue(buffer.floatChannelData!![0]!![1] < 0)
                assertEquals(364L, FileSystem.SYSTEM.metadata(created.path.toPath()).size)
            } finally {
                file?.let(port::discard)
                owner.coroutineContext[Job]!!.children.toList().forEach { it.join() }
                owner.cancel()
                FileSystem.SYSTEM.delete(root / "temporary")
                FileSystem.SYSTEM.delete(root / "media")
                FileSystem.SYSTEM.delete(root)
            }
        }
    }
}
