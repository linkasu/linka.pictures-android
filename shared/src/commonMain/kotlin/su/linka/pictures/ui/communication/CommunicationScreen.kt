package su.linka.pictures.ui.communication

import androidx.compose.foundation.layout.*
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
internal fun CommunicationScreen(
    state: CommunicationState,
    ids: IdSource,
    voices: VoiceCapability,
    onCommand: (CommunicationCommand) -> Unit,
    onOpenLibrary: () -> Unit,
    layout: FixedCommunicationBoardLayout,
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
        val pages = state.board?.document?.pages.orEmpty()
        fun changePage(direction: Int): Boolean {
            val target = pageTarget(presentation.pageIndex, pages.size, direction, presentation.commandsEnabled) ?: return false
            dispatch(CommunicationCommand.ChangePage(CommandId(ids.next()), pages[target].id))
            return true
        }
        val paging = Modifier.communicationPaging(inputFocused, presentation.commandsEnabled) { changePage(it) }
        Surface(modifier.fillMaxSize().then(paging), color = palette.background, contentColor = palette.text) {
            CompositionLocalProvider(LocalContentAlpha provides 1f) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
                    CommunicationControls(
                        state.board?.document?.name ?: "Общение", presentation, ids, dispatch,
                        onOpenLibrary, onOpenBoardSettings,
                        input, { if (presentation.commandsEnabled) input = it }, { inputFocused = it },
                        layout.toolbarHeight.dp,
                    )
                    Row(Modifier.fillMaxWidth().height(layout.phraseHeight.dp)) {
                        val action = Modifier.width(layout.actionWidth.dp).fillMaxHeight()
                        speechAction(action)
                        CommunicationMessage(state, images, Modifier.weight(1f).fillMaxHeight())
                        backspaceAction(action)
                        clearAction(action)
                    }
                    Divider(color = palette.divider, thickness = 1.dp)
                    val page = presentation.page
                    val gridModifier = Modifier.fillMaxWidth().height(layout.gridHeight.dp)
                    key(state.message.cursor?.boardId, page?.id, state.message.cursor?.firstCell) {
                        if (page == null) Box(gridModifier) else CommunicationGrid(
                            page, state.message.cursor?.firstCell ?: 0,
                            presentation.selectedCards, presentation.commandsEnabled,
                            presentation.composing, images,
                            { position ->
                                if (presentation.commandsEnabled) dispatch(
                                    CommunicationCommand.Select(CommandId(ids.next()), page.id, position),
                                )
                            },
                            pageLayout = layout.pages.getValue(page.id),
                            modifier = gridModifier,
                        )
                    }
                    Row(Modifier.fillMaxWidth().height(layout.footerHeight.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (page != null && pages.size > 1) {
                            val pagingVisible = showPageButtons && pages.size > 1
                            val pageAction: @Composable (Int) -> Unit = { direction ->
                                CommunicationIconButton(if (direction < 0) CommunicationIcon.Previous else CommunicationIcon.Next,
                                    if (direction < 0) "Предыдущая страница" else "Следующая страница",
                                    pageTarget(presentation.pageIndex, pages.size, direction, presentation.commandsEnabled) != null,
                                    { changePage(direction) }, Modifier.size(48.dp))
                            }
                            if (pagingVisible) pageAction(-1)
                            Text(
                                "Страница ${presentation.pageIndex + 1} из ${pages.size}",
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.weight(1f).clearAndSetSemantics {
                                    contentDescription = "Страница ${presentation.pageIndex + 1} из ${pages.size}"
                                    customActions = listOfNotNull(
                                        if (pageTarget(presentation.pageIndex, pages.size, -1, presentation.commandsEnabled) != null)
                                            CustomAccessibilityAction("Предыдущая страница") { changePage(-1) } else null,
                                        if (pageTarget(presentation.pageIndex, pages.size, 1, presentation.commandsEnabled) != null)
                                            CustomAccessibilityAction("Следующая страница") { changePage(1) } else null,
                                    )
                                },
                            )
                            if (pagingVisible) pageAction(1)
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
