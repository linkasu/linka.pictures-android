package su.linka.pictures.application.ports

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.Problem
import su.linka.pictures.domain.*

@Serializable
data class SnapshotCounts(val boards: Int, val drafts: Int, val retained: Int, val assets: Int, val assetBytes: Long) {
    init { require(boards >= 0 && drafts >= 0 && retained >= 0 && assets >= 0 && assetBytes >= 0) }
}

class SnapshotReadException(val problem: Problem) : Exception(problem.code.name)

// Repeatable immutable metadata streams backed by owned staging storage, not one giant JSON tree.
interface SnapshotReader : AutoCloseable {
    val epoch: Long
    val revision: Long
    val settings: PortableSettings
    val counts: SnapshotCounts
    val lease: MediaLease
    fun boards(): Flow<PublishedBoard>
    fun drafts(): Flow<StoredDraft>
    fun retained(): Flow<RetainedArchive>
    fun assets(): Flow<MediaAsset>
}

interface SnapshotBuilder : AutoCloseable {
    suspend fun addBoard(value: PublishedBoard): OperationResult<Unit>
    suspend fun addDraft(value: StoredDraft): OperationResult<Unit>
    suspend fun addRetained(value: RetainedArchive): OperationResult<Unit>
    suspend fun addAsset(value: MediaAsset): OperationResult<Unit>
    suspend fun seal(revision: Long, settings: PortableSettings): OperationResult<SnapshotReader>
}
