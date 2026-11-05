package su.linka.pictures.application.ports

import okio.Sink
import okio.Source
import su.linka.pictures.application.OperationResult

enum class PickedFileKind { IMAGE, AUDIO, LINKA, BACKUP }

interface PlatformFilesPort {
    suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile>
    suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination>
    fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile>
    fun source(file: LocalFile): OperationResult<Source>
    fun sink(file: TemporaryFile): OperationResult<Sink>
    fun discard(file: TemporaryFile)
}
