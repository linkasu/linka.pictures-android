package su.linka.pictures.ui.portability

import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.portability.*
import su.linka.pictures.application.ports.*

internal data class TransferReadiness(val fresh: Boolean, val canConfirm: Boolean, val canCancel: Boolean)

internal fun transferReadiness(operation: TransferOperationState, owner: String,
    capability: AssistantCapability?, gate: AssistantGate, enabled: Boolean, foreground: Boolean,
    busy: Boolean): TransferReadiness {
    val owns = operation.ownerId == owner
    val fresh = owns && capability != null &&
        gate.check(transferProtectedAction(operation.kind), capability, owner) is OperationResult.Completed
    val pending = operation.transfer.phase in setOf(TransferPhase.VALIDATING, TransferPhase.PREPARING,
        TransferPhase.AWAITING_CONFIRMATION, TransferPhase.WRITING)
    return TransferReadiness(fresh,
        enabled && fresh && !busy && operation.transfer.phase == TransferPhase.AWAITING_CONFIRMATION,
        foreground && owns && pending)
}

internal fun transferProtectedAction(kind: TransferKind): ProtectedAction = when (kind) {
    TransferKind.IMPORT -> ProtectedAction.IMPORT_BOARD
    TransferKind.EXPORT -> ProtectedAction.EXPORT_BOARD
    TransferKind.BACKUP -> ProtectedAction.CREATE_BACKUP
    TransferKind.RESTORE, TransferKind.RETURN_TO_RECOVERY -> ProtectedAction.RESTORE_BACKUP
    TransferKind.DELETE_RECOVERY -> ProtectedAction.DELETE_RECOVERY
}
