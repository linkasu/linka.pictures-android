package su.linka.pictures.ui.communication

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import su.linka.pictures.application.communication.CommunicationCommand
import su.linka.pictures.application.ports.IdSource
import su.linka.pictures.domain.CommandId
import su.linka.pictures.domain.InteractionMode
import su.linka.pictures.ui.theme.LocalLinkaPalette
import su.linka.pictures.ui.theme.LinkaPalette
import su.linka.pictures.ui.theme.linkaPalette

internal fun communicationActionSurface(palette: LinkaPalette): Color =
    if (palette == linkaPalette(false, false)) Color(0xFFD6D7D7) else palette.disabledSurface

@Composable
internal fun CommunicationControls(
    title: String,
    presentation: CommunicationPresentation,
    ids: IdSource,
    onCommand: (CommunicationCommand) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenBoardSettings: (() -> Unit)?,
    input: String,
    onInputChange: (String) -> Unit,
    onInputFocusChange: (Boolean) -> Unit,
    toolbarHeight: Dp,
) {
    val palette = LocalLinkaPalette.current
    var menu by remember { mutableStateOf(false) }
    var enteringText by rememberSaveable { mutableStateOf(false) }
    fun id() = CommandId(ids.next())
    TopAppBar(
        modifier = Modifier.height(toolbarHeight),
        title = { Text(title, modifier = Modifier.padding(start = 8.dp)) },
        backgroundColor = palette.primary, contentColor = palette.onPrimary,
        navigationIcon = { CommunicationIconButton(CommunicationIcon.Back, "Открыть библиотеку", presentation.commandsEnabled, onOpenLibrary,
            Modifier.padding(start = 8.dp)) },
        actions = {
            if (onOpenBoardSettings != null) CommunicationIconButton(
                CommunicationIcon.Settings, "Настройки доски", presentation.commandsEnabled, onOpenBoardSettings,
            )
            Box {
                CommunicationIconButton(CommunicationIcon.Menu, "Меню общения", presentation.commandsEnabled, { menu = true },
                    Modifier.semantics { stateDescription = if (presentation.composing) "Составление сообщения" else "Прямой режим" })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(enabled = presentation.commandsEnabled, onClick = {
                        menu = false
                        onCommand(CommunicationCommand.SetMode(id(), if (presentation.composing) InteractionMode.DIRECT else InteractionMode.COMPOSE))
                    }) { Text(if (presentation.composing) "Перейти в прямой режим" else "Составлять сообщение") }
                    if (presentation.composing) DropdownMenuItem(enabled = presentation.commandsEnabled, onClick = { menu = false; enteringText = true }) { Text("Добавить текст") }
                    DropdownMenuItem(enabled = presentation.undoEnabled, onClick = {
                        menu = false; onCommand(CommunicationCommand.UndoClear(id()))
                    }) { Text("Отменить очистку сообщения") }
                }
            }
        },
    )
    if (enteringText && presentation.composing) {
        DisposableEffect(Unit) { onDispose { onInputFocusChange(false) } }
        AlertDialog(
            onDismissRequest = { enteringText = false },
            title = { Text("Добавить текст") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(input, onInputChange, enabled = presentation.commandsEnabled,
                        label = { Text("Текст для добавления") }, minLines = 2, maxLines = 5,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { onInputFocusChange(it.isFocused) })
                    TextButton(onClick = { onInputChange("") }, enabled = presentation.commandsEnabled && input.isNotEmpty()) { Text("Очистить поле ввода") }
                }
            },
            confirmButton = { TextButton(enabled = presentation.commandsEnabled && input.isNotEmpty(), onClick = {
                onCommand(CommunicationCommand.InsertText(id(), input)); enteringText = false
            }) { Text("Добавить") } },
            dismissButton = { TextButton(onClick = { enteringText = false }) { Text("Отмена") } },
        )
    }
}
