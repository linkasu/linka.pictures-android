package su.linka.pictures.ui.card

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import su.linka.pictures.application.authoring.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.editor.BufferedAuthoringInput

internal data class CardInputIdentity(
    val ownerId: String,
    val epoch: Long?,
    val draftId: DraftId,
    val pageId: PageId,
    val cardId: CardId,
) {
    fun matches(state: AuthoringState): Boolean {
        val form = state.draft?.form as? EditorForm.Card
        return state.ownerId == ownerId && state.epoch == epoch && state.draft?.id == draftId &&
            form?.pageId == pageId && form.cardId == cardId
    }
}

@Stable
internal class CardEditorInput(
    private val buffer: BufferedAuthoringInput<AuthoringAction.ChangeCard>,
    val change: (AuthoringAction.ChangeCard) -> Unit,
    private val dispatchSwitch: (AuthoringAction.SwitchCard) -> Unit,
) {
    val value get() = buffer.value
    val pending get() = buffer.pending
    var requestingSwitch by mutableStateOf(false)
        private set
    fun switchCard(action: AuthoringAction.SwitchCard) {
        if (requestingSwitch) return
        requestingSwitch = true
        buffer.afterAdmission { admitted ->
            if (admitted) dispatchSwitch(action)
            requestingSwitch = false
        }
    }
    fun discardPending() = buffer.discardPending()
}

/** Admission acknowledgements are not durability; keep this owner above responsive branches. */
@Composable
internal fun rememberCardEditorInput(
    state: AuthoringState,
    ownerId: String,
    dispatchEdited: (AuthoringAction, (AuthoringState) -> Unit) -> Unit,
): CardEditorInput? {
    val form = state.draft?.form as? EditorForm.Card ?: return null
    val identity = CardInputIdentity(ownerId, state.epoch, state.draft.id, form.pageId, form.cardId)
    val scope = rememberCoroutineScope()
    val dispatch by rememberUpdatedState(dispatchEdited)
    val currentState by rememberUpdatedState(state)
    val buffer = remember(identity) { BufferedAuthoringInput(form.payload(), state.editVersion) }
    SideEffect { buffer.acceptExternal(form.payload(), state.editVersion) }
    return remember(identity) {
        CardEditorInput(buffer, change = { next ->
            val token = buffer.edit(next)
            dispatch(next) { accepted ->
                if (identity.matches(accepted)) {
                    val payload = (accepted.draft!!.form as EditorForm.Card).payload()
                    scope.launch(Dispatchers.Main.immediate) {
                        buffer.handled(token, payload, accepted.editVersion)
                    }
                }
            }
        }, dispatchSwitch = { action ->
            val current = currentState
            if (identity.matches(current) && current.phase == AuthoringPhase.EDITING &&
                !current.mediaBusy && !current.switchingCard && current.problem == null) {
                dispatch(action.copy(expected = current.stamp()?.copy(editVersion = buffer.admittedEditVersion))) {}
            }
        })
    }
}

private fun EditorForm.Card.payload() =
    AuthoringAction.ChangeCard(label, speechText, speechChoice, useLabelForSpeech)
