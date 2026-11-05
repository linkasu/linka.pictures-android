@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package su.linka.pictures.migration

import kotlin.concurrent.atomics.AtomicReference
import kotlin.coroutines.CoroutineContext
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.CommitGuard
import su.linka.pictures.application.ports.WritePermit
import su.linka.pictures.storage.ensureStorageActive

// Single-coordinator authority only; commit guards must never touch filesystem/JSON/callbacks.
internal class MigrationAuthority {
    private data class Admission(val id: String, val epoch: Long, val nonce: Any, val phase: MigrationPhase)
    private val current = AtomicReference<Admission?>(null)

    fun begin(id: String, epoch: Long) {
        current.store(Admission(id, epoch, Any(), MigrationPhase.INVENTORY))
    }

    fun advance(id: String, phase: MigrationPhase) {
        while (true) {
            val old = current.load() ?: return
            if (old.id != id) return
            if (current.compareAndSet(old, old.copy(phase = phase))) return
        }
    }

    fun revoke() { current.store(null) }

    fun permit(caller: CoroutineContext): WritePermit {
        val captured = checkNotNull(current.load())
        return WritePermit(captured.epoch, guard(captured, caller, MigrationPhase.COPYING_VALIDATING))
    }

    fun guard(caller: CoroutineContext, phase: MigrationPhase): CommitGuard = guard(checkNotNull(current.load()), caller, phase)

    private fun guard(captured: Admission, caller: CoroutineContext, phase: MigrationPhase): CommitGuard = {
        caller.ensureStorageActive()
        val actual = current.load()
        if (actual != null && actual.nonce === captured.nonce && actual.id == captured.id &&
            actual.epoch == captured.epoch && actual.phase == phase)
            OperationResult.Completed(Unit) else OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
    }
}
