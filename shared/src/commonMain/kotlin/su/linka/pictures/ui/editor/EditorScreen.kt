package su.linka.pictures.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.card.CardEditorDialog
import su.linka.pictures.ui.card.rememberCardEditorInput
import su.linka.pictures.ui.components.LabeledActionButton
import su.linka.pictures.ui.images.VisibleImageDemand
import su.linka.pictures.ui.components.ToolbarTitle
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
    val scope = rememberCoroutineScope()
    val draft = state.draft
    val document = draft?.document
    val enabled = state.editable()
    val structural = enabled && draft?.form == null && !state.mediaBusy
    val palette = LocalLinkaPalette.current
    var selectedPage by rememberSaveable(draft?.id?.value) { mutableStateOf<String?>(null) }
    var moveSource by remember(draft?.id) { mutableStateOf<Pair<PageId, Int>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable(draft?.id?.value) { mutableStateOf(false) }
    var showSaveStatus by remember { mutableStateOf(false) }
    val page = editorPage(draft, selectedPage)
    val pageIndex = document?.pages?.indexOf(page) ?: -1
    val gridForm = draft?.form as? EditorForm.Grid
    val gridEnabled = enabled && draft?.form !is EditorForm.Card && !state.mediaBusy
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val cardInput = rememberCardEditorInput(state, ownerId, dispatchEdited)
    LaunchedEffect(draft?.id, draft?.form != null, page?.id) {
        if (draft?.form != null) selectedPage = page?.id?.value
    }
    VisibleImageDemand(Triple(state.epoch, ownerId, draft?.id), onImagesNeeded) {
        Surface(modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                TopAppBar(modifier = Modifier.height(toolbarHeightForFontScale(56f, LocalDensity.current.fontScale).dp),
                    backgroundColor = palette.primary, contentColor = palette.onPrimary,
                    title = { ToolbarTitle(document?.name ?: "Редактор доски", modifier = Modifier.semantics {
                        heading(); stateDescription = draftSaveLabel(state.savePhase)
                    }) },
                    navigationIcon = {
                        IconButton({ dispatch(AuthoringAction.Close) }, enabled = state.phase != AuthoringPhase.UNCONFIRMED,
                            modifier = Modifier.semantics { contentDescription = "Назад с сохранённым черновиком" }) { EditorIcon(EditorSymbol.BACK) }
                    }, actions = {
                        IconButton({ dispatch(AuthoringAction.Apply) },
                            enabled = structural && state.phase != AuthoringPhase.CONFLICT,
                            modifier = Modifier.semantics {
                                contentDescription = "Сохранить доску"
                                if (draft?.form != null) stateDescription = "Сначала завершите форму"
                            }) { EditorIcon(EditorSymbol.SAVE) }
                        Box {
                            IconButton({ menu = true }, modifier = Modifier.semantics { contentDescription = "Меню редактора" }) { EditorIcon(EditorSymbol.MORE) }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ menu = false; showSettings = true }, enabled = document != null && draft.form == null) { Text("Параметры доски") }
                                DropdownMenuItem({ menu = false; dispatch(AuthoringAction.AddPage(page?.rows ?: 3, page?.columns ?: 3)) }, enabled = structural) { Text("Добавить страницу") }
                                DropdownMenuItem({ menu = false; page?.let { dispatch(AuthoringAction.MovePage(it.id, pageIndex - 1)) } }, enabled = structural && pageIndex > 0) { Text("Переместить страницу раньше") }
                                DropdownMenuItem({ menu = false; page?.let { dispatch(AuthoringAction.MovePage(it.id, pageIndex + 1)) } }, enabled = structural && pageIndex < (document?.pages?.lastIndex ?: 0)) { Text("Переместить страницу позже") }
                                DropdownMenuItem({ menu = false; moveSource = null; page?.let { dispatch(AuthoringAction.RemovePage(it.id)) } }, enabled = structural && (document?.pages?.size ?: 0) > 1) { Text("Удалить страницу, сохранив ячейки") }
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
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).toggleable(
                        value = document.behavior.inputStyle == InputStyle.LITERAL,
                        enabled = structural, role = Role.Checkbox,
                        onValueChange = { dispatch(withoutSpaceDetails(document, it)) }),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(document.behavior.inputStyle == InputStyle.LITERAL, onCheckedChange = null, enabled = structural)
                        Text("Вводить без пробела")
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                        val pageId = page.id
                        val draftId = draft.id
                        val epoch = state.epoch
                        val gridValue: (AuthoringState) -> AuthoringAction.ChangeGrid? = { snapshot ->
                            val actualPage = snapshot.draft?.document?.pages?.firstOrNull { it.id == pageId }
                            val actualForm = (snapshot.draft?.form as? EditorForm.Grid)?.takeIf { it.pageId == pageId }
                            actualPage?.let {
                                AuthoringAction.ChangeGrid(actualForm?.rowsText ?: it.rows.toString(), actualForm?.columnsText ?: it.columns.toString(), it.id)
                            }
                        }
                        val grid = remember(ownerId, epoch, draftId, state.phase, pageId) {
                            BufferedAuthoringInput(checkNotNull(gridValue(state)), state.editVersion)
                        }
                        SideEffect { gridValue(state)?.let { grid.acceptExternal(it, state.editVersion) } }
                        val changeGrid: (AuthoringAction.ChangeGrid) -> Unit = { next ->
                            moveSource = null
                            val token = grid.edit(next)
                            dispatchEdited(next) { accepted ->
                                if (accepted.ownerId == ownerId && accepted.epoch == epoch && accepted.draft?.id == draftId) {
                                    val payload = gridValue(accepted)
                                    val editVersion = accepted.editVersion
                                    if (payload != null) scope.launch(Dispatchers.Main.immediate) {
                                        grid.handled(token, payload, editVersion)
                                    }
                                }
                            }
                        }
                        val groupsPerRow = editorGridGroupsPerRow(maxWidth.value, LocalDensity.current.fontScale)
                        Column(Modifier.heightIn(max = maxHeight / 2).verticalScroll(rememberScrollState())) {
                            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                maxItemsInEachRow = groupsPerRow) {
                                Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextField(grid.value.rowsText, { changeGrid(grid.value.copy(rowsText = it)) },
                                        Modifier.weight(1f).semantics { contentDescription = "Количество строк" },
                                        enabled = gridEnabled, singleLine = true, colors = authoringFieldColors(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                    TextField(grid.value.columnsText, { changeGrid(grid.value.copy(columnsText = it)) },
                                        Modifier.weight(1f).semantics { contentDescription = "Количество столбцов" },
                                        enabled = gridEnabled, singleLine = true, colors = authoringFieldColors(),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                }
                                Button({
                                    if (!grid.pending) {
                                        focus.clearFocus(); keyboard?.hide()
                                        dispatch(AuthoringAction.SaveGrid)
                                    }
                                }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = gridEnabled && !grid.pending) {
                                    Text("ИЗМЕНИТЬ РАЗМЕР СЕТКИ")
                                }
                            }
                            if (gridForm != null) TextButton({
                                focus.clearFocus(); keyboard?.hide()
                                grid.discardPending()
                                dispatch(AuthoringAction.CancelGrid)
                            }, enabled = gridEnabled, modifier = Modifier.align(Alignment.End)) { Text("Отмена") }
                        }
                    }
                    if (moveSource != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Выберите ячейку назначения (можно на другой странице).", Modifier.weight(1f))
                        TextButton({ moveSource = null }) { Text("Отмена") }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                        val pagerWidth = if (maxWidth >= 600.dp) maxWidth / 6 else 48.dp
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ selectedPage = document.pages[pageIndex - 1].id.value }, enabled = pageIndex > 0 && draft.form == null,
                                modifier = Modifier.width(pagerWidth).semantics { contentDescription = "Предыдущая страница" }) { EditorIcon(EditorSymbol.PREVIOUS) }
                            key(page.id) {
                                EditorPageCells(page, structural, moveSource, { moveSource = it }, dispatch, images, Modifier.weight(1f).fillMaxHeight())
                            }
                            IconButton({ selectedPage = document.pages[pageIndex + 1].id.value }, enabled = pageIndex < document.pages.lastIndex && draft.form == null,
                                modifier = Modifier.width(pagerWidth).semantics { contentDescription = "Следующая страница" }) { EditorIcon(EditorSymbol.NEXT) }
                        }
                    }
                    Text("${pageIndex + 1} / ${document.pages.size}", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.caption)
                }
            }
            if (showSettings && document != null) EditorSettingsDialog(state, ownerId, enabled, dispatchEdited, { showSettings = false })
            if (draft?.form is EditorForm.Card) CardEditorDialog(state, ownerId, dispatch, dispatchEdited, images, input = cardInput)
            if (showSaveStatus) AlertDialog(onDismissRequest = { showSaveStatus = false },
                title = { Text("Состояние черновика") }, text = { Column {
                    Text(draftSaveLabel(state.savePhase))
                    Text("Автосохранение сохраняет только черновик. Доска изменяется после «Сохранить доску». Завершите форму карточки или сетки перед сохранением доски.")
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
