package su.linka.pictures.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import su.linka.pictures.application.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.app.*

internal fun libraryEntryDescription(entry: LibraryEntrySummary): String =
    if (entry.kind == LibraryEntryKind.RETAINED_ARCHIVE) "Сохранённый нечитаемый архив. ${entry.problem?.code ?: "Открытие недоступно"}"
    else entry.cardCount?.let { "Карточек: $it" } ?: "Количество карточек неизвестно"

private data class BoardDecision(val entry: LibraryEntrySummary, val epoch: Long, val delete: Boolean)
private data class DraftDecision(val draft: DraftSummary, val epoch: Long)

@Composable
internal fun LibraryScreen(library: LibraryStore, controller: LibraryController, owner: String,
    capability: AssistantCapability?, enabled: Boolean, images: Map<AssetId, ImageBitmap>,
    onAssets: (Set<AssetId>) -> Unit, access: @Composable () -> Unit, onBack: () -> Unit,
    onOpen: (BoardId) -> Unit, onCreate: () -> Unit, onEdit: (BoardId) -> Unit,
    onResume: (DraftId) -> Unit, onSettings: () -> Unit, onAccess: () -> Unit,
    onTransfer: (BoardId?) -> Unit) {
    val listing by library.listing.collectAsState()
    val shownListing = listing
    var drafts by remember { mutableStateOf<OperationResult<List<DraftSummary>>?>(null) }
    var draftsEpoch by remember { mutableStateOf<Long?>(null) }
    val covers = remember { mutableStateMapOf<BoardId, AssetId>() }
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
    val requested = covers.values.toSet()
    LaunchedEffect(requested) { onAssets(requested) }
    ProductScaffold("Библиотека", onBack, scrollContent = false) {
      LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
       item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        restartProblem?.let { Feedback(OperationResult.Failed(it)) }
        access()
        Action("Новая доска", enabled && !busy, onClick = onCreate)
        Action("Настройки", enabled && !busy, onClick = onSettings)
        Action("Защита помощника", enabled && !busy, onClick = onAccess)
        Action("Импорт, резервная копия и восстановление", enabled && !busy) { onTransfer(null) }
        Action("Обновить список", !busy) { refresh++ }
        listing.refreshProblem?.let { Feedback(OperationResult.Failed(it)) }
        if (listing.items.isEmpty()) Text(if (listing.refreshProblem == null) "В библиотеке пока нет досок. Создайте доску или импортируйте файл .linka." else "Не удалось прочитать библиотеку. Данные не заменены пустым списком.")
       } }
        items(shownListing.items, key = { "board:${it.id.value}" }) { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(entry.name)
                LibraryCover(library, entry, shownListing.epoch, images) { asset ->
                    if (asset == null) covers.remove(entry.id) else covers[entry.id] = asset
                }
                Text(libraryEntryDescription(entry))
                Action("Открыть «${entry.name}»", !busy && entry.kind == LibraryEntryKind.BOARD) { onOpen(entry.id) }
                Action("Редактировать «${entry.name}»", enabled && !busy && entry.kind == LibraryEntryKind.BOARD) { onEdit(entry.id) }
                Action("Переименовать «${entry.name}»", enabled && !busy && entry.kind == LibraryEntryKind.BOARD) {
                    decision = BoardDecision(entry, shownListing.epoch, false); name = entry.name
                }
                Action("Экспортировать «${entry.name}»", enabled && !busy && entry.kind == LibraryEntryKind.BOARD) { onTransfer(entry.id) }
                Action("Удалить «${entry.name}»…", enabled && !busy, true) { decision = BoardDecision(entry, shownListing.epoch, true) }
            }
        }
        item { Text("Сохранённые черновики") }
        when (val loaded = drafts) {
            null -> item { Text("Загружаем черновики…") }
            is OperationResult.Completed -> {
                val loadedEpoch = draftsEpoch
                if (loaded.value.isEmpty()) item { Text("Черновиков нет.") }
                items(loaded.value, key = { "draft:${it.id.value}" }) { draft ->
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${draft.name} · версия ${draft.version}${if (draft.hasOpenForm) " · незавершённая форма" else ""}")
                    Action("Продолжить «${draft.name}»", enabled && !busy) { onResume(draft.id) }
                    Action("Удалить черновик «${draft.name}»…", enabled && !busy && loadedEpoch != null, true) { draftDecision = DraftDecision(draft, loadedEpoch!!) }
                  }
                }
            }
            else -> item { Feedback(loaded) }
        }
        item { Feedback(result) }
      }
    }
    decision?.let { shown ->
      ProductDialog(if (shown.delete) "Удаление доски" else "Название доски", { if (!busy) decision = null }) {
            Text(if (shown.delete) "Удалить доску «${shown.entry.name}» из библиотеки? Уже составленное сообщение не очищается."
                else "Новое название для «${shown.entry.name}»")
            if (!shown.delete) Field(name, "Название", !busy) { name = it }
            Action(if (shown.delete) "Подтверждаю удаление «${shown.entry.name}»" else "Сохранить название «${shown.entry.name}»", enabled && !busy && (shown.delete || name.isNotBlank()), shown.delete) {
                val entered = name; busy = true
                scope.launch {
                    try {
                        result = if (shown.delete) controller.delete(owner, capability, shown.entry, shown.epoch)
                            else controller.rename(owner, capability, shown.entry, entered, shown.epoch)
                        decision = null; refresh++
                    } finally { busy = false }
                }
            }
            Action("Отмена", !busy) { decision = null; name = "" }
      }
    }
    draftDecision?.let { shown ->
      ProductDialog("Удаление черновика", { if (!busy) draftDecision = null }) {
            Text("Удалить сохранённый черновик «${shown.draft.name}» версии ${shown.draft.version}? Опубликованная доска останется.")
            Action("Подтверждаю удаление черновика «${shown.draft.name}»", enabled && !busy, true) {
                busy = true
                scope.launch {
                    try { result = controller.deleteDraft(owner, capability, shown.draft, shown.epoch); draftDecision = null; refresh++ }
                    finally { busy = false }
                }
            }
            Action("Оставить черновик", !busy) { draftDecision = null }
      }
    }
}

@Composable
private fun LibraryCover(library: LibraryStore, entry: LibraryEntrySummary, epoch: Long,
    images: Map<AssetId, ImageBitmap>, onAsset: (AssetId?) -> Unit) {
    var cover by remember(entry.id, entry.revision, epoch) { mutableStateOf<AssetId?>(null) }
    val report by rememberUpdatedState(onAsset)
    LaunchedEffect(entry.id, entry.revision, epoch) {
        if (entry.kind == LibraryEntryKind.BOARD) {
            val loaded = library.board(entry.id)
            if (loaded is OperationResult.Completed) {
                try {
                    if (loaded.value.epoch == epoch && loaded.value.board.revision == entry.revision) {
                        cover = loaded.value.board.document.pages.asSequence().flatMap { it.cells.asSequence() }
                            .mapNotNull { it.card?.image }.firstOrNull()
                        currentCoroutineContext().ensureActive()
                        report(cover)
                    }
                } finally { loaded.value.lease.close() }
            }
        }
    }
    DisposableEffect(entry.id, entry.revision, epoch) { onDispose { report(null) } }
    cover?.let { id -> images[id]?.let {
        Image(it, "Обложка доски «${entry.name}»", Modifier.fillMaxWidth().height(96.dp), contentScale = ContentScale.Fit)
    } }
}
