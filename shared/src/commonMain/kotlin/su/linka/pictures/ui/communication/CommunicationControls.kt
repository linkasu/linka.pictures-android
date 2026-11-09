package su.linka.pictures.ui.communication

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
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
import su.linka.pictures.ui.theme.LinkaPalette
import su.linka.pictures.ui.app.ProductTopBar

internal fun communicationActionSurface(palette: LinkaPalette): Color =
    palette.navigationSurface

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
    val composingAMessageLabel = stringResource(Res.string.composing_a_message)
    val directModeLabel = stringResource(Res.string.direct_mode)
    var menu by remember { mutableStateOf(false) }
    var enteringText by rememberSaveable { mutableStateOf(false) }
    fun id() = CommandId(ids.next())
    ProductTopBar(
        modifier = Modifier.height(toolbarHeight),
        title = title,
        minimumRowHeight = 48.dp,
        navigationIcon = { CommunicationIconButton(CommunicationIcon.Back, stringResource(Res.string.open_library), true, onOpenLibrary,
            Modifier.padding(start = 8.dp)) },
        actions = {
            if (onOpenBoardSettings != null) CommunicationIconButton(
                CommunicationIcon.Settings, stringResource(Res.string.board_settings), presentation.commandsEnabled, onOpenBoardSettings,
            )
            Box {
                CommunicationIconButton(CommunicationIcon.Menu, stringResource(Res.string.communication_menu), presentation.commandsEnabled, { menu = true },
                    Modifier.semantics { stateDescription = if (presentation.composing) composingAMessageLabel else directModeLabel })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(enabled = presentation.commandsEnabled, onClick = {
                        menu = false
                        onCommand(CommunicationCommand.SetMode(id(), if (presentation.composing) InteractionMode.DIRECT else InteractionMode.COMPOSE))
                    }) { Text(if (presentation.composing) stringResource(Res.string.switch_to_direct_mode) else stringResource(Res.string.compose_a_message)) }
                    if (presentation.composing) DropdownMenuItem(enabled = presentation.commandsEnabled, onClick = { menu = false; enteringText = true }) { Text(stringResource(Res.string.add_text)) }
                    DropdownMenuItem(enabled = presentation.undoEnabled, onClick = {
                        menu = false; onCommand(CommunicationCommand.UndoClear(id()))
                    }) { Text(stringResource(Res.string.undo_clearing_the_message)) }
                }
            }
        },
    )
    if (enteringText && presentation.composing) {
        DisposableEffect(Unit) { onDispose { onInputFocusChange(false) } }
        AlertDialog(
            onDismissRequest = { enteringText = false },
            title = { Text(stringResource(Res.string.add_text)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(input, onInputChange, enabled = presentation.commandsEnabled,
                        label = { Text(stringResource(Res.string.text_to_add)) }, minLines = 2, maxLines = 5,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { onInputFocusChange(it.isFocused) })
                    TextButton(onClick = { onInputChange("") }, enabled = presentation.commandsEnabled && input.isNotEmpty()) { Text(stringResource(Res.string.clear_text_input)) }
                }
            },
            confirmButton = { TextButton(enabled = presentation.commandsEnabled && input.isNotEmpty(), onClick = {
                onCommand(CommunicationCommand.InsertText(id(), input)); enteringText = false
            }) { Text(stringResource(Res.string.add)) } },
            dismissButton = { TextButton(onClick = { enteringText = false }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}
