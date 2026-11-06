package su.linka.pictures.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
            val captionSize = if (constraints.hasBoundedHeight) (maxHeight.value / 6f).sp else 14.sp
            Layout(content = {
                Box(contentAlignment = Alignment.Center) {
                    if (image != null) Image(
                        bitmap = image, contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    )
                }
                Text(label, style = MaterialTheme.typography.body1.copy(
                    fontSize = captionSize, lineHeight = captionSize * 1.2f,
                    fontWeight = FontWeight.Normal, letterSpacing = 0.sp,
                ), textAlign = TextAlign.Center, softWrap = true)
            }, measurePolicy = CommunicationCardMeasurePolicy)
        }
    }
}
