package su.linka.pictures.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.LocalContentAlpha
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

internal val CommunicationCaptionStyle = TextStyle(
    fontSize = 24.sp, lineHeight = 28.8.sp, fontWeight = FontWeight.Bold,
    letterSpacing = 0.sp, lineBreak = LineBreak.Simple, hyphens = Hyphens.None,
    textAlign = TextAlign.Center,
)

@Composable
internal fun CommunicationCaption(
    label: String,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val renderedStyle = MaterialTheme.typography.body1.merge(CommunicationCaptionStyle)
    if (label == "⎵") {
        val color = renderedStyle.color.takeOrElse { LocalContentColor.current.copy(alpha = LocalContentAlpha.current) }
        val density = LocalDensity.current
        val width = with(density) { renderedStyle.fontSize.toDp() }
        val height = with(density) { renderedStyle.lineHeight.toDp() }
        Canvas(modifier.widthIn(max = availableWidth).size(width, height)) {
            val bounds = communicationSpaceMarkBounds(size)
            val path = Path().apply {
                moveTo(bounds.left, bounds.top)
                lineTo(bounds.left, bounds.bottom)
                lineTo(bounds.right, bounds.bottom)
                lineTo(bounds.right, bounds.top)
            }
            drawPath(path, color, style = Stroke(width = size.minDimension * 0.08f))
        }
    } else {
        Text(label, modifier = modifier.widthIn(max = availableWidth), style = renderedStyle, softWrap = true)
    }
}

internal fun communicationSpaceMarkBounds(size: Size): Rect =
    Rect(size.width * 0.15f, size.height * 0.45f, size.width * 0.85f, size.height * 0.7f)
