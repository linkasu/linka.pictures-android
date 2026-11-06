package su.linka.pictures.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
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
            Layout(content = {
                Box(contentAlignment = Alignment.Center) {
                    if (image != null) Image(
                        bitmap = image, contentDescription = null,
                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                    )
                }
                CommunicationCaption(label, maxWidth, maxHeight)
            }, measurePolicy = CommunicationCardMeasurePolicy)
        }
    }
}
