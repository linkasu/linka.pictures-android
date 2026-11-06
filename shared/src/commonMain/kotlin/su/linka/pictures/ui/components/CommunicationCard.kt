package su.linka.pictures.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import su.linka.pictures.ui.theme.LinkaTargets
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
fun CommunicationCard(
    label: String,
    accessibleName: String,
    activationLabel: String,
    image: ImageBitmap?,
    selected: Boolean,
    enabled: Boolean,
    onActivate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLinkaPalette.current
    PrimitiveSurface(
        accessibleName, activationLabel, enabled, selected, palette.surface, palette.text,
        onActivate, modifier.sizeIn(minWidth = LinkaTargets.communicationCard, minHeight = LinkaTargets.communicationCard),
    ) {
        BoxWithConstraints {
            // The old 4:1 image/caption split scales with the parent, but text is no longer a bitmap.
            val density = LocalDensity.current
            val textMeasurer = rememberTextMeasurer()
            val captionStyle = MaterialTheme.typography.body1.copy(
                fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
            )
            val captionSize = remember(label, maxHeight, constraints.maxWidth, captionStyle, density.density, textMeasurer) {
                // Fit base typography only; the displayed Text keeps the inherited accessibility scale.
                val baseDensity = Density(density.density, fontScale = 1f)
                communicationCaptionSize(
                    label, maxHeight.value,
                    if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.POSITIVE_INFINITY,
                ) { word, size ->
                    textMeasurer.measure(
                        text = word, style = captionStyle.copy(fontSize = size.sp),
                        softWrap = false, density = baseDensity,
                    ).multiParagraph.maxIntrinsicWidth
                }.sp
            }
            Layout(content = {
                Box(contentAlignment = Alignment.Center) {
                    if (image != null) Image(
                        bitmap = image, contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    )
                }
                Text(label, style = captionStyle.copy(
                    fontSize = captionSize, lineHeight = captionSize * 1.2f,
                ), textAlign = TextAlign.Center, softWrap = true)
            }, measurePolicy = CommunicationCardMeasurePolicy)
        }
    }
}
