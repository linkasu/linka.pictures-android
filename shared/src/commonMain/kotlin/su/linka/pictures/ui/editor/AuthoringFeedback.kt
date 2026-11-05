package su.linka.pictures.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.authoring.*
import su.linka.pictures.ui.components.LabeledActionButton
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
internal fun AuthoringFeedback(state: AuthoringState, onAction: (AuthoringAction) -> Unit, showSaveStatus: Boolean = true) {
    val palette = LocalLinkaPalette.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showSaveStatus) Text(draftSaveLabel(state.savePhase), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        state.problem?.let {
            Text(authoringProblemLabel(it.code), color = palette.danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.savePhase == DraftSavePhase.FAILED && state.editable()) {
            LabeledActionButton("Повторить сохранение черновика", true, false, { onAction(AuthoringAction.RetrySave) })
        }
        if (state.phase == AuthoringPhase.PAUSED) {
            Text("Редактор приостановлен. Незавершённая запись не восстанавливается; после входа продолжите явно.")
            LabeledActionButton("Продолжить после входа помощника", true, false, { onAction(AuthoringAction.Resume) })
        }
        if (state.phase == AuthoringPhase.APPLIED) Text("Изменения применены к доске")
        if (state.mediaBusy) Text("Подготовка медиа… Отмена не удаляет уже сохранённые файлы библиотеки.")
    }
}
