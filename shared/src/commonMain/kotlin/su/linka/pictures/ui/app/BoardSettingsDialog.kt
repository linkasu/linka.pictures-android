package su.linka.pictures.ui.app

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
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
    capability: AssistantCapability?, snapshot: BoardSettingsSnapshot, board: BoardId, presentation: BoardPresentation,
    selectedMode: InteractionMode?, onSaved: (PortableSettings) -> Unit, onSetMode: (InteractionMode) -> Unit,
): OperationResult<Long> {
    val value = snapshot.settings.copy(boards = snapshot.settings.boards + (board to presentation))
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
    onSetMode: (InteractionMode) -> Unit, onDismiss: () -> Unit,
    validateSettings: suspend (PortableSettings) -> String? = { null }) {
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
    val currentValidation by rememberUpdatedState(validateSettings)
    var layoutNotice by remember { mutableStateOf<String?>(null) }
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
    var options by remember(snapshot) { mutableStateOf(snapshot?.settings?.boards?.get(board) ?: BoardPresentation()) }
    var selectedMode by remember(snapshot) { mutableStateOf<InteractionMode?>(null) }
    val writable = enabled && !busy && !stopped && !controller.requiresRestart &&
        library.listing.value.refreshProblem == null
    ProductDialog(stringResource(Res.string.board_options), { if (!busy) onDismiss() }, actions = {
        androidx.compose.foundation.layout.Column {
            snapshot?.let { loaded ->
                Action(stringResource(Res.string.save), writable) {
                    val admission = gate.check(ProtectedAction.CHANGE_SETTINGS, capability, owner)
                    if (admission !is OperationResult.Completed) result = admission
                    else {
                        val modeGuard = gate.guard(ProtectedAction.CHANGE_SETTINGS, capability, owner)
                        busy = true
                        scope.launch {
                             try {
                                val proposed = loaded.settings.copy(boards = loaded.settings.boards +
                                    (board to options))
                                layoutNotice = currentValidation(proposed)
                                if (layoutNotice != null) return@launch
                                val saved = saveBoardSettings(controller, owner, capability, loaded, board, options, selectedMode,
                                    { currentOnSaved(it) }, { chosen ->
                                        if (currentEnabled && modeGuard() is OperationResult.Completed &&
                                            gate.check(ProtectedAction.CHANGE_SETTINGS, currentCapability, owner) is OperationResult.Completed)
                                            currentOnSetMode(chosen)
                                    })
                                result = saved
                                if (saved is OperationResult.Completed) { stopped = true; onDismiss() }
                                if (saved == OperationResult.UnconfirmedCommit) stopped = true
                            } finally { busy = false }
                        }
                    }
                }
            }
            Action(stringResource(Res.string.close), !busy, onClick = onDismiss)
        }
    }) {
        layoutNotice?.let { Text(it) }
        if (gate.check(ProtectedAction.CHANGE_SETTINGS, capability, owner) !is OperationResult.Completed) access()
        if (snapshot == null && result == null) Text(stringResource(Res.string.loading_settings))
        snapshot?.let {
            Text(stringResource(Res.string.show_page_navigation_buttons))
            PageNavigation.entries.forEach { choice ->
                Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .selectable(options.navigation == choice, enabled = writable, role = Role.RadioButton,
                        onClick = { options = options.copy(navigation = choice) })) {
                    RadioButton(options.navigation == choice, null, enabled = writable)
                    Text(stringResource(when (choice) {
                        PageNavigation.NONE -> Res.string.communication_navigation_none
                        PageNavigation.BOTTOM -> Res.string.communication_navigation_bottom
                        PageNavigation.SIDE -> Res.string.communication_navigation_side
                    }))
                }
            }
            Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .toggleable(options.dragToCompose, enabled = writable, role = Role.Checkbox,
                    onValueChange = { options = options.copy(dragToCompose = it) })) {
                Checkbox(options.dragToCompose, null, enabled = writable)
                Text(stringResource(Res.string.communication_drag_to_message))
            }
            Text(stringResource(Res.string.communication_mode_app_wide))
            InteractionMode.entries.forEach { choice -> Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .selectable((selectedMode ?: mode) == choice, enabled = writable, role = Role.RadioButton,
                    onClick = { selectedMode = choice })) {
                RadioButton((selectedMode ?: mode) == choice, null, enabled = writable)
                Text(if (choice == InteractionMode.COMPOSE) stringResource(Res.string.compose_a_message) else stringResource(Res.string.speak_immediately))
            } }
        }
        Feedback(result)
        Action(stringResource(Res.string.reload_settings_discard_input), enabled && !busy && !stopped && !controller.requiresRestart) { refresh++ }
    }
}
