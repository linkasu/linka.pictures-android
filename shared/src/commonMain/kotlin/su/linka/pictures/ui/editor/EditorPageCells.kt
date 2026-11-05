package su.linka.pictures.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.authoring.AuthoringAction
import su.linka.pictures.domain.*
import su.linka.pictures.ui.components.CommunicationCard
import su.linka.pictures.ui.components.LabeledActionButton

/** A lazy parent supplies one row at a time; wide grids scroll rather than shrink touch targets. */
@Composable
internal fun EditorPageCells(
    page: BoardPage,
    row: Int,
    enabled: Boolean,
    moveSource: Pair<PageId, Int>?,
    onMoveSource: (Pair<PageId, Int>?) -> Unit,
    onAction: (AuthoringAction) -> Unit,
    images: Map<AssetId, ImageBitmap>,
) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(page.columns) { column ->
            val position = row * page.columns + column
            val cell = page.cells.firstOrNull { it.position == position }
            val label = when (cell?.kind) {
                CellKind.CONTENT -> cell.card!!.label.ifEmpty { "Карточка без подписи" }
                CellKind.SPACE -> "Пробел"
                CellKind.EMPTY, null -> "Пустая ячейка"
            }
            Column(Modifier.width(176.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Строка ${row + 1}, столбец ${column + 1}")
                CommunicationCard(label, "$label. Строка ${row + 1}, столбец ${column + 1}",
                    if (moveSource == null) "Редактировать карточку" else "Обменять ячейки",
                    cell?.card?.image?.let { images[it] }, moveSource == (page.id to position), enabled,
                    {
                        if (moveSource == null) onAction(AuthoringAction.OpenCard(page.id, position))
                        else {
                            onAction(AuthoringAction.MoveCell(moveSource.first, moveSource.second, page.id, position))
                            onMoveSource(null)
                        }
                    }, Modifier.fillMaxWidth())
                LabeledActionButton("Выбрать для перемещения", enabled, false,
                    { onMoveSource(page.id to position) }, Modifier.fillMaxWidth())
                LabeledActionButton("Сделать пробелом", enabled, cell?.card != null,
                    { onAction(AuthoringAction.SetCell(page.id, position, CellKind.SPACE)) }, Modifier.fillMaxWidth())
                LabeledActionButton("Очистить ячейку", enabled, true,
                    { onAction(AuthoringAction.SetCell(page.id, position, CellKind.EMPTY)) }, Modifier.fillMaxWidth())
            }
        }
    }
}
