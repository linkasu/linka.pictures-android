@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.audio

import kotlinx.cinterop.*
import okio.Buffer
import okio.use
import platform.posix.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.text.isNativeText

// Worker-only: do not claim cleanup ownership of a factory result before this succeeds.
internal fun validateAudioReservation(files: PlatformFilesPort, file: TemporaryFile) {
    if (!isNativeText(file.path) || !file.path.startsWith('/') || !file.path.endsWith(".wav", ignoreCase = true))
        throw IosAudioFailure(ProblemCode.INVALID_DATA)
    // TemporaryFile source validates the files-port authority, not just the filesystem path.
    val source = when (val result = files.source(file)) {
        is OperationResult.Completed -> result.value
        is OperationResult.Failed -> throw IosAudioFailure(result)
        OperationResult.Cancelled -> throw IosAudioFailure(OperationResult.Cancelled)
        OperationResult.UnconfirmedCommit -> throw IosAudioFailure(OperationResult.UnconfirmedCommit)
    }
    source.use { if (it.read(Buffer(), 1) != -1L) throw IosAudioFailure(ProblemCode.CONFLICT) }
    memScoped {
        val metadata = alloc<stat>()
        if (lstat(file.path, metadata.ptr) != 0) throw IosAudioFailure(ProblemCode.IO_FAILURE)
        if ((metadata.st_mode.toInt() and S_IFMT) != S_IFREG || metadata.st_nlink.toLong() != 1L || metadata.st_size != 0L)
            throw IosAudioFailure(ProblemCode.CONFLICT)
    }
}
