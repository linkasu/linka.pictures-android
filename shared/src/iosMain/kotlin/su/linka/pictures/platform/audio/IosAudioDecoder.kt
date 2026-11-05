@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.audio

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.posix.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.platform.audio.decoder.*
import su.linka.pictures.text.isNativeText

/** Development-only FFmpeg compatibility decoder. Never adopts/replaces canonical media. */
internal class IosAudioDecoder(private val files: PlatformFilesPort) {
    private data class Identity(val device: Long, val inode: ULong)
    private data class Owned(val file: TemporaryFile, val identity: Identity)

    private companion object {
        val processDecoder = Mutex()
        val cancellation = staticCFunction<COpaquePointer?, Int> { pointer ->
            try { if (pointer?.asStableRef<Job>()?.get()?.isActive == false) 1 else 0 } catch (_: Throwable) { 1 }
        }
    }

    suspend fun inspect(file: LocalFile): OperationResult<AudioInfo> = withContext(Dispatchers.IO) {
        processDecoder.withLock {
            if (!validPath(file.path)) return@withLock failed(ProblemCode.INVALID_DATA)
            val result = native(file, -1)
            when (result.status) {
                LINKA_AUDIO_OK -> OperationResult.Completed(AudioInfo(result.duration, result.mediaType))
                else -> failure(result.status)
            }
        }
    }

    suspend fun decodeToWave(file: LocalFile): OperationResult<TemporaryFile> {
        var owned: Owned? = null
        var handedOff = false
        try {
            val result = withContext(Dispatchers.IO) {
                processDecoder.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!validPath(file.path)) return@withLock failed(ProblemCode.INVALID_DATA)
                    val input = open(file.path, O_RDONLY or O_NOFOLLOW)
                    if (input < 0) return@withLock failed(ProblemCode.IO_FAILURE)
                    try {
                        val source = metadata(input) ?: return@withLock failed(ProblemCode.INVALID_DATA)
                        if (source.second <= 0) return@withLock failed(ProblemCode.INVALID_DATA)
                        if (source.second > 64L * 1024 * 1024) return@withLock failed(ProblemCode.LIMIT_EXCEEDED)
                        val temporary = when (val created = files.createTemporaryFile("compatibility.wav", "audio/wav")) {
                            is OperationResult.Completed -> created.value
                            else -> return@withLock created
                        }
                        if (!validPath(temporary.path)) return@withLock failed(ProblemCode.INVALID_DATA)
                        // Factory is the authority for this fresh owned reservation. Never truncate it.
                        val output = open(temporary.path, O_WRONLY or O_NOFOLLOW)
                        if (output < 0) return@withLock failed(ProblemCode.IO_FAILURE)
                        try {
                            val destination = metadata(output, singleLink = true) ?: return@withLock failed(ProblemCode.CONFLICT)
                            if (destination.second != 0L || destination.first == source.first) return@withLock failed(ProblemCode.CONFLICT)
                            owned = Owned(temporary, destination.first)
                            val decoded = native(file, output)
                            if (decoded.status != LINKA_AUDIO_OK) return@withLock failure(decoded.status)
                            currentCoroutineContext().ensureActive()
                            OperationResult.Completed(temporary)
                        } finally { close(output) }
                    } finally { close(input) }
                }
            }
            currentCoroutineContext().ensureActive()
            handedOff = result is OperationResult.Completed
            return result
        } finally {
            if (!handedOff) owned?.let { claim ->
                withContext(NonCancellable + Dispatchers.IO) {
                    // A replaced path is foreign, even if its old filename was our reservation.
                    if (identityAtPath(claim.file.path) == claim.identity) runCatching { files.discard(claim.file) }
                }
            }
        }
    }

    private data class Decoded(val status: Int, val duration: Long, val mediaType: String)

    private suspend fun native(file: LocalFile, output: Int): Decoded {
        val job = currentCoroutineContext().job
        val reference = StableRef.create(job)
        return try {
            memScoped {
                val result = alloc<LinkaAudioResult>()
                val status = linka_audio_decode(file.path, output, cancellation, reference.asCPointer(), result.ptr)
                val type = when (result.format) {
                    LINKA_AUDIO_OGG -> "audio/ogg"
                    LINKA_AUDIO_MP3 -> "audio/mpeg"
                    LINKA_AUDIO_AMR -> "audio/amr"
                    LINKA_AUDIO_3GP -> "audio/3gpp"
                    LINKA_AUDIO_MP4 -> "audio/mp4"
                    else -> "audio/wav"
                }
                currentCoroutineContext().ensureActive()
                Decoded(status, result.duration_millis, type)
            }
        } finally { reference.dispose() }
    }

    private fun metadata(fd: Int, singleLink: Boolean = false): Pair<Identity, Long>? = memScoped {
        val value = alloc<stat>()
        if (fstat(fd, value.ptr) != 0 || (value.st_mode.toInt() and S_IFMT) != S_IFREG || (singleLink && value.st_nlink.toInt() != 1)) return@memScoped null
        Identity(value.st_dev.toLong(), value.st_ino.toULong()) to value.st_size
    }

    private fun identityAtPath(path: String): Identity? = memScoped {
        val value = alloc<stat>()
        if (lstat(path, value.ptr) != 0 || (value.st_mode.toInt() and S_IFMT) != S_IFREG) return@memScoped null
        Identity(value.st_dev.toLong(), value.st_ino.toULong())
    }

    private fun validPath(path: String): Boolean = path.startsWith('/') && isNativeText(path)
    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))
    private fun failure(status: Int): OperationResult<Nothing> = when (status) {
        LINKA_AUDIO_UNSUPPORTED -> failed(ProblemCode.UNSUPPORTED_MEDIA)
        LINKA_AUDIO_LIMIT -> failed(ProblemCode.LIMIT_EXCEEDED)
        LINKA_AUDIO_IO -> failed(ProblemCode.IO_FAILURE)
        LINKA_AUDIO_CANCELLED -> OperationResult.Cancelled
        else -> failed(ProblemCode.INVALID_DATA)
    }
}
