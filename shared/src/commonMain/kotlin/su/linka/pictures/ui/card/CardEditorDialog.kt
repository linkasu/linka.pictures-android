package su.linka.pictures.ui.card

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.ports.AudioPhase
import su.linka.pictures.application.ports.AudioPurpose
import su.linka.pictures.domain.*
import su.linka.pictures.ui.editor.AuthoringFeedback
import su.linka.pictures.ui.editor.authoringFieldColors
import su.linka.pictures.ui.editor.authoringProblemLabel
import su.linka.pictures.ui.editor.editable
import su.linka.pictures.ui.images.DemandVisibleImages
import su.linka.pictures.ui.theme.LocalLinkaPalette
import su.linka.pictures.ui.app.productDialogProperties

@Composable
internal fun CardEditorDialog(
    state: AuthoringState,
    ownerId: String,
    dispatch: (AuthoringAction) -> Unit,
    dispatchEdited: (AuthoringAction, (AuthoringState) -> Unit) -> Unit,
    images: Map<AssetId, ImageBitmap>,
    modifier: Modifier = Modifier,
    input: CardEditorInput? = rememberCardEditorInput(state, ownerId, dispatchEdited),
) {
    input ?: return
    val enabled = state.editable() && input?.requestingSwitch != true
    val palette = LocalLinkaPalette.current
    val cancel = { input.discardPending(); dispatch(AuthoringAction.CancelCard) }
    Dialog(onDismissRequest = { if (enabled) cancel() },
        properties = productDialogProperties()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(640.dp)),
                color = palette.surface, contentColor = palette.text, shape = MaterialTheme.shapes.medium) {
                CardEditorContent(state, input, dispatch, images, Modifier.padding(16.dp), showCellSelector = true)
            }
        }
    }
}

