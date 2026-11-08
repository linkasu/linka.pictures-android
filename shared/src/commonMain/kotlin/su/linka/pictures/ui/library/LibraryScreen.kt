package su.linka.pictures.ui.library

import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import su.linka.pictures.resources.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
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
    canReturnToCommunication: Boolean = true, communicationNotices: List<StringResource> = emptyList(),
    canOpenBoards: Boolean = true, ids: IdSource, folderId: FolderId? = null, onFolderChanged: (FolderId?) -> Unit = {}) {
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
    var folderDecision by remember { mutableStateOf<FolderDecision?>(null) }
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
    LaunchedEffect(listing.epoch, listing.organization, folderId) {
        if (folderId != null && listing.organization.folders.none { it.id == folderId }) onFolderChanged(null)
    }
    fun requestAuthorization(action: ProtectedAction) {
        authorize = needsLibraryAuthorization(gate?.check(action, capability, owner))
    }
    VisibleImageDemand(shownListing.epoch, onAssets) {
    Scaffold(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
      topBar = { ProductTopBar(stringResource(Res.string.your_boards),
          modifier = Modifier.heightIn(min = toolbarHeightForFontScale(56f, LocalDensity.current.fontScale).dp),
          navigationIcon = { if (folderId != null) IconButton({ onFolderChanged(listing.organization.folders.firstOrNull { it.id == folderId }?.parentId) }) {
              Icon(LibraryIcons.Back, stringResource(Res.string.back))
          } }, actions = {
          IconButton(onClick = onCreate, enabled = enabled && !busy) { Icon(LibraryIcons.Add, stringResource(Res.string.create_board)) }
          Box {
              IconButton(onClick = { menu = true }) { Icon(LibraryIcons.More, stringResource(Res.string.library_menu)) }
              DropdownMenu(menu, { menu = false }) {
                   DropdownMenuItem(onClick = { menu = false; folderDecision = FolderDecision(shownListing, FolderOperation.CREATE, FolderId(ids.next()), parentId = folderId) }, enabled = enabled && !busy) { Text(stringResource(Res.string.library_create_folder)) }
                  DropdownMenuItem(onClick = { menu = false; onSettings() }, enabled = enabled && !busy) { Text(stringResource(Res.string.settings)) }
                  DropdownMenuItem(onClick = { menu = false; onTransfer(null) }, enabled = enabled && !busy) { Text(stringResource(Res.string.import_backup_and_restore)) }
                  DropdownMenuItem(onClick = { menu = false; showDrafts = true }) { Text(stringResource(Res.string.saved_drafts)) }
                  DropdownMenuItem(onClick = { menu = false; onAccess() }, enabled = enabled && !busy) { Text(stringResource(Res.string.assistant_protection)) }
                  DropdownMenuItem(onClick = { menu = false; refresh++ }, enabled = !busy) { Text(stringResource(Res.string.refresh_list)) }
                   DropdownMenuItem(onClick = { menu = false; onBack() }, enabled = canReturnToCommunication && !busy) { Text(stringResource(Res.string.go_to_message)) }
              }
          }
      }) }) { padding ->
      LazyVerticalGrid(GridCells.Adaptive(libraryTileMinWidth(LocalDensity.current.fontScale).dp),
          Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column {
           Text((listOf(stringResource(Res.string.library_root)) + folderPath(shownListing.organization, folderId).map { it.name }).joinToString(" / "))
           communicationNotices.forEach { Text(stringResource(it)) }
          restartProblem?.let { Feedback(OperationResult.Failed(it)) }
          listing.refreshProblem?.let { Feedback(OperationResult.Failed(it)) }
         if (listing.items.isEmpty()) Text(if (listing.refreshProblem == null) stringResource(Res.string.no_boards_in_the_library_yet_create) else stringResource(Res.string.could_not_read_the_library_data_was))
         if (result !is OperationResult.Completed) Feedback(result)
       } }
        items(shownListing.organization.folders.filter { it.parentId == folderId }, key = { "folder:${it.id.value}" }) { folder ->
             FolderTile(folder, enabled && !busy, { onFolderChanged(folder.id) },
                 { folderDecision = FolderDecision(shownListing, FolderOperation.RENAME, folder.id) },
                 { folderDecision = FolderDecision(shownListing, FolderOperation.MOVE_FOLDER, folder.id) },
                 { folderDecision = FolderDecision(shownListing, FolderOperation.DELETE, folder.id) })
         }
         items(shownListing.items.filter { shownListing.organization.placements[it.id] == folderId }, key = { "board:${it.id.value}" }) { entry ->
              LibraryTile(library, entry, shownListing.epoch, images, enabled && !busy, !busy && canOpenBoards,
                 { onOpen(entry.id) }, { onEdit(entry.id) }, { onTransfer(entry.id) },
                 { decision = BoardDecision(entry, shownListing.epoch, false); name = entry.name
                     result = null; requestAuthorization(ProtectedAction.RENAME_BOARD) },
                 { folderDecision = FolderDecision(shownListing, FolderOperation.MOVE_ENTRY, entryId = entry.id) },
                  { decision = BoardDecision(entry, shownListing.epoch, true)
                     result = null; requestAuthorization(ProtectedAction.DELETE_BOARD) })
         }
      }
    }
    }
    folderDecision?.let { shown -> LibraryOrganizationDialog(shown, controller, owner, capability, enabled, access) { folderDecision = null } }
    if (showDrafts && draftDecision == null) ProductDialog(stringResource(Res.string.saved_drafts), { if (!busy) showDrafts = false }, actions = {
        TextButton(onClick = { showDrafts = false }, enabled = !busy) { Text(stringResource(Res.string.close)) }
    }) {
        when (val loaded = drafts) {
             null -> Text(stringResource(Res.string.loading_drafts))
            is OperationResult.Completed -> {
                val loadedEpoch = draftsEpoch
                 if (loaded.value.isEmpty()) Text(stringResource(Res.string.no_drafts))
                 loaded.value.forEach { draft ->
                  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.draft_version, draft.name, draft.version, if (draft.hasOpenForm) stringResource(Res.string.unfinished_form) else ""))
                    Action(stringResource(Res.string.continue_1_s, draft.name), enabled && !busy) { onResume(draft.id) }
                     TextButton(onClick = { draftDecision = DraftDecision(draft, loadedEpoch!!)
                         result = null; requestAuthorization(ProtectedAction.DELETE_DRAFT) }, enabled = enabled && !busy && loadedEpoch != null) { Text(stringResource(Res.string.delete_draft)) }
                  }
                }
            }
             else -> Feedback(loaded)
        }
         if (result !is OperationResult.Completed) Feedback(result)
    }
    decision?.let { shown ->
      ProductDialog(if (shown.delete) stringResource(Res.string.delete_board) else stringResource(Res.string.board_name), { if (!busy) decision = null }, actions = {
            Action(if (shown.delete) stringResource(Res.string.confirm_deletion_of_1_s, shown.entry.name) else stringResource(Res.string.save_name_for_1_s, shown.entry.name), enabled && !busy && (shown.delete || name.isNotBlank()), shown.delete) {
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
             Action(stringResource(Res.string.cancel), !busy) { decision = null; name = "" }
      }) {
            Text(if (shown.delete) stringResource(Res.string.delete_board_1_s_from_the_library, shown.entry.name)
                else stringResource(Res.string.new_name_for_1_s, shown.entry.name))
             if (!shown.delete) Field(name, stringResource(Res.string.name), !busy) { name = it }
             if (authorize) access()
             Feedback(result)
      }
    }
    draftDecision?.let { shown ->
      ProductDialog(stringResource(Res.string.delete_draft_275), { if (!busy) draftDecision = null }, actions = {
            Action(stringResource(Res.string.confirm_deletion_of_draft_1_s, shown.draft.name), enabled && !busy, true) {
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
             Action(stringResource(Res.string.keep_draft), !busy) { draftDecision = null }
      }) {
             Text(stringResource(Res.string.delete_saved_draft_1_s_version_2, shown.draft.name, shown.draft.version))
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
    onOpen: () -> Unit, onEdit: () -> Unit, onExport: () -> Unit, onRename: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    val board = entry.kind == LibraryEntryKind.BOARD
    val unavailable = stringResource(Res.string.cannot_open)
    val description = stringResource(libraryEntryDescription(entry),
        if (board) entry.cardCount?.toString() ?: "" else entry.problem?.code?.name ?: unavailable)
    val palette = LocalLinkaPalette.current
    Surface(Modifier.fillMaxWidth().padding(1.dp), color = palette.surface, contentColor = palette.text,
        border = BorderStroke(if (palette.highContrast) 2.dp else 1.dp, palette.border), elevation = 0.dp) {
      Box(Modifier.combinedClickable(role = Role.Button,
          onClickLabel = stringResource(Res.string.open_1_s, entry.name), onClick = { if (canOpen && board) onOpen() },
          onLongClickLabel = stringResource(Res.string.library_context_menu), onLongClick = { menu = true })
          .semantics { stateDescription = description }) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 24.dp)) {
                LibraryCover(library, entry, epoch, images)
            }
            Text(entry.name, Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
                fontSize = 16.sp, textAlign = TextAlign.Center)
            if (!board) Text(description, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menu = true }) { Icon(LibraryIcons.More, stringResource(Res.string.actions_for_board_1_s, entry.name)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(onClick = { menu = false; onOpen() }, enabled = canOpen && board) { Text(stringResource(Res.string.open)) }
                DropdownMenuItem(onClick = { menu = false; onEdit() }, enabled = enabled && board) { Text(stringResource(Res.string.edit)) }
                DropdownMenuItem(onClick = { menu = false; onRename() }, enabled = enabled && board) { Text(stringResource(Res.string.rename)) }
                DropdownMenuItem(onClick = { menu = false; onExport() }, enabled = enabled && board) { Text(stringResource(Res.string.export)) }
                DropdownMenuItem(onClick = { menu = false; onMove() }, enabled = enabled) { Text(stringResource(Res.string.library_move_to_folder)) }
                DropdownMenuItem(onClick = { menu = false; onDelete() }, enabled = enabled) { Text(stringResource(Res.string.delete)) }
            }
        }
      }
    }
}
