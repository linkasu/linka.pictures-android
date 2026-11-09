package su.linka.pictures.portability.zip

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.MediaKind

internal suspend fun copyStream(source: Source, sink: BufferedSink) {
    val buffer = Buffer()
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = source.read(buffer, 8192)
        if (count == -1L) return
        sink.write(buffer, count)
    }
}

internal suspend fun inspectMedia(file: LocalFile, kind: MediaKind, inspection: MediaInspectionPort): String = when (kind) {
    MediaKind.IMAGE -> inspection.image(file).value().let {
        limit(it.width <= 8192 && it.height <= 8192 && it.width.toLong() * it.height <= 16L * 1000 * 1000)
        it.mediaType
    }
    MediaKind.AUDIO -> inspection.audio(file).value().let {
        limit(it.durationMillis <= 10L * 60 * 1000)
        it.mediaType
    }
    MediaKind.ARCHIVE -> fail(ProblemCode.INVALID_DATA)
}

internal suspend fun <T> codecBoundary(block: suspend () -> T): OperationResult<T> = try {
    OperationResult.Completed(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: CodecCancelled) {
    OperationResult.Cancelled
} catch (_: CodecUnconfirmed) {
    OperationResult.UnconfirmedCommit
} catch (failure: CodecFailure) {
    OperationResult.Failed(failure.problem)
} catch (failure: SnapshotReadException) {
    OperationResult.Failed(failure.problem)
} catch (_: EOFException) {
    OperationResult.Failed(Problem(ProblemCode.UNSAFE_ARCHIVE))
} catch (_: IOException) {
    OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
} catch (_: SerializationException) {
    OperationResult.Failed(Problem(ProblemCode.INVALID_DATA))
} catch (_: IllegalArgumentException) {
    OperationResult.Failed(Problem(ProblemCode.INVALID_DATA))
} catch (_: Exception) {
    OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
}
