package su.linka.pictures.access

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import su.linka.pictures.application.*
import su.linka.pictures.domain.RequestId

/** Ephemeral proof delivery only; the Access guard still authorizes credential publication. */
internal class OwnerVerificationRequest(val id: RequestId, val ownerId: String) {
    private enum class Phase { AWAITING_PROOF, AWAITING_FOREGROUND, TERMINAL }
    private val phase = MutableStateFlow(Phase.AWAITING_PROOF)
    private val reply = CompletableDeferred<OperationResult<Unit>>()
    val terminal: Boolean get() = phase.value == Phase.TERMINAL

    fun proof(result: OperationResult<Unit>, foreground: Boolean) {
        if (result is OperationResult.Completed && !foreground) {
            phase.compareAndSet(Phase.AWAITING_PROOF, Phase.AWAITING_FOREGROUND)
        } else if (phase.compareAndSet(Phase.AWAITING_PROOF, Phase.TERMINAL)) {
            reply.complete(if (result == OperationResult.UnconfirmedCommit)
                OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) else result)
        }
    }

    fun foreground() {
        if (phase.compareAndSet(Phase.AWAITING_FOREGROUND, Phase.TERMINAL))
            reply.complete(OperationResult.Completed(Unit))
    }

    fun cancel() {
        while (true) {
            val previous = phase.value
            if (previous == Phase.TERMINAL) return
            if (phase.compareAndSet(previous, Phase.TERMINAL)) {
                reply.complete(OperationResult.Cancelled)
                return
            }
        }
    }

    suspend fun await(): OperationResult<Unit> = reply.await()
}
