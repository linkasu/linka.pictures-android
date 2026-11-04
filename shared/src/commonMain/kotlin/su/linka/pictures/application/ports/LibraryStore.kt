package su.linka.pictures.application.ports

import kotlinx.coroutines.flow.StateFlow
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.Problem
import su.linka.pictures.domain.*

enum class LibraryEntryKind { BOARD, RETAINED_ARCHIVE }

data class LibraryEntrySummary(
    val id: BoardId,
    val name: String,
    val revision: Long,
    val kind: LibraryEntryKind,
    val cardCount: Int?,
    val problem: Problem? = null,
)

data class LibraryListing(val epoch: Long, val revision: Long, val items: List<LibraryEntrySummary>, val refreshProblem: Problem? = null)
data class LeasedBoard(val epoch: Long, val board: PublishedBoard, val lease: MediaLease)
data class LeasedDraft(val epoch: Long, val draft: StoredDraft, val lease: MediaLease)
data class DraftSummary(val id: DraftId, val boardId: BoardId?, val name: String, val version: Long, val baseRevision: Long?, val hasOpenForm: Boolean)
data class WritePermit(val epoch: Long, val check: CommitGuard) {
    init { require(epoch >= 0) }
}
data class RecoveryInfo(
    val id: RecoveryId,
    val createdAtEpochMillis: Long,
    val capturedLibraryRevision: Long,
    val boardCount: Int,
    val draftCount: Int,
    val assetBytes: Long,
)

interface LibraryStore {
    val listing: StateFlow<LibraryListing>
    suspend fun board(id: BoardId): OperationResult<LeasedBoard>
    suspend fun draft(id: DraftId): OperationResult<LeasedDraft>
    suspend fun drafts(): OperationResult<List<DraftSummary>>
    suspend fun saveDraft(value: EditorDraft, expectedVersion: Long?, permit: WritePermit): OperationResult<StoredDraft>
    suspend fun deleteDraft(id: DraftId, expectedVersion: Long, permit: WritePermit): OperationResult<Unit>
    suspend fun publishDraft(id: DraftId, expectedDraftVersion: Long, expectedBoardRevision: Long?, permit: WritePermit): OperationResult<PublishedBoard>
    suspend fun addBoard(value: BoardDocument, permit: WritePermit): OperationResult<PublishedBoard>
    suspend fun replaceBoard(value: BoardDocument, expectedRevision: Long, permit: WritePermit): OperationResult<PublishedBoard>
    suspend fun renameBoard(id: BoardId, name: String, expectedRevision: Long, permit: WritePermit): OperationResult<PublishedBoard>
    suspend fun deleteBoard(id: BoardId, expectedRevision: Long, permit: WritePermit): OperationResult<Unit>
    suspend fun retainArchive(value: RetainedArchive, permit: WritePermit): OperationResult<Unit>
    suspend fun settings(): OperationResult<PortableSettings>
    suspend fun saveSettings(value: PortableSettings, expectedLibraryRevision: Long, permit: WritePermit): OperationResult<Long>
    suspend fun snapshot(): OperationResult<SnapshotReader>
    suspend fun createSnapshotBuilder(): OperationResult<SnapshotBuilder>
    suspend fun replaceLibrary(value: SnapshotReader, expectedLibraryRevision: Long, expectedMessage: MessageStamp, permit: WritePermit): OperationResult<RecoveryInfo>
    suspend fun recovery(): OperationResult<RecoveryInfo?>
    suspend fun returnToRecovery(id: RecoveryId, expectedLibraryRevision: Long, expectedMessage: MessageStamp, permit: WritePermit): OperationResult<RecoveryInfo>
    suspend fun deleteRecovery(id: RecoveryId, permit: WritePermit): OperationResult<Unit>
}
