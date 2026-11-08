package su.linka.pictures.ui.editor

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.authoring.AuthoringAction
import su.linka.pictures.application.authoring.AuthoringStamp
import su.linka.pictures.domain.*
import su.linka.pictures.ui.images.DemandVisibleImages
import su.linka.pictures.ui.theme.LocalLinkaPalette

/** Both axes fit the ordinary grid; exceptional grids / large text scroll without shrinking targets. */
@Composable
internal fun EditorPageCells(
    page: BoardPage,
    enabled: Boolean,
    moveSource: Pair<PageId, Int>?,
    onMoveSource: (Pair<PageId, Int>?) -> Unit,
    onAction: (AuthoringAction) -> Unit,
    images: Map<AssetId, ImageBitmap>,
    modifier: Modifier = Modifier,
    activePosition: Int? = null,
    selectionEnabled: Boolean = enabled,
    onSelect: (Int) -> Unit = { onAction(AuthoringAction.OpenCard(page.id, it)) },
    dragStamp: AuthoringStamp? = null,
) {
    val palette = LocalLinkaPalette.current
    val fontScale = LocalDensity.current.fontScale
    val drag = remember(page.id) { EditorCellDrag() }
    val bounds = remember(page.id) { mutableMapOf<Int, Rect>() }
    var gridOrigin by remember(page.id) { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.onGloballyPositioned { gridOrigin = it.positionInRoot() }
        .pointerInput(drag, gridOrigin) {
            try {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    drag.resetTapSuppression()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        // Include the release position, not merely the last consumed drag movement.
                        event.changes.firstOrNull { it.id == down.id }?.let {
                            drag.move(it.position + gridOrigin, bounds)
                        }
                    } while (event.changes.any { it.pressed })
                    awaitPointerEvent(PointerEventPass.Final)
                    drag.resetTapSuppression()
                }
            } finally { drag.cancel() }
        }) {
        val cellWidth = editorCellExtent(maxWidth.value, page.columns, 64f * fontScale.coerceAtLeast(1f)).dp
        val cellHeight = editorCellExtent(maxHeight.value, page.rows, 80f * fontScale.coerceAtLeast(1f)).dp
        val cells = remember(page) { page.cells.associateBy { it.position } }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(page.rows, key = { "${page.id.value}:$it" }) { row ->
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(page.columns, key = { it }) { column ->
                        val position = row * page.columns + column
                        val cell = cells[position]
                        val label = editorCellLabel(cell)?.let { stringResource(it) } ?: cell!!.card!!.label
                        val cellDescription = stringResource(Res.string.cell_position, label, row + 1, column + 1)
                        val actionsDescription = stringResource(Res.string.cell_actions_1_s, label)
                        val selectedCell = activePosition == position || moveSource == (page.id to position) ||
                            drag.source == position || drag.target == position
                        var menu by remember(page.id, position) { mutableStateOf(false) }
                        var focused by remember(page.id, position) { mutableStateOf(false) }
                        var origin by remember(page.id, position) { mutableStateOf(Offset.Zero) }
                        DemandVisibleImages(page.id to position, setOfNotNull(cell?.card?.image))
                        DisposableEffect(page.id, position) {
                            onDispose { bounds.remove(position); drag.cancel() }
                        }
                        Card(Modifier.size(cellWidth, cellHeight).onGloballyPositioned { coordinates ->
                            val next = coordinates.boundsInRoot()
                            if (bounds[position] != next) drag.cancel()
                            bounds[position] = next
                            origin = coordinates.positionInRoot()
                        }.pointerInput(drag, dragStamp, enabled, cellWidth, cellHeight, origin) {
                            if (!enabled || dragStamp == null) return@pointerInput
                            try {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { point -> drag.start(point + origin, bounds) },
                                    onDragCancel = { drag.cancel() },
                                    onDragEnd = {
                                        drag.finish()?.let { (from, to) ->
                                            onAction(AuthoringAction.MoveCell(page.id, from, page.id, to, dragStamp))
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        if (drag.source != null) change.consume()
                                    },
                                )
                            } finally { drag.cancel() }
                        }, elevation = 0.dp,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
                            border = androidx.compose.foundation.BorderStroke(if (focused || selectedCell) 2.dp else 1.dp,
                                if (focused || drag.target == position && drag.source != position) palette.focus else if (selectedCell) palette.primary else palette.border),
                            backgroundColor = if (selectedCell) palette.pressedSurface(palette.surface, palette.primary) else palette.surface) {
                            Box(Modifier.fillMaxSize()) {
                                TextButton(onClick = {
                                    if (drag.suppressTap) return@TextButton
                                    if (moveSource == null) onSelect(position)
                                    else {
                                        onAction(AuthoringAction.MoveCell(moveSource.first, moveSource.second, page.id, position))
                                        onMoveSource(null)
                                    }
                                }, enabled = selectionEnabled, contentPadding = PaddingValues(4.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = palette.text), modifier = Modifier.fillMaxSize()
                                    .onFocusChanged { focused = it.isFocused }
                                    .semantics {
                                    contentDescription = cellDescription
                                    selected = selectedCell
                                }) {
                                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            val image = cell?.card?.image?.let { images[it] }
                                            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                            else if (cell?.kind == CellKind.CONTENT) Text(cell.card!!.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                            else if (cell?.kind == CellKind.SPACE) Text("␣", style = MaterialTheme.typography.h3)
                                            else EditorIcon(EditorSymbol.ADD, Modifier.size(32.dp))
                                        }
                                        Text(label, color = palette.text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                    }
                                }
                                Box(Modifier.align(Alignment.TopEnd)) {
                                    IconButton(onClick = { if (!drag.suppressTap) menu = true }, enabled = enabled,
                                        modifier = Modifier.semantics { contentDescription = actionsDescription }) { EditorIcon(EditorSymbol.MORE) }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(onClick = { menu = false; onMoveSource(page.id to position) }, enabled = enabled) { Text(stringResource(Res.string.move)) }
                                        DropdownMenuItem(onClick = { menu = false; onAction(AuthoringAction.SetCell(page.id, position, CellKind.SPACE)) }, enabled = enabled) { Text(stringResource(Res.string.make_a_space)) }
                                        DropdownMenuItem(onClick = { menu = false; onAction(AuthoringAction.SetCell(page.id, position, CellKind.EMPTY)) }, enabled = enabled) { Text(stringResource(Res.string.clear_cell)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
