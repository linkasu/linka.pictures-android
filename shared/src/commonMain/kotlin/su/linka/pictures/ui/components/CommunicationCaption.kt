package su.linka.pictures.ui.components

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

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
    Text(label, modifier = modifier, style = captionStyle.copy(
        fontSize = captionSize, lineHeight = captionSize * 1.2f,
    ), textAlign = TextAlign.Center, softWrap = true)
}
