package su.linka.pictures.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (selected) "✓" else "\u00a0", style = MaterialTheme.typography.h6)
            // Reserve the same bounded square before and after the caller supplies pixels.
            Box(Modifier.widthIn(max = 160.dp).fillMaxWidth().aspectRatio(1f)) {
                if (image != null) Image(
                    bitmap = image, contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(label, style = MaterialTheme.typography.h6, textAlign = TextAlign.Center, softWrap = true)
        }
    }
}
