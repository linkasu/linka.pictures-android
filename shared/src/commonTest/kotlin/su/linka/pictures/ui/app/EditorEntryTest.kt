package su.linka.pictures.ui.app

import kotlin.test.*
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

class EditorEntryTest {
    private val unprotected = AssistantState.Unprotected(OwnerVerificationAvailability.UNAVAILABLE)

    @Test fun explicitInitialIntentsUseTheirCanonicalAction() {
        val actions = listOf(AuthoringAction.NewBoard(), AuthoringAction.EditBoard(BoardId("b")),
            AuthoringAction.ResumeDraft(DraftId("d")))
        assertEquals(listOf(ProtectedAction.CREATE_BOARD, ProtectedAction.EDIT_BOARD, ProtectedAction.EDIT_BOARD),
            actions.map { it.initialProtectedAction() })
        actions.forEach { assertTrue(initialEditorEntryReady(it, AuthoringPhase.IDLE, unprotected, true, true, false)) }
    }

    @Test fun noAutoEntryAfterDeniedAdmissionStaleRoutePauseOrFirstAttempt() {
        val action = AuthoringAction.NewBoard()
        assertFalse(initialEditorEntryReady(action, AuthoringPhase.IDLE, AssistantState.Initializing, true, true, false))
        assertFalse(initialEditorEntryReady(action, AuthoringPhase.IDLE,
            AssistantState.Protected(false, OwnerVerificationAvailability.AVAILABLE), true, true, false))
        assertFalse(initialEditorEntryReady(action, AuthoringPhase.IDLE, unprotected, true, false, false))
        assertFalse(initialEditorEntryReady(action, AuthoringPhase.IDLE, unprotected, false, true, false))
        assertFalse(initialEditorEntryReady(action, AuthoringPhase.IDLE, unprotected, true, true, true))
        AuthoringPhase.entries.filter { it != AuthoringPhase.IDLE }.forEach {
            assertFalse(initialEditorEntryReady(action, it, unprotected, true, true, false))
        }
        assertFalse(initialEditorEntryReady(AuthoringAction.Resume, AuthoringPhase.IDLE, unprotected, true, true, false))
        assertFalse(initialEditorEntryReady(null, AuthoringPhase.IDLE, unprotected, true, true, false))
    }
}