/** Shared form and pinned actions; its input owner must live above dialog/inspector branches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardEditorContent(
    state: AuthoringState,
    input: CardEditorInput,
    dispatch: (AuthoringAction) -> Unit,
    images: Map<AssetId, ImageBitmap>,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    showCellSelector: Boolean = false,
) {
    val form = state.draft?.form as? EditorForm.Card ?: return
    val enabled = state.editable() && !input.requestingSwitch
    val showContent = cardContentVisible(form)
    val palette = LocalLinkaPalette.current
    val focus = LocalFocusManager.current
    var speechMenu by remember { mutableStateOf(false) }
    var moreAudio by remember { mutableStateOf(false) }
    val content = input.value
    val change = input.change
    val cancel = { input.discardPending(); dispatch(AuthoringAction.CancelCard) }
    DemandVisibleImages(form.cardId, cardImageDemand(form))
    Column(modifier) {
        if (showCellSelector) CardCellSelector(state, input)
        Column(Modifier.weight(1f, fill = fillHeight).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!showCellSelector) Text(stringResource(Res.string.card_1_s, form.slotIndex + 1), style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
            Text(stringResource(Res.string.card_type))
            FlowRow(Modifier.selectableGroup()) {
                CellKind.entries.forEach { kind ->
                    Row(Modifier.heightIn(min = 48.dp).selectable(form.kind == kind, enabled = enabled, role = Role.RadioButton,
                        onClick = { focus.clearFocus(); dispatch(AuthoringAction.ChangeCardKind(kind)) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(form.kind == kind, onClick = null, enabled = enabled)
                        Text(when (kind) {
                            CellKind.CONTENT -> stringResource(Res.string.standard_card)
                            CellKind.SPACE -> stringResource(Res.string.space)
                            CellKind.EMPTY -> stringResource(Res.string.empty_card)
                        })
                    }
                }
            }
            AuthoringFeedback(state, dispatch, showSaveStatus = state.savePhase == DraftSavePhase.FAILED || state.savePhase == DraftSavePhase.UNCONFIRMED)
            if (showContent) {
                OutlinedTextField(content.label, { change(input.value.copy(label = it)) },
                    label = { Text(stringResource(Res.string.card_name)) }, enabled = enabled, colors = authoringFieldColors(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth())
                Text(stringResource(Res.string.image), style = MaterialTheme.typography.subtitle1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(120.dp), contentAlignment = Alignment.Center) {
                        form.image?.let { images[it] }?.let { Image(it, stringResource(Res.string.card_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                            ?: Text(if (form.image == null) stringResource(Res.string.no_image) else stringResource(Res.string.loading_preview))
                    }
                    Column(Modifier.weight(1f)) {
                        OutlinedButton({ dispatch(AuthoringAction.PickImage) }, enabled = enabled && !state.mediaBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.choose_image)) }
                        OutlinedButton({ if (!input.pending) dispatch(AuthoringAction.ImageFromText(input.value.label)) }, enabled = enabled && !input.pending && !state.mediaBusy && content.label.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.from_text)) }
                        if (form.image != null) TextButton({ dispatch(AuthoringAction.RemoveImage) }, enabled = enabled) { Text(stringResource(Res.string.remove_image)) }
                    }
                }
                Text(stringResource(Res.string.audio), style = MaterialTheme.typography.subtitle1)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ if (!input.pending) dispatch(AuthoringAction.Preview) }, enabled = enabled && !input.pending && !state.mediaBusy && content.speechChoice != SpeechChoice.NONE) { Text(stringResource(Res.string.preview)) }
                    OutlinedButton({ if (!input.pending) dispatch(AuthoringAction.Synthesize) }, enabled = enabled && !input.pending && !state.mediaBusy &&
                        (if (content.useLabelForSpeech) content.label else content.speechText).isNotBlank()) { Text(stringResource(Res.string.audio_from_text)) }
                    OutlinedButton({ dispatch(if (state.audio.phase == AudioPhase.RECORDING) AuthoringAction.FinishRecording else AuthoringAction.Record) },
                        enabled = enabled && (!state.mediaBusy || state.audio.phase == AudioPhase.RECORDING)) {
                        Text(if (state.audio.phase == AudioPhase.RECORDING) stringResource(Res.string.finish_recording) else stringResource(Res.string.record_audio))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TextButton({ speechMenu = true }, enabled = enabled) { Text(when (content.speechChoice) {
                            SpeechChoice.TTS -> stringResource(Res.string.local_tts)
                            SpeechChoice.RECORDING -> stringResource(Res.string.audio_recording)
                            SpeechChoice.NONE -> stringResource(Res.string.no_audio)
                        }) }
                        DropdownMenu(speechMenu, { speechMenu = false }) {
                            SpeechChoice.entries.forEach { choice ->
                                DropdownMenuItem({ speechMenu = false; change(input.value.copy(speechChoice = choice)) }, enabled = enabled) {
                                    Text(when (choice) { SpeechChoice.TTS -> stringResource(Res.string.local_tts_80); SpeechChoice.RECORDING -> stringResource(Res.string.audio_recording_81); SpeechChoice.NONE -> stringResource(Res.string.no_audio_82) })
                                }
                            }
                        }
                    }
                    TextButton({ moreAudio = !moreAudio }) { Text(if (moreAudio) stringResource(Res.string.hide_audio_options) else stringResource(Res.string.audio_options)) }
                }
                if (state.audio.owner?.purpose == AudioPurpose.PREVIEW && state.audio.phase in setOf(AudioPhase.PREPARING, AudioPhase.PLAYING))
                    TextButton({ dispatch(AuthoringAction.StopPreview) }, enabled = enabled) { Text(stringResource(Res.string.stop_preview)) }
                if (moreAudio) {
                    Row(Modifier.fillMaxWidth().toggleable(content.useLabelForSpeech, enabled = enabled, role = Role.Checkbox,
                        onValueChange = { change(input.value.copy(useLabelForSpeech = it)) }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(content.useLabelForSpeech, onCheckedChange = null, enabled = enabled)
                        Text(stringResource(Res.string.use_name_for_speech))
                    }
                    if (!content.useLabelForSpeech) OutlinedTextField(content.speechText,
                        { change(input.value.copy(speechText = it)) }, label = { Text(stringResource(Res.string.speech_text)) },
                        enabled = enabled, colors = authoringFieldColors(), modifier = Modifier.fillMaxWidth())
                    Text(if (form.recording == null) stringResource(Res.string.no_recording_selected) else stringResource(Res.string.recording_prepared))
                    TextButton({ dispatch(AuthoringAction.PickAudio) }, enabled = enabled && !state.mediaBusy) { Text(stringResource(Res.string.choose_audio_file)) }
                    val source = state.draft.document.pages.firstOrNull { it.id == form.pageId }?.cells
                        ?.firstOrNull { it.card?.id == form.cardId }?.card?.speech
                    (source as? SpeechSource.Tts)?.languageTag?.let { Text(stringResource(Res.string.original_speech_language_1_s_preserved, it)) }
                }
            } else Text(if (form.kind == CellKind.SPACE) stringResource(Res.string.adds_a_space_without_an_image_or) else stringResource(Res.string.empty_cell_without_an_image_or_audio))
            state.audio.problem?.let { Text(stringResource(authoringProblemLabel(it.code), it.code.name), color = palette.danger,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (state.mediaBusy) TextButton({ dispatch(AuthoringAction.CancelMedia) }, enabled = enabled) { Text(stringResource(Res.string.cancel_media_preparation)) }
            Text(stringResource(Res.string.done_accepts_the_card_into_the_draft), style = MaterialTheme.typography.caption)
        }
        Divider(color = palette.border)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({ focus.clearFocus(); cancel() }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.cancel)) }
            TextButton({ if (!input.pending) { focus.clearFocus(); dispatch(AuthoringAction.SaveCard) } }, enabled = enabled && !input.pending && !state.mediaBusy, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.done)) }
        }
    }
}
