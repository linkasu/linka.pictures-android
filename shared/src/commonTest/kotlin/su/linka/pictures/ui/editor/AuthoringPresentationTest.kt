package su.linka.pictures.ui.editor

import su.linka.pictures.resources.*
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.authoring.*
import kotlin.test.*

class AuthoringPresentationTest {
    @Test fun onlyEditingAndRecoverableConflictPermitLocalEditing() {
        for (phase in AuthoringPhase.entries) {
            assertEquals(phase == AuthoringPhase.EDITING || phase == AuthoringPhase.CONFLICT,
                AuthoringState(phase = phase).editable(), phase.name)
        }
    }

    @Test fun saveLabelsDistinguishDurabilityFailureAndUnknownOutcome() {
        assertEquals(DraftSavePhase.entries.size, DraftSavePhase.entries.map(::draftSaveLabel).toSet().size)
        assertEquals(Res.string.draft_saved_on_this_device, draftSaveLabel(DraftSavePhase.SAVED))
        assertEquals(Res.string.draft_was_not_saved_do_not_close, draftSaveLabel(DraftSavePhase.FAILED))
        assertEquals(Res.string.write_outcome_is_unknown_editing_is_stopped, draftSaveLabel(DraftSavePhase.UNCONFIRMED))
    }

    @Test fun conflictsLimitsAndReauthenticationHaveActionableMessages() {
        assertEquals(Res.string.the_board_or_draft_was_changed_by, authoringProblemLabel(ProblemCode.CONFLICT))
        assertEquals(Res.string.limit_exceeded_256_pages_grid_1_64, authoringProblemLabel(ProblemCode.LIMIT_EXCEEDED))
        assertEquals(Res.string.assistant_sign_in_and_explicit_continuation_are, authoringProblemLabel(ProblemCode.UNAUTHORIZED))
        assertEquals(Res.string.write_outcome_is_unknown_do_not_apply, authoringProblemLabel(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
        assertTrue(ProblemCode.entries.map(::authoringProblemLabel).toSet().size > 1)
    }
}
