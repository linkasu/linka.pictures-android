package su.linka.pictures.application.portability

import su.linka.pictures.application.ports.SnapshotCounts
import su.linka.pictures.domain.*

enum class TransferKind { IMPORT, RESTORE, EXPORT, BACKUP, RETURN_TO_RECOVERY, DELETE_RECOVERY }

// Passive data only: no file handles, leases, destinations or authorization tokens.
data class TransferPreview(
    val kind: TransferKind,
    val name: String? = null,
    val board: BoardTransferReport? = null,
    val snapshot: SnapshotCounts? = null,
    val expectedLibraryRevision: Long? = null,
    val expectedMessage: MessageStamp? = null,
    val folderCount: Int? = null,
)

data class TransferOperationState(
    val ownerId: String,
    val kind: TransferKind,
    val transfer: TransferState,
    val preview: TransferPreview? = null,
    val stateCommittedEpoch: Long? = null,
)

sealed interface TransferConfirmation {
    data object ImportNew : TransferConfirmation
    data class ImportReplace(val boardId: BoardId, val expectedRevision: Long) : TransferConfirmation {
        init { require(expectedRevision > 0) }
    }
    data object WriteFile : TransferConfirmation
    data object ReplaceWholeLibrary : TransferConfirmation
    data object ReturnToRecovery : TransferConfirmation
    data object DeleteRecovery : TransferConfirmation
}
