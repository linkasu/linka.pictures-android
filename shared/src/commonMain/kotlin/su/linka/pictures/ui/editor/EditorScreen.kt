package su.linka.pictures.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.card.CardEditorScreen
import su.linka.pictures.ui.components.LabeledActionButton
import su.linka.pictures.ui.images.VisibleImageDemand
import su.linka.pictures.ui.images.DemandVisibleImages
import su.linka.pictures.ui.images.visibleRowImages
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
fun EditorScreen(
    state: AuthoringState,
    ownerId: String,
    capability: AssistantCapability?,
    ids: IdSource,
    onCommand: (AuthoringCommand) -> Unit,
    images: Map<AssetId, ImageBitmap> = emptyMap(),
    modifier: Modifier = Modifier,
    onImagesNeeded: ((Set<AssetId>) -> Unit)? = null,
) {
    if (state.draft?.form is EditorForm.Card) {
        CardEditorScreen(state, ownerId, capability, ids, onCommand, images, modifier, onImagesNeeded)
        return
    }
    val dispatch: (AuthoringAction) -> Unit = { onCommand(AuthoringCommand(CommandId(ids.next()), ownerId, capability, it)) }
    val draft = state.draft
    val document = draft?.document
    val enabled = state.editable()
    val structural = enabled && draft?.form == null
    val palette = LocalLinkaPalette.current
    val fieldColors = authoringFieldColors()
    var selectedPage by rememberSaveable(draft?.id?.value) { mutableStateOf<String?>(null) }
    var moveSource by remember(draft?.id) { mutableStateOf<Pair<PageId, Int>?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable(draft?.id?.value) { mutableStateOf(false) }
    val page = document?.pages?.firstOrNull { it.id.value == selectedPage } ?: document?.pages?.firstOrNull()
    val pageIndex = document?.pages?.indexOf(page) ?: -1
    VisibleImageDemand(Triple(state.epoch, ownerId, draft?.id), onImagesNeeded) {
    Surface(modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
      Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Редактор доски", style = MaterialTheme.typography.h5, modifier = Modifier.semantics { heading() })
        Text(draftSaveLabel(state.savePhase), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (document != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledActionButton(if (showSettings) "К сетке" else "Параметры доски", true, false,
                { showSettings = !showSettings })
            LabeledActionButton("Применить к доске", structural && state.phase != AuthoringPhase.CONFLICT && !state.mediaBusy,
                false, { dispatch(AuthoringAction.Apply) })
            LabeledActionButton("Закрыть с черновиком", state.phase != AuthoringPhase.UNCONFIRMED,
                false, { dispatch(AuthoringAction.Close) })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                AuthoringFeedback(state, dispatch, showSaveStatus = false)
            }
            if (document == null) {
                item {
                    Text(if (state.phase == AuthoringPhase.LOADING) "Загрузка черновика…" else "Создайте доску или откройте доску / черновик из библиотеки.")
                    LabeledActionButton("Новая доска", state.phase == AuthoringPhase.IDLE, false,
                        { dispatch(AuthoringAction.NewBoard()) }, Modifier.fillMaxWidth())
                }
            } else {
              if (showSettings) {
                item {
                    OutlinedTextField(document.name,
                        { dispatch(AuthoringAction.Details(it, document.description, document.behavior)) },
                        label = { Text("Название доски") }, enabled = enabled, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                }
                item {
                    OutlinedTextField(document.description.orEmpty(),
                        { dispatch(AuthoringAction.Details(document.name, it, document.behavior)) },
                        label = { Text("Описание доски") }, enabled = enabled, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Режим по умолчанию", style = MaterialTheme.typography.h6)
                        InteractionMode.entries.forEach { mode ->
                            LabeledActionButton((if (document.behavior.defaultMode == mode) "✓ " else "") +
                                if (mode == InteractionMode.COMPOSE) "Составление сообщения" else "Произносить сразу", enabled, false,
                                { dispatch(AuthoringAction.Details(document.name, document.description, document.behavior.copy(defaultMode = mode))) },
                                Modifier.fillMaxWidth())
                        }
                        Text("Способ ввода", style = MaterialTheme.typography.h6)
                        InputStyle.entries.forEach { style ->
                            LabeledActionButton((if (document.behavior.inputStyle == style) "✓ " else "") +
                                if (style == InputStyle.CARDS) "Карточки" else "Буквальный текст подписей", enabled, false,
                                { dispatch(AuthoringAction.Details(document.name, document.description, document.behavior.copy(inputStyle = style))) },
                                Modifier.fillMaxWidth())
                        }
                    }
                }
              }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Страница ${pageIndex + 1} из ${document.pages.size}", style = MaterialTheme.typography.h6,
                            modifier = Modifier.semantics { heading() })
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LabeledActionButton("Предыдущая", pageIndex > 0, false,
                                { selectedPage = document.pages[pageIndex - 1].id.value })
                            LabeledActionButton("Следующая", pageIndex < document.pages.lastIndex, false,
                                { selectedPage = document.pages[pageIndex + 1].id.value })
                            LabeledActionButton("Добавить страницу", structural, false,
                                { dispatch(AuthoringAction.AddPage(page?.rows ?: 3, page?.columns ?: 3)) })
                        }
                        if (page != null && showSettings) {
                            LabeledActionButton("Переместить страницу раньше", structural && pageIndex > 0, false,
                                { dispatch(AuthoringAction.MovePage(page.id, pageIndex - 1)) }, Modifier.fillMaxWidth())
                            LabeledActionButton("Переместить страницу позже", structural && pageIndex < document.pages.lastIndex, false,
                                { dispatch(AuthoringAction.MovePage(page.id, pageIndex + 1)) }, Modifier.fillMaxWidth())
                            LabeledActionButton("Удалить страницу, сохранив её ячейки", structural && document.pages.size > 1, true,
                                { dispatch(AuthoringAction.RemovePage(page.id)) }, Modifier.fillMaxWidth())
                            LabeledActionButton("Изменить сетку ${page.rows} × ${page.columns}", structural, false,
                                { dispatch(AuthoringAction.OpenGrid(page.id)) }, Modifier.fillMaxWidth())
                        }
                    }
                }
                val gridForm = draft.form as? EditorForm.Grid
                if (gridForm != null) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(gridForm.rowsText, { dispatch(AuthoringAction.ChangeGrid(it, gridForm.columnsText)) },
                            label = { Text("Число строк (1–64)") }, enabled = enabled, colors = fieldColors,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(gridForm.columnsText, { dispatch(AuthoringAction.ChangeGrid(gridForm.rowsText, it)) },
                            label = { Text("Число столбцов (1–64)") }, enabled = enabled, colors = fieldColors,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Text("Не поместившиеся ячейки перейдут на новые страницы с такой же сеткой. Пустые ячейки с метаданными также сохранятся. Предел — 256 страниц, без обрезки.")
                        LabeledActionButton("Сохранить сетку в черновик", enabled, false,
                            { dispatch(AuthoringAction.SaveGrid) }, Modifier.fillMaxWidth())
                        LabeledActionButton("Отменить изменение сетки", enabled, false,
                            { dispatch(AuthoringAction.CancelGrid) }, Modifier.fillMaxWidth())
                    }
                }
                if (moveSource != null) item {
                    Text("Выберите ячейку назначения, в том числе на другой странице. Содержимое ячеек обменяется местами.")
                    LabeledActionButton("Отменить перемещение", true, false, { moveSource = null }, Modifier.fillMaxWidth())
                }
                if (page != null && !showSettings && gridForm == null) items((0 until page.rows).toList(), key = { "${page.id.value}:$it" }) { row ->
                    DemandVisibleImages(page.id to row, visibleRowImages(page, row))
                    EditorPageCells(page, row, structural, moveSource, { moveSource = it }, dispatch, images)
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Автосохранение хранит черновик, включая незавершённую форму. Доска изменяется только после применения.")
                        if (showSettings) {
                            LabeledActionButton("Отбросить несохранённые изменения…", enabled, true,
                                { confirmDiscard = true }, Modifier.fillMaxWidth())
                            LabeledActionButton("Удалить сохранённый черновик…", enabled && state.durableVersion != null,
                                true, { confirmDelete = true }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
      }
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
            title = { Text("Отбросить несохранённые изменения?") },
            text = { Text("Редактор закроется. Последний успешно сохранённый черновик останется в библиотеке; несохранённые изменения будут потеряны.") },
            confirmButton = { LabeledActionButton("Отбросить и закрыть", true, true,
                { confirmDiscard = false; dispatch(AuthoringAction.ForceDiscard) }) },
            dismissButton = { LabeledActionButton("Продолжить редактирование", true, false, { confirmDiscard = false }) })
        if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить черновик?") }, text = { Text("Сохранённый черновик будет удалён. Опубликованная доска не изменится.") },
            confirmButton = { LabeledActionButton("Удалить черновик", true, true,
                { confirmDelete = false; dispatch(AuthoringAction.DeleteDraft) }) },
            dismissButton = { LabeledActionButton("Оставить черновик", true, false, { confirmDelete = false }) })
    }
    }
}
