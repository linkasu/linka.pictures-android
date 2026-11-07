package su.linka.pictures.ui.components

import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import su.linka.pictures.ui.theme.LinkaMetrics

/** Caption wraps above an image minimum; loading pixels and selection cannot resize a cell. */
internal object CommunicationCardMeasurePolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val imageMinimum = if (constraints.hasBoundedHeight) minOf(LinkaMetrics.touchTarget.roundToPx(), constraints.maxHeight / 3) else 0
        val gap = if (constraints.hasBoundedHeight) minOf(LinkaMetrics.imageCaptionGap.roundToPx(), constraints.maxHeight - imageMinimum)
            else LinkaMetrics.imageCaptionGap.roundToPx()
        val caption = measurables[1].measure(constraints.copy(
            minWidth = 0, minHeight = 0,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight - imageMinimum - gap else Constraints.Infinity,
        ))
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else maxOf(constraints.minWidth, caption.width)
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.constrainHeight(width + caption.height + gap)
        val imageHeight = (height - caption.height - gap).coerceAtLeast(0)
        val image = measurables[0].measure(Constraints.fixed(width, imageHeight))
        return layout(width, height) {
            image.placeRelative(0, 0)
            caption.placeRelative((width - caption.width) / 2, imageHeight + gap)
        }
    }
}
