package su.linka.pictures.ui.communication

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.communication.CommunicationCommand
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.application.ports.IdSource
import su.linka.pictures.application.ports.VoiceCapability
import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.CommandId
import su.linka.pictures.ui.components.LabeledActionButton
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
) {
    val presentation = remember(state, voices) { communicationPresentation(state, voices) }
    var input by rememberSaveable(state.stamp.epoch) { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    val palette = LocalLinkaPalette.current
    val dispatch: (CommunicationCommand) -> Unit = { if (presentation.commandsEnabled) onCommand(it) }
    VisibleImageDemand(state.stamp.epoch, onImagesNeeded) {
    val paging = Modifier.communicationPaging(inputFocused, presentation.commandsEnabled) { direction ->
        val pages = state.board?.document?.pages.orEmpty()
        pageTarget(presentation.pageIndex, pages.size, direction, presentation.commandsEnabled)?.let {
            dispatch(CommunicationCommand.ChangePage(CommandId(ids.next()), pages[it].id))
        }
    }
    Surface(modifier.fillMaxSize().then(paging), color = palette.background, contentColor = palette.text) {
        CompositionLocalProvider(LocalContentAlpha provides 1f) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val controls: @Composable (Modifier) -> Unit = { paneModifier ->
                        Column(paneModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CommunicationMessage(state, images, Modifier.weight(1f).fillMaxWidth())
                            CommunicationControls(
                                state, presentation, ids, dispatch,
                                { if (presentation.commandsEnabled) onOpenLibrary() },
                                input, { if (presentation.commandsEnabled) input = it },
                                Modifier.weight(1f).fillMaxWidth(),
                                showPageButtons,
                                { inputFocused = it },
                            )
                        }
                    }
                    val grid: @Composable (Modifier) -> Unit = { paneModifier ->
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
                                paneModifier,
                            )
                        }
                    }
                    if (maxWidth >= 480.dp && maxWidth > maxHeight) {
                        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            controls(Modifier.weight(1f).fillMaxSize())
                            grid(Modifier.weight(2f).fillMaxSize())
                        }
                    } else {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            controls(Modifier.weight(1f).fillMaxSize())
                            grid(Modifier.weight(1f).fillMaxSize())
                        }
                    }
                }
                CommunicationMessageActions(presentation, ids, dispatch)
                LabeledActionButton(
                    if (presentation.stopping) "Остановить" else "Произнести",
                    presentation.speechEnabled, false,
                    {
                        val id = CommandId(ids.next())
                        dispatch(if (presentation.stopping) CommunicationCommand.Stop(id) else CommunicationCommand.Speak(id))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
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
