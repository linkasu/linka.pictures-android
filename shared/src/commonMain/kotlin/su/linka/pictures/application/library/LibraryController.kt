package su.linka.pictures.application.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

/** Revision/epoch arguments must come from the snapshot on which the user acted. */
class LibraryController(private val library: LibraryStore, private val gate: AssistantGate) {
    private val mutableRestartProblem = MutableStateFlow<Problem?>(null)
    val restartProblem = mutableRestartProblem.asStateFlow()
    val requiresRestart: Boolean get() = restartProblem.value != null
    fun markCommitUnconfirmed() { mutableRestartProblem.compareAndSet(null, Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)) }
    fun haltForLifecycleFailure() { mutableRestartProblem.value = Problem(ProblemCode.IO_FAILURE) }
    suspend fun rename(owner: String, capability: AssistantCapability?, entry: LibraryEntrySummary,
        name: String, epoch: Long): OperationResult<PublishedBoard> =
        write(ProtectedAction.RENAME_BOARD, owner, capability, epoch) {
            library.renameBoard(entry.id, name, entry.revision, it)
        }

    suspend fun delete(owner: String, capability: AssistantCapability?, entry: LibraryEntrySummary,
        epoch: Long): OperationResult<Unit> = write(ProtectedAction.DELETE_BOARD, owner, capability, epoch) {
        library.deleteBoard(entry.id, entry.revision, it)
    }

    suspend fun deleteDraft(owner: String, capability: AssistantCapability?, draft: DraftSummary,
        epoch: Long): OperationResult<Unit> = write(ProtectedAction.DELETE_DRAFT, owner, capability, epoch) {
        library.deleteDraft(draft.id, draft.version, it)
    }

    suspend fun settings(owner: String, capability: AssistantCapability?, value: PortableSettings,
        revision: Long, epoch: Long): OperationResult<Long> =
        write(ProtectedAction.CHANGE_SETTINGS, owner, capability, epoch) {
            library.saveSettings(value, revision, it)
        }

    suspend fun organization(owner: String, capability: AssistantCapability?, value: LibraryOrganization,
        revision: Long, epoch: Long): OperationResult<Long> =
        write(ProtectedAction.ORGANIZE_LIBRARY, owner, capability, epoch) {
            library.saveOrganization(value, revision, it)
        }

    private suspend fun <T> write(action: ProtectedAction, owner: String, capability: AssistantCapability?,
        epoch: Long, operation: suspend (WritePermit) -> OperationResult<T>): OperationResult<T> {
        stopped()?.let { return it }
        val fresh = gate.guard(action, capability, owner)
        val guard: CommitGuard = {
            stopped() ?: if (library.listing.value.epoch != epoch) OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
            else fresh()
        }
        val result = when (val allowed = guard()) {
            is OperationResult.Completed -> operation(WritePermit(epoch, guard))
            is OperationResult.Failed -> allowed
            OperationResult.Cancelled -> OperationResult.Cancelled
            OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
        }
        if (result == OperationResult.UnconfirmedCommit) markCommitUnconfirmed()
        return result
    }

    private fun stopped(): OperationResult<Nothing>? = restartProblem.value?.let {
        if (it.code == ProblemCode.COMMIT_OUTCOME_UNKNOWN) OperationResult.UnconfirmedCommit else OperationResult.Failed(it)
    }
}
