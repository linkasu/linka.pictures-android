package su.linka.pictures.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.*
import su.linka.pictures.application.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.app.*
import su.linka.pictures.ui.images.*
import su.linka.pictures.ui.theme.LocalLinkaPalette
import su.linka.pictures.ui.components.toolbarHeightForFontScale

private data class BoardDecision(val entry: LibraryEntrySummary, val epoch: Long, val delete: Boolean)
private data class DraftDecision(val draft: DraftSummary, val epoch: Long)

@Composable
internal fun LibraryScreen(library: LibraryStore, controller: LibraryController, owner: String,
    capability: AssistantCapability?, enabled: Boolean, images: Map<AssetId, ImageBitmap>,
    onAssets: (Set<AssetId>) -> Unit, access: @Composable () -> Unit, onBack: () -> Unit,
    onOpen: (BoardId) -> Unit, onCreate: () -> Unit, onEdit: (BoardId) -> Unit,
    onResume: (DraftId) -> Unit, onSettings: () -> Unit, onAccess: () -> Unit,
    onTransfer: (BoardId?) -> Unit, gate: AssistantGate? = null,
    canReturnToCommunication: Boolean = true, communicationNotices: List<String> = emptyList(),
    canOpenBoards: Boolean = true) {
    val listing by library.listing.collectAsState()
    val shownListing = listing
    var drafts by remember { mutableStateOf<OperationResult<List<DraftSummary>>?>(null) }
    var draftsEpoch by remember { mutableStateOf<Long?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showDrafts by remember { mutableStateOf(false) }
    var authorize by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<OperationResult<*>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var decision by remember { mutableStateOf<BoardDecision?>(null) }
    var draftDecision by remember { mutableStateOf<DraftDecision?>(null) }
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val restartProblem by controller.restartProblem.collectAsState()
    LaunchedEffect(listing.epoch, listing.revision, refresh) {
        drafts = null; draftsEpoch = null
        val snapshot = shownListing
        val read = library.drafts()
        if (library.listing.value.epoch == snapshot.epoch && library.listing.value.revision == snapshot.revision) {
            draftsEpoch = snapshot.epoch; drafts = read
        } else drafts = OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
    }
    fun requestAuthorization(action: ProtectedAction) {
        authorize = needsLibraryAuthorization(gate?.check(action, capability, owner))
    }
    VisibleImageDemand(shownListing.epoch, onAssets) {
    Scaffold(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
      topBar = { ProductTopBar("Ваши наборы",
          modifier = Modifier.heightIn(min = toolbarHeightForFontScale(56f, LocalDensity.current.fontScale).dp), actions = {
          IconButton(onClick = onCreate, enabled = enabled && !busy) { Icon(LibraryIcons.Add, "Создать набор") }
          Box {
              IconButton(onClick = { menu = true }) { Icon(LibraryIcons.More, "Меню библиотеки") }
              DropdownMenu(menu, { menu = false }) {
                  DropdownMenuItem(onClick = { menu = false; onSettings() }, enabled = enabled && !busy) { Text("Настройки") }
                  DropdownMenuItem(onClick = { menu = false; onTransfer(null) }, enabled = enabled && !busy) { Text("Импорт, резервная копия и восстановление") }
                  DropdownMenuItem(onClick = { menu = false; showDrafts = true }) { Text("Сохранённые черновики") }
                  DropdownMenuItem(onClick = { menu = false; onAccess() }, enabled = enabled && !busy) { Text("Защита помощника") }
                  DropdownMenuItem(onClick = { menu = false; refresh++ }, enabled = !busy) { Text("Обновить список") }
                   DropdownMenuItem(onClick = { menu = false; onBack() }, enabled = canReturnToCommunication && !busy) { Text("К сообщению") }
              }
          }
      }) }) { padding ->
      LazyVerticalGrid(GridCells.Adaptive(libraryTileMinWidth(LocalDensity.current.fontScale).dp),
          Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column {
           communicationNotices.forEach { Text(it) }
          restartProblem?.let { Feedback(OperationResult.Failed(it)) }
          listing.refreshProblem?.let { Feedback(OperationResult.Failed(it)) }
         if (listing.items.isEmpty()) Text(if (listing.refreshProblem == null) "В библиотеке пока нет досок. Создайте доску или импортируйте файл .linka." else "Не удалось прочитать библиотеку. Данные не заменены пустым списком.")
         if (result !is OperationResult.Completed) Feedback(result)
       } }
        items(shownListing.items, key = { "board:${it.id.value}" }) { entry ->
              LibraryTile(library, entry, shownListing.epoch, images, enabled && !busy, !busy && canOpenBoards,
                 { onOpen(entry.id) }, { onEdit(entry.id) }, { onTransfer(entry.id) },
                 { decision = BoardDecision(entry, shownListing.epoch, false); name = entry.name
                     result = null; requestAuthorization(ProtectedAction.RENAME_BOARD) },
                 { decision = BoardDecision(entry, shownListing.epoch, true)
                     result = null; requestAuthorization(ProtectedAction.DELETE_BOARD) })
         }
      }
    }
    }
    if (showDrafts && draftDecision == null) ProductDialog("Сохранённые черновики", { if (!busy) showDrafts = false }, actions = {
        TextButton(onClick = { showDrafts = false }, enabled = !busy) { Text("Закрыть") }
    }) {
        when (val loaded = drafts) {
             null -> Text("Загружаем черновики…")
            is OperationResult.Completed -> {
                val loadedEpoch = draftsEpoch
                 if (loaded.value.isEmpty()) Text("Черновиков нет.")
                 loaded.value.forEach { draft ->
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${draft.name} · версия ${draft.version}${if (draft.hasOpenForm) " · незавершённая форма" else ""}")
                    Action("Продолжить «${draft.name}»", enabled && !busy) { onResume(draft.id) }
                     TextButton(onClick = { draftDecision = DraftDecision(draft, loadedEpoch!!)
                         result = null; requestAuthorization(ProtectedAction.DELETE_DRAFT) }, enabled = enabled && !busy && loadedEpoch != null) { Text("Удалить черновик…") }
                  }
                }
            }
             else -> Feedback(loaded)
        }
         if (result !is OperationResult.Completed) Feedback(result)
    }
    decision?.let { shown ->
      ProductDialog(if (shown.delete) "Удаление доски" else "Название доски", { if (!busy) decision = null }, actions = {
            Action(if (shown.delete) "Подтверждаю удаление «${shown.entry.name}»" else "Сохранить название «${shown.entry.name}»", enabled && !busy && (shown.delete || name.isNotBlank()), shown.delete) {
                val entered = name; busy = true
                scope.launch {
                    try {
                        result = if (shown.delete) controller.delete(owner, capability, shown.entry, shown.epoch)
                            else controller.rename(owner, capability, shown.entry, entered, shown.epoch)
                         authorize = needsLibraryAuthorization(result)
                         if (libraryDecisionFinished(result!!)) { decision = null; refresh++ }
                    } finally { busy = false }
                }
            }
             Action("Отмена", !busy) { decision = null; name = "" }
      }) {
            Text(if (shown.delete) "Удалить доску «${shown.entry.name}» из библиотеки? Уже составленное сообщение не очищается."
                else "Новое название для «${shown.entry.name}»")
             if (!shown.delete) Field(name, "Название", !busy) { name = it }
             if (authorize) access()
             Feedback(result)
      }
    }
    draftDecision?.let { shown ->
      ProductDialog("Удаление черновика", { if (!busy) draftDecision = null }, actions = {
            Action("Подтверждаю удаление черновика «${shown.draft.name}»", enabled && !busy, true) {
                busy = true
                scope.launch {
                     try {
                         result = controller.deleteDraft(owner, capability, shown.draft, shown.epoch)
                         authorize = needsLibraryAuthorization(result)
                         if (libraryDecisionFinished(result!!)) { draftDecision = null; refresh++ }
                     }
                    finally { busy = false }
                }
            }
             Action("Оставить черновик", !busy) { draftDecision = null }
      }) {
             Text("Удалить сохранённый черновик «${shown.draft.name}» версии ${shown.draft.version}? Опубликованная доска останется.")
             if (authorize) access()
             Feedback(result)
      }
    }
}

