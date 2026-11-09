package su.linka.pictures.ui.card

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import su.linka.pictures.application.authoring.*
import su.linka.pictures.domain.EditorForm
import su.linka.pictures.ui.app.ProductDialog
import su.linka.pictures.ui.editor.editorCellLabel
import su.linka.pictures.ui.editor.editable

/** Compact-host navigation uses the same buffered, durable SwitchCard as the inspector. */
@Composable
internal fun CardCellSelector(state: AuthoringState, input: CardEditorInput) {
    val draft = state.draft ?: return
    val form = draft.form as? EditorForm.Card ?: return
    var open by remember(form.cardId) { mutableStateOf(false) }
    val enabled = state.editable() && !input.requestingSwitch && !state.mediaBusy && state.problem == null
    val focus = LocalFocusManager.current
    OutlinedButton({ focus.clearFocus(); open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.editor_choose_card, form.slotIndex + 1))
    }
    if (open) ProductDialog(stringResource(Res.string.editor_select_card), { open = false }, actions = {
        TextButton({ open = false }) { Text(stringResource(Res.string.cancel)) }
    }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
            draft.document.pages.forEachIndexed { pageIndex, page ->
                item(key = "page:${page.id.value}") {
                    Text(stringResource(Res.string.page_1_s_of_2_s, pageIndex + 1, draft.document.pages.size), style = MaterialTheme.typography.subtitle1)
                }
                val cells = page.cells.associateBy { it.position }
                items(page.capacity, key = { "cell:${page.id.value}:$it" }) { position ->
                    val cell = cells[position]
                    val label = editorCellLabel(cell)?.let { stringResource(it) } ?: cell?.card?.label.orEmpty()
                    val current = form.pageId == page.id && form.slotIndex == position
                    TextButton({
                        open = false
                        input.switchCard(AuthoringAction.SwitchCard(page.id, position))
                    }, enabled = enabled && !current, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { selected = current }) {
                        Text("${position + 1}. $label")
                    }
                }
            }
        }
    }
}
