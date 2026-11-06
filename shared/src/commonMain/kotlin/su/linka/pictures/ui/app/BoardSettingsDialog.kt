package su.linka.pictures.ui.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.Checkbox
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import su.linka.pictures.application.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal data class BoardSettingsSnapshot(val listing: LibraryListing, val settings: PortableSettings)

/** Mode is a separate explicit communication command, never a board default override. */
internal suspend fun saveBoardSettings(controller: LibraryController, owner: String,
    capability: AssistantCapability?, snapshot: BoardSettingsSnapshot, board: BoardId, showPageButtons: Boolean,
    selectedMode: InteractionMode?, onSaved: (PortableSettings) -> Unit, onSetMode: (InteractionMode) -> Unit): OperationResult<Long> {
    val value = snapshot.settings.copy(boards = snapshot.settings.boards + (board to BoardPresentation(showPageButtons)))
    val result = controller.settings(owner, capability, value, snapshot.listing.revision, snapshot.listing.epoch)
    if (result is OperationResult.Completed) {
        onSaved(value)
        selectedMode?.let(onSetMode)
    }
    return result
}

@Composable
internal fun BoardSettingsDialog(library: LibraryStore, controller: LibraryController, gate: AssistantGate,
    owner: String, capability: AssistantCapability?, board: BoardId, mode: InteractionMode, enabled: Boolean,
    access: @Composable () -> Unit, onSaved: (PortableSettings) -> Unit,
    onSetMode: (InteractionMode) -> Unit, onDismiss: () -> Unit) {
    var snapshot by remember(owner, board) { mutableStateOf<BoardSettingsSnapshot?>(null) }
    var result by remember(owner, board) { mutableStateOf<OperationResult<*>?>(null) }
    var refresh by remember(owner, board) { mutableStateOf(0) }
    var busy by remember(owner, board) { mutableStateOf(false) }
    var stopped by remember(owner, board) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCapability by rememberUpdatedState(capability)
    val currentOnSaved by rememberUpdatedState(onSaved)
    val currentOnSetMode by rememberUpdatedState(onSetMode)
    LaunchedEffect(owner, board, refresh) {
        snapshot = null; result = null
        val listing = library.listing.value
        when (val read = library.settings()) {
            is OperationResult.Completed -> {
                if (listing.refreshProblem == null && library.listing.value.refreshProblem == null &&
                    library.listing.value.epoch == listing.epoch && library.listing.value.revision == listing.revision &&
                    listing.items.any { it.id == board && it.kind == LibraryEntryKind.BOARD })
                    snapshot = BoardSettingsSnapshot(listing, read.value)
                else result = OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
            }
            else -> result = read
        }
    }
    ProductDialog("Параметры доски", { if (!busy) onDismiss() }) {
        access()
        if (snapshot == null && result == null) Text("Загружаем настройки…")
        snapshot?.let { loaded -> key(loaded) {
            var pager by remember { mutableStateOf(loaded.settings.boards[board]?.showPageButtons ?: true) }
            var selectedMode by remember { mutableStateOf<InteractionMode?>(null) }
            val writable = enabled && !busy && !stopped && !controller.requiresRestart &&
                library.listing.value.refreshProblem == null
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .toggleable(pager, enabled = writable, role = Role.Checkbox, onValueChange = { pager = it })) {
                Checkbox(pager, null, enabled = writable)
                Text("Показывать кнопки переключения страниц")
            }
            Text("Режим общения (общий для приложения)")
            InteractionMode.entries.forEach { choice -> Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .selectable((selectedMode ?: mode) == choice, enabled = writable, role = Role.RadioButton,
                    onClick = { selectedMode = choice })) {
                RadioButton((selectedMode ?: mode) == choice, null, enabled = writable)
                Text(if (choice == InteractionMode.COMPOSE) "Составлять сообщение" else "Говорить сразу")
            } }
            Action("Сохранить", writable) {
                val admission = gate.check(ProtectedAction.CHANGE_SETTINGS, capability, owner)
                if (admission !is OperationResult.Completed) result = admission
                else {
                    busy = true
                    scope.launch {
                        try {
                            val saved = saveBoardSettings(controller, owner, capability, loaded, board, pager, selectedMode,
                                { currentOnSaved(it) }, { chosen ->
                                    if (currentEnabled && gate.check(ProtectedAction.CHANGE_SETTINGS, currentCapability, owner) is OperationResult.Completed)
                                        currentOnSetMode(chosen)
                                })
                            result = saved
                            if (saved is OperationResult.Completed) { stopped = true; onDismiss() }
                            if (saved == OperationResult.UnconfirmedCommit) stopped = true
                        } finally { busy = false }
                    }
                }
            }
        } }
        Feedback(result)
        Action("Перечитать настройки (отбросить ввод)", enabled && !busy && !stopped && !controller.requiresRestart) { refresh++ }
        Action("Закрыть", !busy, onClick = onDismiss)
    }
}
