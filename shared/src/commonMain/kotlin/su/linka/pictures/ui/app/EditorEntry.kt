package su.linka.pictures.ui.app

import su.linka.pictures.application.authoring.AuthoringAction
import su.linka.pictures.application.authoring.AuthoringPhase
import su.linka.pictures.application.ports.AssistantState
import su.linka.pictures.application.ports.ProtectedAction

internal fun AuthoringAction.initialProtectedAction(): ProtectedAction? = when (this) {
    is AuthoringAction.NewBoard -> ProtectedAction.CREATE_BOARD
    is AuthoringAction.EditBoard, is AuthoringAction.ResumeDraft -> ProtectedAction.EDIT_BOARD
    else -> null
}

internal fun initialEditorEntryReady(action: AuthoringAction?, phase: AuthoringPhase,
    access: AssistantState, freshRoute: Boolean, permitted: Boolean, attempted: Boolean): Boolean =
    !attempted && freshRoute && phase == AuthoringPhase.IDLE && access is AssistantState.Unprotected &&
        action?.initialProtectedAction() != null && permitted
