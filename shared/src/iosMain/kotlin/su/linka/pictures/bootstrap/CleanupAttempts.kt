package su.linka.pictures.bootstrap

import su.linka.pictures.application.*

/** Every independent release is attempted; a secondary cleanup error cannot replace the primary problem. */
internal class CleanupAttempts(primary: Problem? = null) {
    var problem: Problem? = primary
        private set
    fun attempt(action: () -> Unit) {
        try { action() } catch (_: Throwable) { if (problem == null) problem = Problem(ProblemCode.IO_FAILURE) }
    }
    suspend fun suspending(action: suspend () -> Unit) {
        try { action() } catch (_: Throwable) { if (problem == null) problem = Problem(ProblemCode.IO_FAILURE) }
    }
}
