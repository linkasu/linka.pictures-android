@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.audio

import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.TemporaryFile
import su.linka.pictures.application.ports.VoiceStatus
import su.linka.pictures.domain.RequestId
import su.linka.pictures.platform.audio.IosAudioBackend
import su.linka.pictures.platform.audio.IosAudioDecoder
import su.linka.pictures.platform.capabilities.IosMediaInspectionPort
import su.linka.pictures.platform.files.IosPlatformFilesPort

/** App-host only: real Main run loop and native terminal callbacks, never a virtual dispatcher. */
object AudioAppHostChecks {
    // Admission is serialized on real Main, including calls from other threads.
    private var busy = false

    fun clipCompletes(completion: (String?) -> Unit) = check(false, completion)

    fun installedVoiceSpeechCompletes(completion: (String?) -> Unit) = check(true, completion)

    private fun applicationActive() =
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive

    private fun check(speech: Boolean, completion: (String?) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            if (busy) {
                scope.cancel()
                completion("Audio check already running")
                return@launch
            }
            busy = true
            val backendJob = SupervisorJob()
            val filesJob = SupervisorJob()
            val fs = FileSystem.SYSTEM
            val root = (NSTemporaryDirectory() + "linka-audio-host-" + NSUUID().UUIDString).toPath(normalize = true)
            val request = RequestId(NSUUID().UUIDString)
            var ownedRoot = false
            var files: IosPlatformFilesPort? = null
            var source: TemporaryFile? = null
            var backend: IosAudioBackend? = null
            var failure: String? = null
            try {
                withTimeout(20_000) {
                    requireCheck(applicationActive(), "Application is not active")
                    val broker = IosPlatformFilesPort(root / "temporary", root / "media", fs,
                        { null }, { false }, CoroutineScope(filesJob + Dispatchers.IO))
                    files = broker
                    withContext(Dispatchers.IO) {
                        fs.createDirectory(root, mustCreate = true)
                        ownedRoot = true
                        fs.createDirectories(root / "temporary")
                        fs.createDirectories(root / "media")
                    }
                    val decoder = IosAudioDecoder(broker)
                    val inspection = IosMediaInspectionPort(decoder::inspect)
                    val audio = IosAudioBackend(broker, inspection, decoder::decodeToWave,
                        { throw CheckFailure("Unexpected microphone permission request") },
                        ::applicationActive, CoroutineScope(backendJob + Dispatchers.Main.immediate))
                    backend = audio
                    if (speech) {
                        val capability = audio.voices.first { it.status != VoiceStatus.INITIALIZING }
                        requireCheck(capability.status == VoiceStatus.LOCAL_VOICE_READY, "Installed voice is not ready")
                        val voice = capability.voices.firstOrNull()
                            ?: throw CheckFailure("No installed voice")
                        completed(audio.speak("Linka audio check.", voice.languageTag, voice.id, request))
                    } else {
                        val clip = withContext(Dispatchers.IO) {
                            completed(broker.createTemporaryFile("host-tone.wav", "audio/wav")).also {
                                source = it
                                completed(broker.sink(it)).buffer().use { sink -> sink.write(tone()) }
                            }
                        }
                        val info = completed(inspection.audio(clip))
                        requireCheck(info.mediaType == "audio/wav" && info.durationMillis == 300L,
                            "Native tone inspection failed")
                        completed(audio.playClip(clip, request))
                    }
                }
            } catch (error: CheckFailure) {
                failure = error.message
            } catch (_: TimeoutCancellationException) {
                failure = "Native audio completion timed out"
            } catch (_: Throwable) {
                // Never export native exceptions, file paths, or voice identifiers to XCTest logs.
                failure = "Audio check failed"
            } finally {
                withContext(NonCancellable) {
                    suspend fun cleanup(action: suspend () -> Unit) {
                        try { action() } catch (_: Throwable) {
                            failure = failure?.let { "$it; cleanup failed" } ?: "Audio cleanup failed"
                        }
                    }
                    cleanup { backend?.stop(request) }
                    cleanup { backendJob.cancelAndJoin() }
                    // Backend cancellation enqueues observer disposal; finish it before the Swift callback.
                    cleanup { suspendCoroutine<Unit> { continuation ->
                        NSOperationQueue.mainQueue.addOperationWithBlock { continuation.resume(Unit) }
                    } }
                    cleanup { withContext(Dispatchers.IO) { source?.let { files?.discard(it) } } }
                    cleanup { filesJob.children.toList().joinAll() }
                    cleanup { filesJob.cancelAndJoin() }
                    cleanup { if (ownedRoot) withContext(Dispatchers.IO) { fs.deleteRecursively(root) } }
                }
                busy = false
                scope.cancel()
            }
            completion(failure)
        }
    }

    private class CheckFailure(message: String) : Exception(message)

    private fun requireCheck(condition: Boolean, message: String) {
        if (!condition) throw CheckFailure(message)
    }

    private fun <T> completed(result: OperationResult<T>): T = when (result) {
        is OperationResult.Completed -> result.value
        is OperationResult.Failed -> throw CheckFailure("Native audio failed: ${result.problem.code.name}")
        OperationResult.Cancelled -> throw CheckFailure("Native audio cancelled")
        OperationResult.UnconfirmedCommit -> throw CheckFailure("Native audio result unconfirmed")
    }

    private fun tone(): ByteArray {
        val frames = 4800 // PCM16LE mono, 16 kHz, 300 ms of finite 440 Hz tone.
        val bytes = ByteArray(44 + frames * 2)
        fun littleEndian(offset: Int, value: Int, size: Int) {
            repeat(size) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
        }
        "RIFF".encodeToByteArray().copyInto(bytes)
        littleEndian(4, 36 + frames * 2, 4)
        "WAVEfmt ".encodeToByteArray().copyInto(bytes, 8)
        littleEndian(16, 16, 4); littleEndian(20, 1, 2); littleEndian(22, 1, 2)
        littleEndian(24, 16000, 4); littleEndian(28, 32000, 4)
        littleEndian(32, 2, 2); littleEndian(34, 16, 2)
        "data".encodeToByteArray().copyInto(bytes, 36)
        littleEndian(40, frames * 2, 4)
        repeat(frames) { littleEndian(44 + it * 2, (sin(2 * PI * 440 * it / 16000) * 1000).toInt(), 2) }
        val header = Buffer().write(bytes)
        requireCheck(header.readUtf8(4) == "RIFF" && header.readIntLe() == 36 + frames * 2 &&
            header.readUtf8(8) == "WAVEfmt " && header.readIntLe() == 16 &&
            header.readShortLe().toInt() == 1 && header.readShortLe().toInt() == 1 &&
            header.readIntLe() == 16000 && header.readIntLe() == 32000 &&
            header.readShortLe().toInt() == 2 && header.readShortLe().toInt() == 16 &&
            header.readUtf8(4) == "data" && header.readIntLe() == frames * 2 &&
            header.size == frames * 2L && bytes.size == 44 + frames * 2,
            "Invalid generated tone header or frame count")
        return bytes
    }
}
