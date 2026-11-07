package su.linka.pictures.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.authoring.AuthoringAction
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
) {
    val palette = LocalLinkaPalette.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier) {
        val cellWidth = editorCellExtent(maxWidth.value, page.columns, 64f * fontScale.coerceAtLeast(1f)).dp
        val cellHeight = editorCellExtent(maxHeight.value, page.rows, 80f * fontScale.coerceAtLeast(1f)).dp
        val cells = remember(page) { page.cells.associateBy { it.position } }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(page.rows, key = { "${page.id.value}:$it" }) { row ->
                LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(page.columns, key = { it }) { column ->
                        val position = row * page.columns + column
                        val cell = cells[position]
                        val label = editorCellLabel(cell)
                        val selectedCell = activePosition == position || moveSource == (page.id to position)
                        var menu by remember(page.id, position) { mutableStateOf(false) }
                        var focused by remember(page.id, position) { mutableStateOf(false) }
                        DemandVisibleImages(page.id to position, setOfNotNull(cell?.card?.image))
                        Card(Modifier.size(cellWidth, cellHeight), elevation = 0.dp,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
                            border = androidx.compose.foundation.BorderStroke(if (focused || selectedCell) 2.dp else 1.dp,
                                if (focused) palette.focus else if (selectedCell) palette.primary else palette.border),
                            backgroundColor = if (selectedCell) palette.pressedSurface(palette.surface, palette.primary) else palette.surface) {
                            Box(Modifier.fillMaxSize()) {
                                TextButton(onClick = {
                                    if (moveSource == null) onAction(AuthoringAction.OpenCard(page.id, position))
                                    else {
                                        onAction(AuthoringAction.MoveCell(moveSource.first, moveSource.second, page.id, position))
                                        onMoveSource(null)
                                    }
                                }, enabled = enabled, contentPadding = PaddingValues(4.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = palette.text), modifier = Modifier.fillMaxSize()
                                    .onFocusChanged { focused = it.isFocused }
                                    .semantics {
                                    contentDescription = "$label. Строка ${row + 1}, столбец ${column + 1}"
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
                                    IconButton(onClick = { menu = true }, enabled = enabled,
                                        modifier = Modifier.semantics { contentDescription = "Действия ячейки: $label" }) { EditorIcon(EditorSymbol.MORE) }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(onClick = { menu = false; onMoveSource(page.id to position) }, enabled = enabled) { Text("Переместить…") }
                                        DropdownMenuItem(onClick = { menu = false; onAction(AuthoringAction.SetCell(page.id, position, CellKind.SPACE)) }, enabled = enabled) { Text("Сделать пробелом") }
                                        DropdownMenuItem(onClick = { menu = false; onAction(AuthoringAction.SetCell(page.id, position, CellKind.EMPTY)) }, enabled = enabled) { Text("Очистить ячейку") }
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
