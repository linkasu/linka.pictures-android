package su.linka.pictures.application.portability

import su.linka.pictures.application.Problem
import su.linka.pictures.domain.OperationId

enum class TransferPhase { VALIDATING, PREPARING, AWAITING_CONFIRMATION, WRITING, COMPLETED, CANCELLED, FAILED }

data class TransferState(
    val id: OperationId,
    val phase: TransferPhase,
    val processedEntries: Int = 0,
    val processedBytes: Long = 0,
    val problem: Problem? = null,
) {
    init {
        require(processedEntries >= 0 && processedBytes >= 0)
        require((phase == TransferPhase.FAILED) == (problem != null))
    }
}
