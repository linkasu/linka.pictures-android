package su.linka.pictures.ui.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import su.linka.pictures.text.dropLastGrapheme

@Composable
internal fun CommunicationCaption(
    label: String,
    availableWidth: Dp,
    availableHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val captionStyle = MaterialTheme.typography.body1.copy(
        fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
        lineBreak = LineBreak.Heading, hyphens = Hyphens.Auto,
    )
    val captionSize = remember(label, availableHeight, availableWidth, captionStyle, density.density, textMeasurer) {
        // Fit base typography only; the displayed Text keeps the inherited accessibility scale.
        val baseDensity = Density(density.density, fontScale = 1f)
        communicationCaptionSize(
            label, availableHeight.value, with(baseDensity) { availableWidth.toPx() },
        ) { word, size ->
            textMeasurer.measure(
                text = word, style = captionStyle.copy(fontSize = size.sp),
                softWrap = false, density = baseDensity,
            ).multiParagraph.maxIntrinsicWidth
        }.sp
    }
    val renderedStyle = captionStyle.copy(
        fontSize = captionSize, lineHeight = captionSize * 1.2f,
        textAlign = TextAlign.Center,
    )
    val captionWidth = remember(label, availableWidth, renderedStyle, density.density, density.fontScale, textMeasurer) {
        val widthPx = with(density) { availableWidth.toPx() }
        if (label.isEmpty() || label.any { it.isWhitespace() } || !widthPx.isFinite() || widthPx <= 1f) {
            availableWidth
        } else {
            val originalWidth = widthPx.roundToInt()
            fun measure(width: Int) = textMeasurer.measure(
                text = label, style = renderedStyle, density = density,
                constraints = Constraints(maxWidth = width), softWrap = true,
            )
            val original = measure(originalWidth)
            val lastLine = original.lineCount - 1
            val lastText = label.substring(original.getLineStart(lastLine), original.getLineEnd(lastLine, visibleEnd = true))
            val hasOrphan = original.lineCount > 1 && lastText.isNotEmpty() && dropLastGrapheme(lastText).isEmpty()
            val balancedWidth = communicationCaptionWidth(originalWidth, original.lineCount, hasOrphan) { measure(it).lineCount }
            if (balancedWidth == originalWidth) availableWidth else with(density) { balancedWidth.toDp() }
        }
    }
    key(label) {
        val scrollState = rememberScrollState()
        Text(
            label, modifier = modifier.widthIn(max = captionWidth)
                .then(if (availableHeight.value.isFinite()) Modifier.verticalScroll(scrollState) else Modifier),
            style = renderedStyle, softWrap = true,
        )
    }
}
