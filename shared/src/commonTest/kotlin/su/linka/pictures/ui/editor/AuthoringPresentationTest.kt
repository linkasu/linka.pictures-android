package su.linka.pictures.ui.editor

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
        assertTrue(draftSaveLabel(DraftSavePhase.SAVED).contains("сохранён на устройстве"))
        assertTrue(draftSaveLabel(DraftSavePhase.FAILED).contains("не сохранён"))
        assertTrue(draftSaveLabel(DraftSavePhase.UNCONFIRMED).contains("не повторяйте"))
    }

    @Test fun conflictsLimitsAndReauthenticationHaveActionableMessages() {
        assertTrue(authoringProblemLabel(ProblemCode.CONFLICT).contains("черновик не потерян"))
        assertTrue(authoringProblemLabel(ProblemCode.LIMIT_EXCEEDED).contains("не обрезаны"))
        assertTrue(authoringProblemLabel(ProblemCode.UNAUTHORIZED).contains("явное продолжение"))
        assertTrue(authoringProblemLabel(ProblemCode.COMMIT_OUTCOME_UNKNOWN).contains("Не повторяйте"))
        ProblemCode.entries.forEach { assertTrue(authoringProblemLabel(it).isNotBlank()) }
    }
}
