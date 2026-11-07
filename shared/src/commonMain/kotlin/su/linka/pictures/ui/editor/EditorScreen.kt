package su.linka.pictures.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.card.CardEditorDialog
import su.linka.pictures.ui.card.CardEditorContent
import su.linka.pictures.ui.card.rememberCardEditorInput
import su.linka.pictures.ui.app.editorUsesInspector
import su.linka.pictures.ui.components.LabeledActionButton
import su.linka.pictures.ui.images.VisibleImageDemand
import su.linka.pictures.ui.components.ToolbarTitle
import su.linka.pictures.ui.app.ProductTopBar
import su.linka.pictures.ui.components.toolbarHeightForFontScale
import su.linka.pictures.ui.theme.LocalLinkaPalette

@OptIn(ExperimentalLayoutApi::class)
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
    val dispatch: (AuthoringAction) -> Unit = { onCommand(AuthoringCommand(CommandId(ids.next()), ownerId, capability, it)) }
    val dispatchEdited: (AuthoringAction, (AuthoringState) -> Unit) -> Unit = { action, onHandled ->
        onCommand(AuthoringCommand(CommandId(ids.next()), ownerId, capability, action, onHandled))
    }
    val draft = state.draft
    val document = draft?.document
    val enabled = state.editable()
    val structural = enabled && draft?.form == null && !state.mediaBusy
    val palette = LocalLinkaPalette.current
    var selectedPage by rememberSaveable(draft?.id?.value) { mutableStateOf<String?>(null) }
    var moveSource by remember(draft?.id) { mutableStateOf<Pair<PageId, Int>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable(draft?.id?.value) { mutableStateOf(false) }
    var showSaveStatus by remember { mutableStateOf(false) }
    val page = editorPage(draft, selectedPage)
    val pageIndex = document?.pages?.indexOf(page) ?: -1
    val gridForm = draft?.form as? EditorForm.Grid
    val cardInput = rememberCardEditorInput(state, ownerId, dispatchEdited)
    LaunchedEffect(draft?.id, draft?.form != null, page?.id) {
        if (draft?.form != null) selectedPage = page?.id?.value
    }
    VisibleImageDemand(Triple(state.epoch, ownerId, draft?.id), onImagesNeeded) {
        Surface(modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
             val fontScale = LocalDensity.current.fontScale
             // IME resizes the form, not its host; moving a focused field between hosts dismisses the keyboard.
             val inspector = editorUsesInspector(maxWidth.value, maxHeight.value, fontScale)
             Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                ProductTopBar(modifier = Modifier.heightIn(min = toolbarHeightForFontScale(56f, LocalDensity.current.fontScale).dp)
                    .semantics { stateDescription = draftSaveLabel(state.savePhase) },
                    title = document?.name ?: "Редактор доски",
                    navigationIcon = {
                        IconButton({ dispatch(AuthoringAction.Close) }, enabled = state.phase != AuthoringPhase.UNCONFIRMED,
                            modifier = Modifier.semantics { contentDescription = "Назад с сохранённым черновиком" }) { EditorIcon(EditorSymbol.BACK) }
                    }, actions = {
                         TextButton({ dispatch(AuthoringAction.Apply) },
                            enabled = structural && state.phase != AuthoringPhase.CONFLICT,
                            modifier = Modifier.semantics {
                                 contentDescription = "Сохранить и выйти"
                                if (draft?.form != null) stateDescription = "Сначала завершите форму"
                             }) { Text("Сохранить и выйти") }
                        Box {
                            IconButton({ menu = true }, modifier = Modifier.semantics { contentDescription = "Меню редактора" }) { EditorIcon(EditorSymbol.MORE) }
                            DropdownMenu(menu, { menu = false }) {
                                 DropdownMenuItem({ menu = false; showSettings = true }, enabled = document != null && draft.form !is EditorForm.Card) { Text("Параметры доски") }
                                DropdownMenuItem({ menu = false; showSaveStatus = true }) { Text("Состояние черновика") }
                                DropdownMenuItem({ menu = false; confirmDiscard = true }, enabled = enabled) { Text("Отбросить несохранённые изменения…") }
                                DropdownMenuItem({ menu = false; confirmDelete = true }, enabled = structural && state.durableVersion != null) { Text("Удалить черновик…") }
                            }
                        }
                    })
                val urgentSave = state.savePhase == DraftSavePhase.FAILED || state.savePhase == DraftSavePhase.UNCONFIRMED
                AuthoringFeedback(state, dispatch, showSaveStatus = urgentSave)
                if (document == null) {
                    Text(if (state.phase == AuthoringPhase.LOADING) "Загрузка черновика…" else "Создайте доску или откройте черновик из библиотеки.", Modifier.padding(8.dp))
                    LabeledActionButton("Новая доска", state.phase == AuthoringPhase.IDLE, false, { dispatch(AuthoringAction.NewBoard()) })
                 } else if (page != null) {
                     Row(Modifier.weight(1f).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                         Surface(Modifier.weight(1f).fillMaxHeight(), color = palette.surface,
                             border = BorderStroke(1.dp, palette.border), shape = MaterialTheme.shapes.medium) {
                             Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                 Text("Карточки", style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
                                 if (moveSource != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                     Text("Выберите ячейку назначения (можно на другой странице).", Modifier.weight(1f))
                                     TextButton({ moveSource = null }) { Text("Отмена") }
                                 }
                                 key(page.id) {
                                     EditorPageCells(page, structural, moveSource, { moveSource = it }, dispatch, images,
                                         Modifier.weight(1f).fillMaxWidth(), activePosition = (draft.form as? EditorForm.Card)?.slotIndex)
                                 }
                                 Divider(color = palette.border)
                                 FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                     verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                     IconButton({ selectedPage = document.pages[pageIndex - 1].id.value }, enabled = pageIndex > 0 && draft.form == null,
                                         modifier = Modifier.semantics { contentDescription = "Предыдущая страница" }) { EditorIcon(EditorSymbol.PREVIOUS) }
                                     Text("Страница ${pageIndex + 1} из ${document.pages.size}", Modifier.heightIn(min = 48.dp).wrapContentHeight(),
                                         style = MaterialTheme.typography.body2)
                                     IconButton({ selectedPage = document.pages[pageIndex + 1].id.value }, enabled = pageIndex < document.pages.lastIndex && draft.form == null,
                                         modifier = Modifier.semantics { contentDescription = "Следующая страница" }) { EditorIcon(EditorSymbol.NEXT) }
                                     OutlinedButton({ dispatch(AuthoringAction.AddPage(page.rows, page.columns)) }, enabled = structural) { Text("+ Страница") }
                                     Box {
                                         IconButton({ pageMenu = true }, enabled = structural,
                                             modifier = Modifier.semantics { contentDescription = "Действия страницы" }) { EditorIcon(EditorSymbol.MORE) }
                                         DropdownMenu(pageMenu, { pageMenu = false }) {
                                             DropdownMenuItem({ pageMenu = false; dispatch(AuthoringAction.MovePage(page.id, pageIndex - 1)) }, enabled = structural && pageIndex > 0) { Text("Переместить страницу раньше") }
                                             DropdownMenuItem({ pageMenu = false; dispatch(AuthoringAction.MovePage(page.id, pageIndex + 1)) }, enabled = structural && pageIndex < document.pages.lastIndex) { Text("Переместить страницу позже") }
                                             DropdownMenuItem({ pageMenu = false; moveSource = null; dispatch(AuthoringAction.RemovePage(page.id)) }, enabled = structural && document.pages.size > 1) { Text("Удалить страницу, сохранив ячейки") }
                                         }
                                     }
                                 }
                             }
                         }
                         if (inspector) Surface(Modifier.width(360.dp * fontScale.coerceAtLeast(1f)).fillMaxHeight(), color = palette.surface,
                             border = BorderStroke(1.dp, palette.border), shape = MaterialTheme.shapes.medium) {
                             if (cardInput != null) CardEditorContent(state, cardInput, dispatch, images, Modifier.fillMaxSize().padding(16.dp), fillHeight = true)
                             else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                 Text("Карточка", style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
                                 Text("Выберите карточку на доске для редактирования.")
                             }
                         }
                     }
                }
            }
             if ((showSettings || gridForm != null) && document != null && draft.form !is EditorForm.Card)
                 EditorSettingsDialog(state, ownerId, enabled, dispatchEdited, { showSettings = false }, page, dispatch, { moveSource = null })
             if (!inspector && cardInput != null) CardEditorDialog(state, ownerId, dispatch, dispatchEdited, images, input = cardInput)
            if (showSaveStatus) AlertDialog(onDismissRequest = { showSaveStatus = false },
                title = { Text("Состояние черновика") }, text = { Column {
                    Text(draftSaveLabel(state.savePhase))
                     Text("Автосохранение сохраняет только черновик. «Сохранить и выйти» изменяет доску и закрывает редактор. Сначала завершите форму карточки или сетки.")
                } }, confirmButton = { TextButton({ showSaveStatus = false }) { Text("Понятно") } })
            if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
                title = { Text("Отбросить несохранённые изменения?") },
                text = { Text("Редактор закроется. Последний успешно сохранённый черновик останется; несохранённый ввод будет потерян.") },
                confirmButton = { TextButton({ confirmDiscard = false; dispatch(AuthoringAction.ForceDiscard) }) { Text("Отбросить и закрыть") } },
                dismissButton = { TextButton({ confirmDiscard = false }) { Text("Остаться") } })
            if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
                title = { Text("Удалить черновик?") }, text = { Text("Опубликованная доска не изменится. Сохранённый черновик будет удалён.") },
                confirmButton = { TextButton({ confirmDelete = false; dispatch(AuthoringAction.DeleteDraft) }) { Text("Удалить черновик") } },
                 dismissButton = { TextButton({ confirmDelete = false }) { Text("Отмена") } })
            }
        }
    }
}
