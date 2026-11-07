package su.linka.pictures.ui.communication

import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Text
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.communication.CommunicationCommand
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.application.ports.IdSource
import su.linka.pictures.application.ports.VoiceCapability
import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.CommandId
import su.linka.pictures.ui.images.VisibleImageDemand
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
fun CommunicationScreen(
    state: CommunicationState,
    ids: IdSource,
    voices: VoiceCapability,
    onCommand: (CommunicationCommand) -> Unit,
    onOpenLibrary: () -> Unit,
    images: Map<AssetId, ImageBitmap> = emptyMap(),
    modifier: Modifier = Modifier,
    showPageButtons: Boolean = true,
    onImagesNeeded: ((Set<AssetId>) -> Unit)? = null,
    onOpenBoardSettings: (() -> Unit)? = null,
) {
    val presentation = remember(state, voices) { communicationPresentation(state, voices) }
    var input by rememberSaveable(state.stamp.epoch) { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    val palette = LocalLinkaPalette.current
    val dispatch: (CommunicationCommand) -> Unit = { if (presentation.commandsEnabled) onCommand(it) }
    val speechAction: @Composable (Modifier) -> Unit = { action ->
        CommunicationActionButton(if (presentation.stopping) CommunicationIcon.Stop else CommunicationIcon.Speak,
            if (presentation.stopping) "Остановить" else "Произнести", presentation.speechEnabled,
            { dispatch(if (presentation.stopping) CommunicationCommand.Stop(CommandId(ids.next()))
                else CommunicationCommand.Speak(CommandId(ids.next()))) },
            action.semantics { stateDescription = presentation.audioStatus })
    }
    val backspaceAction: @Composable (Modifier) -> Unit = { action ->
        CommunicationActionButton(CommunicationIcon.Backspace, "Удалить последнее", presentation.editEnabled,
            { dispatch(CommunicationCommand.Backspace(CommandId(ids.next()))) }, action)
    }
    val clearAction: @Composable (Modifier) -> Unit = { action ->
        CommunicationActionButton(if (presentation.undoEnabled) CommunicationIcon.Undo else CommunicationIcon.Clear,
            if (presentation.undoEnabled) "Вернуть сообщение" else "Очистить сообщение",
            presentation.editEnabled || presentation.undoEnabled,
            { dispatch(if (presentation.undoEnabled) CommunicationCommand.UndoClear(CommandId(ids.next()))
                else CommunicationCommand.Clear(CommandId(ids.next()))) }, action)
    }
    VisibleImageDemand(state.stamp.epoch, onImagesNeeded) {
    val paging = Modifier.communicationPaging(inputFocused, presentation.commandsEnabled) { direction ->
        val pages = state.board?.document?.pages.orEmpty()
        pageTarget(presentation.pageIndex, pages.size, direction, presentation.commandsEnabled)?.let {
            dispatch(CommunicationCommand.ChangePage(CommandId(ids.next()), pages[it].id))
        }
    }
    Surface(modifier.fillMaxSize().then(paging), color = palette.background, contentColor = palette.text) {
        CompositionLocalProvider(LocalContentAlpha provides 1f) {
            BoxWithConstraints(
                Modifier.fillMaxSize(),
            ) {
                val toolbarHeight = communicationToolbarHeight(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale).dp
                val actionSurface = communicationActionSurface(palette)
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                CommunicationControls(
                    state.board?.document?.name ?: "Общение", presentation, ids, dispatch,
                    { if (presentation.commandsEnabled) onOpenLibrary() }, onOpenBoardSettings,
                    input, { if (presentation.commandsEnabled) input = it }, { inputFocused = it },
                    toolbarHeight,
                )
                val notices = communicationNotices(state, presentation, voices)
                if (notices.isNotEmpty()) Column(
                    Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) { notices.forEach { Text(it, color = palette.danger) } }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val pages = state.board?.document?.pages.orEmpty()
                    val stackedActions = maxWidth.value < 600f * LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val budget = communicationVerticalBudget(maxHeight.value, LocalDensity.current.fontScale, pages.size > 1, stackedActions)
                    val actionWidth = (maxWidth / 8).coerceIn(48.dp, 96.dp)
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxWidth().height(budget.content.dp)) {
                            Row(Modifier.fillMaxWidth().height(budget.phrase.dp)) {
                                val action = Modifier.width(actionWidth).fillMaxHeight()
                                if (!stackedActions) speechAction(action)
                                CommunicationMessage(state, images, Modifier.weight(1f).fillMaxHeight())
                                if (!stackedActions) { backspaceAction(action); clearAction(action) }
                             }
                             if (stackedActions) Row(Modifier.fillMaxWidth().height(budget.actions.dp)) {
                                 val action = Modifier.weight(1f).fillMaxHeight()
                                 speechAction(action); backspaceAction(action); clearAction(action)
                             }
                            Divider(color = palette.divider)
                            fun changePage(direction: Int): Boolean {
                                val target = pageTarget(presentation.pageIndex, pages.size, direction, presentation.commandsEnabled) ?: return false
                                dispatch(CommunicationCommand.ChangePage(CommandId(ids.next()), pages[target].id))
                                return true
                            }
                            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                val pagingVisible = showPageButtons && pages.size > 1
                                if (pagingVisible) CommunicationIconButton(CommunicationIcon.Previous, "Предыдущая страница",
                                    pageTarget(presentation.pageIndex, pages.size, -1, presentation.commandsEnabled) != null,
                                    { changePage(-1) }, Modifier.width(48.dp).fillMaxHeight())
                                val page = presentation.page
                                key(state.message.cursor?.boardId, page?.id, state.message.cursor?.firstCell) {
                                    CommunicationGrid(
                                        page, state.message.cursor?.firstCell ?: 0,
                                        presentation.selectedCards, presentation.commandsEnabled,
                                        presentation.composing, images,
                                        { position ->
                                            if (page != null && presentation.commandsEnabled) dispatch(
                                                CommunicationCommand.Select(CommandId(ids.next()), page.id, position),
                                            )
                                        },
                                        Modifier.weight(1f).fillMaxHeight(),
                                    )
                                }
                                if (pagingVisible) CommunicationIconButton(CommunicationIcon.Next, "Следующая страница",
                                    pageTarget(presentation.pageIndex, pages.size, 1, presentation.commandsEnabled) != null,
                                    { changePage(1) }, Modifier.width(48.dp).fillMaxHeight())
                            }
                            if (presentation.page != null && pages.size > 1) Text(
                                "Страница ${presentation.pageIndex + 1} из ${pages.size}",
                                modifier = Modifier.align(Alignment.CenterHorizontally).semantics {
                                    customActions = listOfNotNull(
                                        if (pageTarget(presentation.pageIndex, pages.size, -1, presentation.commandsEnabled) != null)
                                            CustomAccessibilityAction("Предыдущая страница") { changePage(-1) } else null,
                                        if (pageTarget(presentation.pageIndex, pages.size, 1, presentation.commandsEnabled) != null)
                                            CustomAccessibilityAction("Следующая страница") { changePage(1) } else null,
                                    )
                                },
                            )
                        }
                    }
                }
                }
            }
        }
    }
    }
}

@Composable
private fun Modifier.communicationPaging(textFocused: Boolean, enabled: Boolean, onPage: (Int) -> Unit): Modifier {
    val activation = remember { PageKeyActivation() }
    return onFocusChanged { if (!it.hasFocus) activation.reset() }.onPreviewKeyEvent { event ->
        val direction = when (event.key) {
            Key.PageUp -> -1
            Key.PageDown -> 1
            else -> return@onPreviewKeyEvent false
        }
        if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) false
        else activation.event(direction, event.type == KeyEventType.KeyDown, textFocused,
            event.isAltPressed || event.isCtrlPressed || event.isMetaPressed || event.isShiftPressed, enabled, onPage)
    }
}
