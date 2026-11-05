package su.linka.pictures.application.ports

import okio.Sink
import okio.Source
import su.linka.pictures.application.OperationResult

enum class PickedFileKind { IMAGE, AUDIO, LINKA, BACKUP }

interface PlatformFilesPort {
    suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile>
    suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination>
    // Creation and stream operations are blocking: callers run them on a worker, never UI Main.
    fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile>
    fun source(file: LocalFile): OperationResult<Source>
    fun sink(file: TemporaryFile): OperationResult<Sink>
    // Safe from cancellation callbacks on any thread: invalidate ownership now, unlink on an owned worker.
    // Physical removal is best-effort; no library/immutable file may be removed.
    fun discard(file: TemporaryFile)
}
