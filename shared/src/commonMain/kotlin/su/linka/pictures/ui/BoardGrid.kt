package su.linka.pictures.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import su.linka.pictures.BoardDocument

@Composable
internal fun BoardGrid(
    document: BoardDocument,
    pagination: BoardPagination,
    editing: Boolean,
    enabled: Boolean,
    onCard: (Int) -> Unit,
    image: @Composable (String?, String?, Modifier) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit = {},
) {
    BoxWithConstraints(modifier) {
        // Keep logical pages intact, but wrap columns on narrow windows and large font scales.
        val columns = minOf(document.columns.coerceAtLeast(1), ((maxWidth + 8.dp) / 104.dp).toInt().coerceAtLeast(1))
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            items(pagination.pageSize) { offset ->
                val index = pagination.firstIndex + offset
                PictureCard(document.cards.getOrNull(index), editing, enabled, { onCard(index) }, image)
            }
        }
    }
}
