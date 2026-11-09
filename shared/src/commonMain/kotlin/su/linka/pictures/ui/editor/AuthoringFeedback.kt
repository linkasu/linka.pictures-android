package su.linka.pictures.ui.editor

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
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
        if (showSaveStatus) Text(stringResource(draftSaveLabel(state.savePhase)), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        state.problem?.let {
            Text(stringResource(authoringProblemLabel(it.code), it.code.name), color = palette.danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.cleanupProblem != null) Text(stringResource(Res.string.could_not_release_all_resources_io_failure),
            color = palette.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.savePhase == DraftSavePhase.FAILED && state.editable()) {
            LabeledActionButton(stringResource(Res.string.retry_saving_draft), true, false, { onAction(AuthoringAction.RetrySave) })
        }
        if (state.phase == AuthoringPhase.PAUSED) {
            Text(stringResource(Res.string.editor_paused_an_unfinished_write_is_not))
            LabeledActionButton(stringResource(Res.string.continue_after_assistant_sign_in), true, false, { onAction(AuthoringAction.Resume) })
        }
        if (state.phase == AuthoringPhase.APPLIED) Text(stringResource(Res.string.changes_applied_to_the_board))
        if (state.mediaBusy) Text(stringResource(Res.string.preparing_media_cancelling_does_not_delete_files))
    }
}
