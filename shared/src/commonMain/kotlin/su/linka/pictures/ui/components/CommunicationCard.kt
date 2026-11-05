package su.linka.pictures.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
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
            if (selected) Text("✓", style = MaterialTheme.typography.h6)
            if (image != null) Image(
                bitmap = image, contentDescription = null,
                modifier = Modifier.sizeIn(maxWidth = 160.dp, maxHeight = 160.dp),
                contentScale = ContentScale.Fit,
            )
            Text(label, style = MaterialTheme.typography.h6, textAlign = TextAlign.Center, softWrap = true)
        }
    }
}
