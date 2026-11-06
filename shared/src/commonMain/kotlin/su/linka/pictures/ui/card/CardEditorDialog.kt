package su.linka.pictures.ui.card

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.ports.AudioPhase
import su.linka.pictures.application.ports.AudioPurpose
import su.linka.pictures.domain.*
import su.linka.pictures.ui.editor.AuthoringFeedback
import su.linka.pictures.ui.editor.BufferedAuthoringInput
import su.linka.pictures.ui.editor.authoringFieldColors
import su.linka.pictures.ui.editor.authoringProblemLabel
import su.linka.pictures.ui.editor.editable
import su.linka.pictures.ui.images.DemandVisibleImages
import su.linka.pictures.ui.theme.LocalLinkaPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardEditorDialog(
    state: AuthoringState,
    ownerId: String,
    dispatch: (AuthoringAction) -> Unit,
    dispatchEdited: (AuthoringAction, (AuthoringState) -> Unit) -> Unit,
    images: Map<AssetId, ImageBitmap>,
    modifier: Modifier = Modifier,
) {
    val form = state.draft?.form as? EditorForm.Card ?: return
    val enabled = state.editable()
    val showContent = cardContentVisible(form)
    val palette = LocalLinkaPalette.current
    val focus = LocalFocusManager.current
    var speechMenu by remember { mutableStateOf(false) }
    var moreAudio by remember { mutableStateOf(false) }
    val draftId = state.draft.id
    val epoch = state.epoch
    val pageId = form.pageId
    val cardId = form.cardId
    val input = remember(ownerId, epoch, draftId, state.phase, pageId, cardId) {
        BufferedAuthoringInput(AuthoringAction.ChangeCard(form.label, form.speechText, form.speechChoice, form.useLabelForSpeech), state.editVersion)
    }
    val scope = rememberCoroutineScope()
    SideEffect { input.acceptExternal(AuthoringAction.ChangeCard(form.label, form.speechText, form.speechChoice, form.useLabelForSpeech), state.editVersion) }
    val change: (AuthoringAction.ChangeCard) -> Unit = { next ->
        val token = input.edit(next)
        dispatchEdited(next) { accepted ->
            val actual = accepted.draft?.form as? EditorForm.Card
            if (accepted.ownerId == ownerId && accepted.epoch == epoch && accepted.draft?.id == draftId &&
                actual?.pageId == pageId && actual.cardId == cardId) {
                val payload = AuthoringAction.ChangeCard(actual.label, actual.speechText, actual.speechChoice, actual.useLabelForSpeech)
                val editVersion = accepted.editVersion
                scope.launch(Dispatchers.Main.immediate) {
                    input.handled(token, payload, editVersion)
                }
            }
        }
    }
    val content = input.value
    val cancel = { input.discardPending(); dispatch(AuthoringAction.CancelCard) }
    DemandVisibleImages(form.cardId, cardImageDemand(form))
    Dialog(onDismissRequest = { if (enabled) cancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(640.dp)),
                color = palette.surface, contentColor = palette.text, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Тип карточки", modifier = Modifier.semantics { heading() })
                        FlowRow(Modifier.selectableGroup()) {
                            CellKind.entries.forEach { kind ->
                                Row(Modifier.heightIn(min = 48.dp).selectable(form.kind == kind, enabled = enabled, role = Role.RadioButton,
                                    onClick = { focus.clearFocus(); dispatch(AuthoringAction.ChangeCardKind(kind)) }),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(form.kind == kind, onClick = null, enabled = enabled)
                                    Text(when (kind) {
                                        CellKind.CONTENT -> "Стандартная карточка"
                                        CellKind.SPACE -> "Пробел"
                                        CellKind.EMPTY -> "Пустая карточка"
                                    })
                                }
                            }
                        }
                        AuthoringFeedback(state, dispatch, showSaveStatus = state.savePhase == DraftSavePhase.FAILED || state.savePhase == DraftSavePhase.UNCONFIRMED)
                        if (showContent) {
                            OutlinedTextField(content.label, { change(input.value.copy(label = it)) },
                                label = { Text("Название карточки") }, enabled = enabled, colors = authoringFieldColors(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                                modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).height(120.dp), contentAlignment = Alignment.Center) {
                                    form.image?.let { images[it] }?.let { Image(it, "Изображение карточки", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                                        ?: Text(if (form.image == null) "Нет изображения" else "Предпросмотр загружается")
                                }
                                Column(Modifier.weight(1f)) {
                                    Button({ dispatch(AuthoringAction.PickImage) }, enabled = enabled && !state.mediaBusy, modifier = Modifier.fillMaxWidth()) { Text("Выбрать изображение") }
                                    Button({ if (!input.pending) dispatch(AuthoringAction.ImageFromText(input.value.label)) }, enabled = enabled && !input.pending && !state.mediaBusy && content.label.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Из текста") }
                                    if (form.image != null) TextButton({ dispatch(AuthoringAction.RemoveImage) }, enabled = enabled) { Text("Убрать изображение") }
                                }
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button({ if (!input.pending) dispatch(AuthoringAction.Preview) }, enabled = enabled && !input.pending && !state.mediaBusy && content.speechChoice != SpeechChoice.NONE) { Text("Прослушать") }
                                Button({ if (!input.pending) dispatch(AuthoringAction.Synthesize) }, enabled = enabled && !input.pending && !state.mediaBusy &&
                                    (if (content.useLabelForSpeech) content.label else content.speechText).isNotBlank()) { Text("Звук из текста") }
                                Button({ dispatch(if (state.audio.phase == AudioPhase.RECORDING) AuthoringAction.FinishRecording else AuthoringAction.Record) },
                                    enabled = enabled && (!state.mediaBusy || state.audio.phase == AudioPhase.RECORDING)) {
                                    Text(if (state.audio.phase == AudioPhase.RECORDING) "Завершить запись" else "Запись звука")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    TextButton({ speechMenu = true }, enabled = enabled) { Text(when (content.speechChoice) {
                                        SpeechChoice.TTS -> "Локальный TTS ▾"
                                        SpeechChoice.RECORDING -> "Аудиозапись ▾"
                                        SpeechChoice.NONE -> "Без звука ▾"
                                    }) }
                                    DropdownMenu(speechMenu, { speechMenu = false }) {
                                        SpeechChoice.entries.forEach { choice ->
                                            DropdownMenuItem({ speechMenu = false; change(input.value.copy(speechChoice = choice)) }) {
                                                Text(when (choice) { SpeechChoice.TTS -> "Локальный TTS"; SpeechChoice.RECORDING -> "Аудиозапись"; SpeechChoice.NONE -> "Без звука" })
                                            }
                                        }
                                    }
                                }
                                TextButton({ moreAudio = !moreAudio }) { Text(if (moreAudio) "Скрыть параметры звука" else "Параметры звука") }
                            }
                            if (state.audio.owner?.purpose == AudioPurpose.PREVIEW && state.audio.phase in setOf(AudioPhase.PREPARING, AudioPhase.PLAYING))
                                TextButton({ dispatch(AuthoringAction.StopPreview) }, enabled = enabled) { Text("Остановить прослушивание") }
                            if (moreAudio) {
                                Row(Modifier.fillMaxWidth().toggleable(content.useLabelForSpeech, enabled = enabled, role = Role.Checkbox,
                                    onValueChange = { change(input.value.copy(useLabelForSpeech = it)) }), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(content.useLabelForSpeech, onCheckedChange = null, enabled = enabled)
                                    Text("Использовать название для речи")
                                }
                                if (!content.useLabelForSpeech) OutlinedTextField(content.speechText,
                                    { change(input.value.copy(speechText = it)) }, label = { Text("Текст речи") },
                                    enabled = enabled, colors = authoringFieldColors(), modifier = Modifier.fillMaxWidth())
                                Text(if (form.recording == null) "Аудиозапись не выбрана" else "Аудиозапись подготовлена")
                                TextButton({ dispatch(AuthoringAction.PickAudio) }, enabled = enabled && !state.mediaBusy) { Text("Выбрать аудиофайл") }
                                val source = state.draft.document.pages.firstOrNull { it.id == form.pageId }?.cells
                                    ?.firstOrNull { it.card?.id == form.cardId }?.card?.speech
                                (source as? SpeechSource.Tts)?.languageTag?.let { Text("Язык исходной речи: $it (сохраняется)") }
                            }
                        } else Text(if (form.kind == CellKind.SPACE) "Добавляет пробел без изображения и звука." else "Пустая ячейка без изображения и звука.")
                        state.audio.problem?.let { Text(authoringProblemLabel(it.code), color = palette.danger,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                        if (state.mediaBusy) TextButton({ dispatch(AuthoringAction.CancelMedia) }, enabled = enabled) { Text("Отменить подготовку медиа") }
                        Text("Готово — принять карточку в черновик. Затем сохраните доску в верхней панели редактора.", style = MaterialTheme.typography.caption)
                    }
                    Divider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton({ focus.clearFocus(); cancel() }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Отмена") }
                        TextButton({ if (!input.pending) { focus.clearFocus(); dispatch(AuthoringAction.SaveCard) } }, enabled = enabled && !input.pending && !state.mediaBusy, modifier = Modifier.weight(1f)) { Text("Готово") }
                    }
                }
            }
        }
    }
}
