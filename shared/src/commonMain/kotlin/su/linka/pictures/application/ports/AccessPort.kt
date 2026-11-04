package su.linka.pictures.application.ports

import kotlinx.coroutines.flow.StateFlow
import su.linka.pictures.application.OperationResult

enum class ProtectedAction {
    CREATE_BOARD, EDIT_BOARD, APPLY_DRAFT, RENAME_BOARD, DELETE_BOARD, DELETE_DRAFT,
    IMPORT_BOARD, EXPORT_BOARD, CREATE_BACKUP, RESTORE_BACKUP, DELETE_RECOVERY,
    CHANGE_SETTINGS, CONFIGURE_LOCK, RESET_LOCK,
}

class AssistantCapability internal constructor(internal val token: String, val owner: String)

typealias CommitGuard = () -> OperationResult<Unit>

interface AssistantGate {
    fun check(action: ProtectedAction, capability: AssistantCapability?): OperationResult<Unit>
    fun guard(action: ProtectedAction, capability: AssistantCapability?): CommitGuard = { check(action, capability) }
}

enum class OwnerVerificationAvailability { UNKNOWN, AVAILABLE, UNAVAILABLE }

sealed interface AssistantState {
    data object Initializing : AssistantState
    data class Unprotected(val verification: OwnerVerificationAvailability) : AssistantState
    data class Protected(val legacy: Boolean, val verification: OwnerVerificationAvailability) : AssistantState
}

interface AssistantAccess : AssistantGate {
    val state: StateFlow<AssistantState>
    suspend fun unlock(password: String, ownerId: String): OperationResult<AssistantCapability>
    suspend fun setPassword(password: String, ownerId: String, capability: AssistantCapability?): OperationResult<Unit>
    suspend fun resetPasswordWithDeviceOwner(password: String, ownerId: String): OperationResult<Unit>
    suspend fun disableLock(capability: AssistantCapability, ownerId: String): OperationResult<Unit>
    suspend fun refreshPrerequisites()
    fun revokeOwner(ownerId: String)
    fun revokeAll()
}