@Composable
private fun LibraryCover(library: LibraryStore, entry: LibraryEntrySummary, epoch: Long,
    images: Map<AssetId, ImageBitmap>) {
    var cover by remember(entry.id, entry.revision, epoch) { mutableStateOf<AssetId?>(null) }
    LaunchedEffect(entry.id, entry.revision, epoch) {
        if (entry.kind == LibraryEntryKind.BOARD) {
            val loaded = library.board(entry.id)
            if (loaded is OperationResult.Completed) {
                try {
                    if (loaded.value.epoch == epoch && loaded.value.board.revision == entry.revision) {
                         cover = libraryCoverImage(loaded.value.board.document)
                        currentCoroutineContext().ensureActive()
                    }
                } finally { loaded.value.lease.close() }
            }
        }
    }
    DemandVisibleImages(Triple(entry.id, entry.revision, epoch), setOfNotNull(cover))
    cover?.let { id -> images[id]?.let {
         Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    } }
}

@Composable
private fun LibraryTile(library: LibraryStore, entry: LibraryEntrySummary, epoch: Long,
    images: Map<AssetId, ImageBitmap>, enabled: Boolean, canOpen: Boolean,
    onOpen: () -> Unit, onEdit: () -> Unit, onExport: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    val board = entry.kind == LibraryEntryKind.BOARD
    val palette = LocalLinkaPalette.current
    Surface(Modifier.fillMaxWidth().padding(1.dp), color = palette.surface, contentColor = palette.text,
        border = BorderStroke(if (palette.highContrast) 2.dp else 1.dp, palette.border), elevation = 0.dp) {
      Box(Modifier.clickable(enabled = canOpen && board, role = Role.Button,
          onClickLabel = "Открыть ${entry.name}", onClick = onOpen)
          .semantics { stateDescription = libraryEntryDescription(entry) }) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 24.dp)) {
                LibraryCover(library, entry, epoch, images)
            }
            Text(entry.name, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
                fontSize = 16.sp, textAlign = TextAlign.Center)
            if (!board) Text(libraryEntryDescription(entry), fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menu = true }) { Icon(LibraryIcons.More, "Действия с набором «${entry.name}»") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(onClick = { menu = false; onOpen() }, enabled = canOpen && board) { Text("Открыть") }
                DropdownMenuItem(onClick = { menu = false; onEdit() }, enabled = enabled && board) { Text("Редактировать") }
                DropdownMenuItem(onClick = { menu = false; onRename() }, enabled = enabled && board) { Text("Переименовать") }
                DropdownMenuItem(onClick = { menu = false; onExport() }, enabled = enabled && board) { Text("Экспортировать") }
                DropdownMenuItem(onClick = { menu = false; onDelete() }, enabled = enabled) { Text("Удалить…") }
            }
        }
      }
    }
}
