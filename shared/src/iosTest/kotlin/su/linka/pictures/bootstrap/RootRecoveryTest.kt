package su.linka.pictures.bootstrap

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import su.linka.pictures.application.*

class RootRecoveryTest {
    @Test fun postReadyErrorKeepsVisibleSessionAndDoesNotRequestGraphOrEditorDisposal() {
        val recovery = RootRecovery()
        val primary = Problem(ProblemCode.IO_FAILURE, systemCode = 5)
        assertEquals(RootFailureAction.KEEP_VISIBLE_SESSION, recovery.fail(primary, ready = true))
        assertTrue(recovery.notice.value.readOnly)
        assertNull(recovery.notice.value.replacementEpoch)
        assertFalse(recovery.beginReplacement(7)) // Error cannot silently reopen admission/retry.
        assertEquals(7L, recovery.notice.value.replacementEpoch) // A later already-running commit still invalidates the old phrase.
        recovery.fail(Problem(ProblemCode.INTERRUPTED), ready = true)
        assertEquals(primary, recovery.notice.value.problem)
        assertEquals(primary, recovery.denied())
    }

    @Test fun startupAndConfirmedReplacementErrorsHaveDifferentReleaseSemantics() {
        val primary = Problem(ProblemCode.INTEGRITY_FAILURE)
        assertEquals(RootFailureAction.CLOSE_STARTUP, RootRecovery().fail(primary, ready = false))
        val recovery = RootRecovery()
        assertTrue(recovery.beginReplacement(9))
        assertTrue(recovery.notice.value.readOnly)
        assertNotNull(recovery.denied())
        assertEquals(RootFailureAction.INVALIDATE_REPLACED_SESSION, recovery.fail(primary, ready = true))
        assertEquals(9L, recovery.notice.value.replacementEpoch)
        assertFalse(recovery.replacementReady())
        assertEquals(9L, recovery.notice.value.replacementEpoch) // Never claim rollback or display old undo as current.
    }

    @Test fun successfulReplacementReopensAdmissionOnlyAfterNewSessionReady() {
        val recovery = RootRecovery()
        assertTrue(recovery.beginReplacement(2))
        assertNotNull(recovery.denied())
        assertTrue(recovery.replacementReady())
        assertFalse(recovery.notice.value.readOnly)
        assertNull(recovery.denied())
    }

    @Test fun independentCleanupContinuesAfterThrowsAndPreservesPrimaryProblem() = runTest {
        val primary = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
        val cleanup = CleanupAttempts(primary)
        val attempted = mutableListOf<String>()
        cleanup.attempt { attempted += "admission-closed" }
        cleanup.attempt { attempted += "access-revoked" }
        cleanup.attempt { attempted += "audio-stop"; error("controlled audio failure") }
        cleanup.attempt { attempted += "files"; error("controlled native destination failure") }
        cleanup.attempt { attempted += "scope-cancel" }
        cleanup.attempt { attempted += "observer-detach" }
        cleanup.suspending { attempted += "sqlite-close"; error("controlled writer-close failure") }
        cleanup.suspending { attempted += "owned-cache-release" }
        assertEquals(listOf("admission-closed", "access-revoked", "audio-stop", "files", "scope-cancel", "observer-detach", "sqlite-close", "owned-cache-release"), attempted)
        assertEquals(primary, cleanup.problem)
        val secondaryOnly = CleanupAttempts()
        secondaryOnly.attempt { error("controlled failure") }
        assertEquals(Problem(ProblemCode.IO_FAILURE), secondaryOnly.problem)
    }
}
