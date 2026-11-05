package su.linka.pictures.ui.communication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.communication.CommunicationCommand
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.application.ports.IdSource
import su.linka.pictures.domain.CommandId
import su.linka.pictures.domain.InteractionMode
import su.linka.pictures.ui.components.LabeledActionButton
import su.linka.pictures.ui.theme.LocalLinkaPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommunicationControls(
    state: CommunicationState,
    presentation: CommunicationPresentation,
    ids: IdSource,
    onCommand: (CommunicationCommand) -> Unit,
    onOpenLibrary: () -> Unit,
    input: String,
    onInputChange: (String) -> Unit,
    modifier: Modifier,
    showPageButtons: Boolean,
    onInputFocusChange: (Boolean) -> Unit,
) {
    val palette = LocalLinkaPalette.current
    val scroll = rememberScrollState()
    LaunchedEffect(presentation.saveStatus, presentation.commandsEnabled) {
        if (!presentation.commandsEnabled) scroll.scrollTo(0)
    }
    fun id() = CommandId(ids.next())
    fun changePage(direction: Int): Boolean {
        val pages = state.board?.document?.pages.orEmpty()
        val target = pageTarget(presentation.pageIndex, pages.size, direction, presentation.commandsEnabled) ?: return false
        onCommand(CommunicationCommand.ChangePage(id(), pages[target].id))
        return true
    }
    Column(
        modifier.verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(presentation.saveStatus, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        for (problem in presentation.problems) Text(problem, color = palette.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        LabeledActionButton("Открыть библиотеку", presentation.commandsEnabled, false, onOpenLibrary)
        Text(state.board?.document?.name ?: "Доска не открыта", style = MaterialTheme.typography.h6)
        Text(
            if (presentation.composing) "Режим: составление общего сообщения."
            else "Прямой режим: выбор произносит только карточку. Общее сообщение сохраняется.",
            style = MaterialTheme.typography.subtitle1,
        )
        LabeledActionButton(
            if (presentation.composing) "Перейти в прямой режим" else "Составлять сообщение",
            presentation.commandsEnabled, false,
            { onCommand(CommunicationCommand.SetMode(id(), if (presentation.composing) InteractionMode.DIRECT else InteractionMode.COMPOSE)) },
        )
        val pages = state.board?.document?.pages.orEmpty()
        if (presentation.page != null) {
            Text("Страница ${presentation.pageIndex + 1} из ${pages.size}", modifier = Modifier.semantics {
                customActions = listOfNotNull(
                    if (pageTarget(presentation.pageIndex, pages.size, -1, presentation.commandsEnabled) != null)
                        CustomAccessibilityAction("Предыдущая страница") { changePage(-1) } else null,
                    if (pageTarget(presentation.pageIndex, pages.size, 1, presentation.commandsEnabled) != null)
                        CustomAccessibilityAction("Следующая страница") { changePage(1) } else null,
                )
            })
            if (showPageButtons) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabeledActionButton(
                    "Предыдущая страница", presentation.commandsEnabled && presentation.pageIndex > 0, false,
                    { pages.getOrNull(presentation.pageIndex - 1)?.let { onCommand(CommunicationCommand.ChangePage(id(), it.id)) } },
                )
                LabeledActionButton(
                    "Следующая страница", presentation.commandsEnabled && presentation.pageIndex < pages.lastIndex, false,
                    { pages.getOrNull(presentation.pageIndex + 1)?.let { onCommand(CommunicationCommand.ChangePage(id(), it.id)) } },
                )
            }
        }
        Text(presentation.audioStatus, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(presentation.voiceStatus)
        if (presentation.composing) {
            DisposableEffect(Unit) { onDispose { onInputFocusChange(false) } }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                enabled = presentation.commandsEnabled,
                label = { Text("Текст для добавления") },
                minLines = 2, maxLines = 5,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp, max = 160.dp)
                    .onFocusChanged { onInputFocusChange(it.isFocused) },
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    textColor = palette.text, disabledTextColor = palette.disabledText,
                    backgroundColor = palette.surface, cursorColor = palette.primary,
                    focusedBorderColor = palette.focus, unfocusedBorderColor = palette.border,
                    disabledBorderColor = palette.border, focusedLabelColor = palette.text,
                    unfocusedLabelColor = palette.secondaryText, disabledLabelColor = palette.disabledText,
                ),
            )
            Text("Текст добавляется только кнопкой и остаётся в поле до явной очистки.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabeledActionButton(
                    "Добавить текст в сообщение", presentation.commandsEnabled && input.isNotEmpty(), false,
                    { onCommand(CommunicationCommand.InsertText(id(), input)) },
                )
                LabeledActionButton("Очистить поле ввода", presentation.commandsEnabled && input.isNotEmpty(), false, { onInputChange("") })
            }
        }
    }
}
