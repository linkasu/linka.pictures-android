package su.linka.pictures.ui.card

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.components.LabeledActionButton
import su.linka.pictures.ui.images.VisibleImageDemand
import su.linka.pictures.ui.images.DemandVisibleImages
import su.linka.pictures.ui.editor.AuthoringFeedback
import su.linka.pictures.ui.editor.editable
import su.linka.pictures.ui.editor.authoringFieldColors
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
fun CardEditorScreen(
    state: AuthoringState,
    ownerId: String,
    capability: AssistantCapability?,
    ids: IdSource,
    onCommand: (AuthoringCommand) -> Unit,
    images: Map<AssetId, ImageBitmap> = emptyMap(),
    modifier: Modifier = Modifier,
    onImagesNeeded: ((Set<AssetId>) -> Unit)? = null,
) {
    val form = state.draft?.form as? EditorForm.Card ?: return
    val enabled = state.editable()
    val palette = LocalLinkaPalette.current
    val fieldColors = authoringFieldColors()
    val dispatch: (AuthoringAction) -> Unit = { onCommand(AuthoringCommand(CommandId(ids.next()), ownerId, capability, it)) }
    val change: (String, String, SpeechChoice, Boolean) -> Unit = { label, text, choice, useLabel ->
        dispatch(AuthoringAction.ChangeCard(label, text, choice, useLabel))
    }
    VisibleImageDemand(Triple(state.epoch, ownerId, state.draft.id), onImagesNeeded) {
    DemandVisibleImages(form.cardId, setOfNotNull(form.image))
    Surface(modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
      Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Карточка", style = MaterialTheme.typography.h5, modifier = Modifier.semantics { heading() })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledActionButton("Сохранить карточку в черновик", enabled && !state.mediaBusy, false,
                    { dispatch(AuthoringAction.SaveCard) })
                LabeledActionButton("Отменить изменения карточки", enabled, true, { dispatch(AuthoringAction.CancelCard) })
                LabeledActionButton("Закрыть с черновиком", state.phase != AuthoringPhase.UNCONFIRMED, false,
                    { dispatch(AuthoringAction.Close) })
            }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuthoringFeedback(state, dispatch)
            OutlinedTextField(form.label, { change(it, form.speechText, form.speechChoice, form.useLabelForSpeech) },
                label = { Text("Подпись карточки") }, enabled = enabled, colors = fieldColors, modifier = Modifier.fillMaxWidth())
            Text("Подпись видна на карточке. Текст речи можно задать отдельно.")
            Text("Речь", style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
            SpeechChoice.entries.forEach { choice ->
                val label = when (choice) {
                    SpeechChoice.TTS -> "Локальный синтез речи"
                    SpeechChoice.RECORDING -> "Запись или аудиофайл"
                    SpeechChoice.NONE -> "Без звука"
                }
                LabeledActionButton((if (form.speechChoice == choice) "✓ " else "") + label, enabled, false,
                    { change(form.label, form.speechText, choice, form.useLabelForSpeech) }, Modifier.fillMaxWidth())
            }
            LabeledActionButton("Использовать подпись для речи: ${if (form.useLabelForSpeech) "да" else "нет"}", enabled, false,
                { change(form.label, form.speechText, form.speechChoice, !form.useLabelForSpeech) }, Modifier.fillMaxWidth())
            OutlinedTextField(form.speechText, { change(form.label, it, form.speechChoice, form.useLabelForSpeech) },
                label = { Text("Отдельный текст речи") }, enabled = enabled && !form.useLabelForSpeech,
                colors = fieldColors, modifier = Modifier.fillMaxWidth())
            val sourceLanguage = (state.draft.document.pages.flatMap { it.cells }.firstOrNull { it.card?.id == form.cardId }
                ?.card?.speech as? SpeechSource.Tts)?.languageTag
            if (sourceLanguage != null) Text("Язык исходной речи: $sourceLanguage (сохраняется)")
            Text(if (form.recording == null) "Аудиозапись не выбрана" else "Аудиозапись подготовлена")
            LabeledActionButton("Выбрать аудиофайл", enabled && !state.mediaBusy, false, { dispatch(AuthoringAction.PickAudio) }, Modifier.fillMaxWidth())
            LabeledActionButton("Начать запись", enabled && !state.mediaBusy, false, { dispatch(AuthoringAction.Record) }, Modifier.fillMaxWidth())
            LabeledActionButton("Завершить запись", enabled && state.audio.phase == AudioPhase.RECORDING, false,
                { dispatch(AuthoringAction.FinishRecording) }, Modifier.fillMaxWidth())
            LabeledActionButton("Сохранить синтезированную речь как аудио", enabled && !state.mediaBusy &&
                (if (form.useLabelForSpeech) form.label else form.speechText).isNotBlank(), false,
                { dispatch(AuthoringAction.Synthesize) }, Modifier.fillMaxWidth())
            LabeledActionButton("Прослушать", enabled && !state.mediaBusy && form.speechChoice != SpeechChoice.NONE, false,
                { dispatch(AuthoringAction.Preview) }, Modifier.fillMaxWidth())
            LabeledActionButton("Остановить прослушивание", enabled, false, { dispatch(AuthoringAction.StopPreview) }, Modifier.fillMaxWidth())
            if (state.mediaBusy) LabeledActionButton("Отменить выбор / запись / подготовку", enabled, false,
                { dispatch(AuthoringAction.CancelMedia) }, Modifier.fillMaxWidth())
            Text("Изображение", style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
            form.image?.let { asset ->
                images[asset]?.let { bitmap ->
                    Image(bitmap, "Изображение карточки", Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 240.dp), contentScale = ContentScale.Fit)
                } ?: Text("Изображение выбрано. Предпросмотр загружается.")
            } ?: Text("Изображение не выбрано")
            LabeledActionButton("Выбрать изображение", enabled && !state.mediaBusy, false, { dispatch(AuthoringAction.PickImage) }, Modifier.fillMaxWidth())
            LabeledActionButton("Создать изображение из подписи", enabled && !state.mediaBusy && form.label.isNotBlank(), false,
                { dispatch(AuthoringAction.ImageFromText(form.label)) }, Modifier.fillMaxWidth())
            Text("Это явная операция создания изображения. Подпись интерфейса остаётся обычным текстом.")
            LabeledActionButton("Убрать изображение", enabled && form.image != null, true, { dispatch(AuthoringAction.RemoveImage) }, Modifier.fillMaxWidth())
            Text("Это не применение к доске. После закрытия формы используйте «Применить к доске» в редакторе.")
        }
      }
    }
    }
}
