package su.linka.pictures.ui.communication

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.communication.CommunicationCommand
import su.linka.pictures.application.ports.IdSource
import su.linka.pictures.domain.CommandId
import su.linka.pictures.ui.components.LabeledActionButton

@Composable
internal fun CommunicationMessageActions(
    presentation: CommunicationPresentation,
    ids: IdSource,
    onCommand: (CommunicationCommand) -> Unit,
) {
    fun id() = CommandId(ids.next())
    // The toolbar stays put; narrow windows scroll actions horizontally instead of shrinking targets.
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LabeledActionButton(
            "Удалить последнее", presentation.editEnabled, false,
            { onCommand(CommunicationCommand.Backspace(id())) }, Modifier.widthIn(max = 240.dp),
        )
        LabeledActionButton(
            "Очистить сообщение", presentation.editEnabled, true,
            { onCommand(CommunicationCommand.Clear(id())) }, Modifier.widthIn(max = 240.dp),
        )
        LabeledActionButton(
            "Отменить очистку сообщения", presentation.undoEnabled, false,
            { onCommand(CommunicationCommand.UndoClear(id())) }, Modifier.widthIn(max = 240.dp),
        )
    }
}
