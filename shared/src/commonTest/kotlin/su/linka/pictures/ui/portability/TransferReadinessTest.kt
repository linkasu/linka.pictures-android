package su.linka.pictures.ui.portability

import kotlin.test.*
import su.linka.pictures.application.*
import su.linka.pictures.application.portability.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.OperationId

class TransferReadinessTest {
    private class FakeGateRevoked : AssistantGate {
        var live: AssistantCapability? = null
        val actions = mutableListOf<ProtectedAction>()
        override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): OperationResult<Unit> {
            actions += action
            return if (capability != null && capability === live && capability.owner == ownerId)
                OperationResult.Completed(Unit) else OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
        }
        override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard =
            error("Presentation must not create an application commit guard")
    }
    private fun operation(kind: TransferKind = TransferKind.EXPORT,
        phase: TransferPhase = TransferPhase.AWAITING_CONFIRMATION, owner: String = "owner") =
        TransferOperationState(owner, kind, TransferState(OperationId("opaque-operation"), phase, problem = when (phase) {
            TransferPhase.FAILED -> Problem(ProblemCode.IO_FAILURE)
            TransferPhase.UNCONFIRMED -> Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
            else -> null
        }))

    @Test fun staleNonnullPickerCapabilityCannotConfirmAndFreshUnlockEnablesEveryKind() {
        val gate = FakeGateRevoked()
        val old = AssistantCapability("old-test-only", "owner")
        gate.live = old
        assertTrue(transferReadiness(operation(), "owner", old, gate, true, true, false).canConfirm)
        gate.live = null
        val expected = mapOf(TransferKind.IMPORT to ProtectedAction.IMPORT_BOARD,
            TransferKind.EXPORT to ProtectedAction.EXPORT_BOARD, TransferKind.BACKUP to ProtectedAction.CREATE_BACKUP,
            TransferKind.RESTORE to ProtectedAction.RESTORE_BACKUP,
            TransferKind.RETURN_TO_RECOVERY to ProtectedAction.RESTORE_BACKUP,
            TransferKind.DELETE_RECOVERY to ProtectedAction.DELETE_RECOVERY)
        for (kind in TransferKind.entries) {
            val stale = transferReadiness(operation(kind), "owner", old, gate, true, true, false)
            assertFalse(stale.fresh, "stale nonnull capability for $kind")
            assertFalse(stale.canConfirm)
            assertTrue(stale.canCancel)
            assertEquals(expected.getValue(kind), gate.actions.last())
            val fresh = AssistantCapability("new-test-only-${kind.name}", "owner")
            gate.live = fresh
            assertTrue(transferReadiness(operation(kind), "owner", fresh, gate, true, true, false).canConfirm)
            assertEquals(expected.getValue(kind), gate.actions.last())
            gate.live = null
        }
        assertFalse(transferReadiness(operation(), "owner", null, gate, true, true, false).fresh)
        val foreign = AssistantCapability("foreign-test-only", "other"); gate.live = foreign
        assertFalse(transferReadiness(operation(), "owner", foreign, gate, true, true, false).canConfirm)
    }

    @Test fun confirmationStillRequiresAwaitingPhaseEnabledAndNotBusy() {
        val gate = FakeGateRevoked()
        val fresh = AssistantCapability("fresh-test-only", "owner"); gate.live = fresh
        for (phase in TransferPhase.entries) {
            val readiness = transferReadiness(operation(phase = phase), "owner", fresh, gate, true, true, false)
            assertEquals(phase == TransferPhase.AWAITING_CONFIRMATION, readiness.canConfirm)
        }
        assertFalse(transferReadiness(operation(), "owner", fresh, gate, false, true, false).canConfirm)
        assertFalse(transferReadiness(operation(), "owner", fresh, gate, true, true, true).canConfirm)
    }

    @Test fun ownPendingCancellationNeedsForegroundButNotFreshCapabilityOrBusinessReadiness() {
        val gate = FakeGateRevoked()
        val revoked = AssistantCapability("revoked-test-only", "owner")
        for (phase in listOf(TransferPhase.VALIDATING, TransferPhase.PREPARING, TransferPhase.AWAITING_CONFIRMATION, TransferPhase.WRITING)) {
            val readiness = transferReadiness(operation(phase = phase), "owner", revoked, gate, false, true, true)
            assertFalse(readiness.canConfirm)
            assertTrue(readiness.canCancel)
            assertFalse(transferReadiness(operation(phase = phase), "owner", revoked, gate, false, false, true).canCancel)
            assertFalse(transferReadiness(operation(phase = phase, owner = "other"), "owner", revoked, gate, false, true, true).canCancel)
        }
    }

    @Test fun terminalAndUnknownOutcomesDoNotOfferCancellationOrReconfirmation() {
        val gate = FakeGateRevoked()
        val fresh = AssistantCapability("fresh-test-only", "owner"); gate.live = fresh
        for (phase in listOf(TransferPhase.COMPLETED, TransferPhase.CANCELLED, TransferPhase.FAILED, TransferPhase.UNCONFIRMED)) {
            val readiness = transferReadiness(operation(phase = phase), "owner", fresh, gate, true, true, false)
            assertFalse(readiness.canCancel)
            assertFalse(readiness.canConfirm)
        }
    }
}
