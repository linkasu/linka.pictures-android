package su.linka.pictures.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.authoring.AuthoringAction
import su.linka.pictures.domain.*

@Composable
internal fun EditorSettingsDialog(
    document: BoardDocument,
    enabled: Boolean,
    dispatch: (AuthoringAction) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Параметры доски") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(document.name,
                    { dispatch(AuthoringAction.Details(it, document.description, document.behavior)) },
                    label = { Text("Название доски") }, enabled = enabled, colors = authoringFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(document.description.orEmpty(),
                    { dispatch(AuthoringAction.Details(document.name, it, document.behavior)) },
                    label = { Text("Описание") }, enabled = enabled, colors = authoringFieldColors(), modifier = Modifier.fillMaxWidth())
                Text("Режим по умолчанию")
                InteractionMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(document.behavior.defaultMode == mode, enabled = enabled, role = Role.RadioButton,
                        onClick = { dispatch(AuthoringAction.Details(document.name, document.description, document.behavior.copy(defaultMode = mode))) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(document.behavior.defaultMode == mode, onClick = null, enabled = enabled)
                        Text(if (mode == InteractionMode.COMPOSE) "Составление сообщения" else "Произносить сразу")
                    }
                }
                Text("Способ ввода")
                InputStyle.entries.forEach { style ->
                    Row(Modifier.fillMaxWidth().selectable(document.behavior.inputStyle == style, enabled = enabled, role = Role.RadioButton,
                        onClick = { dispatch(AuthoringAction.Details(document.name, document.description, document.behavior.copy(inputStyle = style))) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(document.behavior.inputStyle == style, onClick = null, enabled = enabled)
                        Text(if (style == InputStyle.CARDS) "Карточки" else "Буквальный текст подписей")
                    }
                }
            }
        }, confirmButton = { TextButton(onDismiss) { Text("Готово") } })
}
