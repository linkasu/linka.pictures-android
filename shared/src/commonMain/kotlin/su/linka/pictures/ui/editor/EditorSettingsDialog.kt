package su.linka.pictures.ui.editor

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import su.linka.pictures.application.authoring.*
import su.linka.pictures.domain.*

@Composable
internal fun EditorSettingsDialog(
    state: AuthoringState,
    ownerId: String,
    enabled: Boolean,
    dispatchEdited: (AuthoringAction, (AuthoringState) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    page: BoardPage?,
    dispatch: (AuthoringAction) -> Unit,
    onGridChange: () -> Unit,
) {
    val draft = state.draft ?: return
    val document = draft.document
    val draftId = draft.id
    val epoch = state.epoch
    val input = remember(ownerId, epoch, draftId) {
        BufferedAuthoringInput(AuthoringAction.Details(document.name, document.description, document.behavior), state.editVersion)
    }
    val scope = rememberCoroutineScope()
    SideEffect { input.acceptExternal(AuthoringAction.Details(document.name, document.description, document.behavior), state.editVersion) }
    val change: (AuthoringAction.Details) -> Unit = { next ->
        val token = input.edit(next)
        dispatchEdited(next) { accepted ->
            if (accepted.ownerId == ownerId && accepted.epoch == epoch && accepted.draft?.id == draftId) {
                val actual = accepted.draft.document
                val payload = AuthoringAction.Details(actual.name, actual.description, actual.behavior)
                val editVersion = accepted.editVersion
                scope.launch(Dispatchers.Main.immediate) {
                    input.handled(token, payload, editVersion)
                }
            }
        }
    }
    val pageId = page?.id
    val gridForm = draft.form as? EditorForm.Grid
    val gridEnabled = enabled && draft.form !is EditorForm.Card && !state.mediaBusy
    val gridValue: (AuthoringState) -> AuthoringAction.ChangeGrid? = { snapshot ->
        val actualPage = snapshot.draft?.document?.pages?.firstOrNull { it.id == pageId }
        val actualForm = (snapshot.draft?.form as? EditorForm.Grid)?.takeIf { it.pageId == pageId }
        actualPage?.let { AuthoringAction.ChangeGrid(actualForm?.rowsText ?: it.rows.toString(), actualForm?.columnsText ?: it.columns.toString(), it.id) }
    }
    val grid = remember(ownerId, epoch, draftId, pageId) {
        gridValue(state)?.let { BufferedAuthoringInput(it, state.editVersion) }
    }
    SideEffect { gridValue(state)?.let { grid?.acceptExternal(it, state.editVersion) } }
    val changeGrid: (AuthoringAction.ChangeGrid) -> Unit = { next ->
        if (grid != null) {
            onGridChange()
            val token = grid.edit(next)
            dispatchEdited(next) { accepted ->
                if (accepted.ownerId == ownerId && accepted.epoch == epoch && accepted.draft?.id == draftId) {
                    val payload = gridValue(accepted)
                    if (payload != null) scope.launch(Dispatchers.Main.immediate) {
                        grid.handled(token, payload, accepted.editVersion)
                    }
                }
            }
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val canDismiss = editorSettingsCanDismiss(draft.form, input.pending, grid?.pending == true)
    val details = input.value
    AlertDialog(onDismissRequest = { if (canDismiss) onDismiss() }, title = { Text(stringResource(Res.string.board_options)) },
        properties = su.linka.pictures.ui.app.productDialogProperties(),
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthoringFeedback(state, dispatch, showSaveStatus = state.savePhase == DraftSavePhase.FAILED || state.savePhase == DraftSavePhase.UNCONFIRMED)
                if (grid != null) {
                    Text(stringResource(Res.string.current_page_grid_size))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(grid.value.rowsText, { changeGrid(grid.value.copy(rowsText = it)) },
                            label = { Text(stringResource(Res.string.rows)) }, modifier = Modifier.weight(1f), enabled = gridEnabled,
                            singleLine = true, colors = authoringFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        OutlinedTextField(grid.value.columnsText, { changeGrid(grid.value.copy(columnsText = it)) },
                            label = { Text(stringResource(Res.string.columns)) }, modifier = Modifier.weight(1f), enabled = gridEnabled,
                            singleLine = true, colors = authoringFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    OutlinedButton({
                        if (!grid.pending) {
                            focus.clearFocus(); keyboard?.hide()
                            dispatch(AuthoringAction.SaveGrid)
                        }
                    }, enabled = gridEnabled && !grid.pending) { Text(stringResource(Res.string.resize_grid)) }
                    if (gridForm != null) TextButton({
                        focus.clearFocus(); keyboard?.hide()
                        grid.discardPending()
                        dispatch(AuthoringAction.CancelGrid)
                    }, enabled = gridEnabled) { Text(stringResource(Res.string.cancel_grid_changes)) }
                    Divider()
                }
                OutlinedTextField(details.name,
                    { change(input.value.copy(name = it)) },
                    label = { Text(stringResource(Res.string.board_name)) }, enabled = enabled, colors = authoringFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(details.description.orEmpty(),
                    { change(input.value.copy(description = it)) },
                    label = { Text(stringResource(Res.string.description)) }, enabled = enabled, colors = authoringFieldColors(), modifier = Modifier.fillMaxWidth())
                Text(stringResource(Res.string.default_mode))
                InteractionMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(details.behavior.defaultMode == mode, enabled = enabled, role = Role.RadioButton,
                        onClick = { change(input.value.copy(behavior = input.value.behavior.copy(defaultMode = mode))) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(details.behavior.defaultMode == mode, onClick = null, enabled = enabled)
                        Text(if (mode == InteractionMode.COMPOSE) stringResource(Res.string.composing_a_message) else stringResource(Res.string.speak_immediately_249))
                    }
                }
                Text(stringResource(Res.string.input_style))
                InputStyle.entries.forEach { style ->
                    Row(Modifier.fillMaxWidth().selectable(details.behavior.inputStyle == style, enabled = enabled, role = Role.RadioButton,
                        onClick = { change(input.value.copy(behavior = input.value.behavior.copy(inputStyle = style))) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(details.behavior.inputStyle == style, onClick = null, enabled = enabled)
                        Text(if (style == InputStyle.CARDS) stringResource(Res.string.cards) else stringResource(Res.string.literal_label_text))
                    }
                }
            }
        }, confirmButton = { TextButton({ if (enabled && canDismiss) onDismiss() }, enabled = enabled && canDismiss) { Text(stringResource(Res.string.done)) } })
}
