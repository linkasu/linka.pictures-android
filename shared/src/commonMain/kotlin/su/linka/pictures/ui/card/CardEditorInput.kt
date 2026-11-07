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
) {
    val value get() = buffer.value
    val pending get() = buffer.pending
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
    val buffer = remember(identity) { BufferedAuthoringInput(form.payload(), state.editVersion) }
    SideEffect { buffer.acceptExternal(form.payload(), state.editVersion) }
    return remember(identity) {
        CardEditorInput(buffer) { next ->
            val token = buffer.edit(next)
            dispatch(next) { accepted ->
                if (identity.matches(accepted)) {
                    val payload = (accepted.draft!!.form as EditorForm.Card).payload()
                    scope.launch(Dispatchers.Main.immediate) {
                        buffer.handled(token, payload, accepted.editVersion)
                    }
                }
            }
        }
    }
}

private fun EditorForm.Card.payload() =
    AuthoringAction.ChangeCard(label, speechText, speechChoice, useLabelForSpeech)
