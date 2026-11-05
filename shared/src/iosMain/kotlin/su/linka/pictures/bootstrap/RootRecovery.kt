package su.linka.pictures.bootstrap

import kotlinx.coroutines.flow.*
import su.linka.pictures.application.*

internal data class RootNotice(val problem: Problem? = null, val replacementEpoch: Long? = null, val reloading: Boolean = false) {
    val readOnly: Boolean get() = problem != null || reloading
}
internal enum class RootFailureAction { CLOSE_STARTUP, KEEP_VISIBLE_SESSION, INVALIDATE_REPLACED_SESSION }

/** Error is latched until process restart; a confirmed replacement is never described as rollback. */
internal class RootRecovery {
    private val mutableNotice = MutableStateFlow(RootNotice())
    val notice = mutableNotice.asStateFlow()
    fun fail(problem: Problem, ready: Boolean, committedEpoch: Long? = null): RootFailureAction {
        mutableNotice.update { it.copy(problem = it.problem ?: problem, replacementEpoch = committedEpoch ?: it.replacementEpoch, reloading = false) }
        return when {
            !ready -> RootFailureAction.CLOSE_STARTUP
            notice.value.replacementEpoch != null -> RootFailureAction.INVALIDATE_REPLACED_SESSION
            else -> RootFailureAction.KEEP_VISIBLE_SESSION
        }
    }
    fun beginReplacement(epoch: Long): Boolean {
        if (notice.value.problem != null) {
            mutableNotice.update { it.copy(replacementEpoch = epoch, reloading = false) }
            return false
        }
        mutableNotice.value = RootNotice(replacementEpoch = epoch, reloading = true)
        return true
    }
    fun replacementReady(): Boolean {
        if (notice.value.problem != null) return false
        mutableNotice.value = RootNotice()
        return true
    }
    fun denied(): Problem? = notice.value.let { it.problem ?: if (it.reloading) Problem(ProblemCode.STALE_REQUEST) else null }
}
