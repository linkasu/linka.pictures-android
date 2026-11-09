package su.linka.pictures.ui.communication

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import su.linka.pictures.domain.BoardPage
import su.linka.pictures.domain.CellKind
import su.linka.pictures.ui.components.CommunicationCaptionStyle
import su.linka.pictures.ui.theme.LinkaMetrics

@Composable
internal fun measuredCommunicationCells(page: BoardPage, width: Float, height: Float): CommunicationCellSize {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.body1.merge(CommunicationCaptionStyle)
    return remember(page, width, height, density, style, measurer) {
        val labels = page.cells.filter { it.kind != CellKind.EMPTY }.map {
            (if (it.kind == CellKind.SPACE) "⎵" else it.card!!.label) to (it.card?.image != null)
        }
        with(density) {
            // One width for the page: no word splitting, media-loading resize, or stored-grid changes.
            val longestWord = labels.flatMap { it.first.split(Regex("\\s+")) }.maxOfOrNull {
                measurer.measure(it, style = style, softWrap = false).size.width
            } ?: 0
            val size = communicationCellSize(width, height, page.columns, page.rows, fontScale,
                minimumWidth = (longestWord.toDp().value + 8f))
            val textWidth = (size.width.dp.roundToPx() - 8.dp.roundToPx()).coerceAtLeast(1)
            val requiredHeight = labels.maxOfOrNull { (label, hasImage) ->
                val caption = measurer.measure(label, style = style, constraints = Constraints(maxWidth = textWidth))
                caption.size.height.toDp().value + 8f + if (hasImage)
                    LinkaMetrics.touchTarget.value * maxOf(1f, fontScale) + LinkaMetrics.imageCaptionGap.value else 0f
            } ?: 0f
            communicationCellSize(width, height, page.columns, page.rows, fontScale,
                minimumWidth = size.width, minimumHeight = requiredHeight)
        }
    }
}
