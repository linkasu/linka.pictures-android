package su.linka.pictures.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.authoring.*
import su.linka.pictures.domain.EditorForm

@Composable
internal fun EditorGridDialog(form: EditorForm.Grid, state: AuthoringState, dispatch: (AuthoringAction) -> Unit) {
    AlertDialog(onDismissRequest = { if (state.editable()) dispatch(AuthoringAction.CancelGrid) },
        title = { Text("Размер сетки") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthoringFeedback(state, dispatch, showSaveStatus = state.savePhase == DraftSavePhase.FAILED || state.savePhase == DraftSavePhase.UNCONFIRMED)
                OutlinedTextField(form.rowsText, { dispatch(AuthoringAction.ChangeGrid(it, form.columnsText)) },
                    label = { Text("Строки (1–64)") }, enabled = state.editable(), colors = authoringFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(form.columnsText, { dispatch(AuthoringAction.ChangeGrid(form.rowsText, it)) },
                    label = { Text("Столбцы (1–64)") }, enabled = state.editable(), colors = authoringFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Text("Не поместившиеся ячейки, включая пустые с метаданными, перейдут на новые страницы. Предел — 256 страниц; данные не обрезаются.")
            }
        }, confirmButton = { TextButton({ dispatch(AuthoringAction.SaveGrid) }, enabled = state.editable()) { Text("Готово") } },
        dismissButton = { TextButton({ dispatch(AuthoringAction.CancelGrid) }, enabled = state.editable()) { Text("Отмена") } })
}
