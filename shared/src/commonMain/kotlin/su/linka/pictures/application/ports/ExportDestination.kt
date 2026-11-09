package su.linka.pictures.application.ports

import su.linka.pictures.application.OperationResult

// Scoped native destination, not a persisted provider URI or permission to issue data.
interface ExportDestination {
    val ownerId: String
    // Single use, foreground only. Check guard before external open/truncate and between copy chunks.
    suspend fun write(file: LocalFile, guard: CommitGuard): OperationResult<Unit>
    fun close()
}
