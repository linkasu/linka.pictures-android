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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import su.linka.pictures.ui.theme.LinkaMetrics
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
    hasImage: Boolean = image != null,
    imageBesideCaption: Dp? = null,
) {
    val palette = LocalLinkaPalette.current
    PrimitiveSurface(
        accessibleName, activationLabel, enabled, selected, palette.surface, palette.text,
        onActivate, modifier.sizeIn(minWidth = LinkaMetrics.touchTarget, minHeight = LinkaMetrics.touchTarget),
        kind = PrimitiveSurfaceKind.CARD,
        description = if (!hasImage) null else if (image != null) "Изображение показано" else "Изображение ещё не показано",
    ) {
        BoxWithConstraints {
            val captionWidth = maxWidth
            if (!hasImage) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CommunicationCaption(label, captionWidth)
                }
            } else if (label.isBlank()) {
                if (image != null) Image(
                    bitmap = image, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                )
            } else if (imageBesideCaption != null) {
                Layout(content = {
                    Box(contentAlignment = Alignment.Center) {
                        if (image != null) Image(
                            bitmap = image, contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        )
                    }
                    CommunicationCaption(label, maxWidth)
                }) { measurables, constraints ->
                    val side = imageBesideCaption.roundToPx()
                    val gap = LinkaMetrics.imageCaptionGap.roundToPx()
                    val picture = measurables[0].measure(Constraints.fixed(side, side))
                    val caption = measurables[1].measure(Constraints(
                        maxWidth = (constraints.maxWidth - side - gap).coerceAtLeast(0),
                        maxHeight = Constraints.Infinity,
                    ))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        val start = (constraints.maxWidth - side - gap - caption.width) / 2
                        picture.placeRelative(start, (constraints.maxHeight - side) / 2)
                        caption.placeRelative(start + side + gap, (constraints.maxHeight - caption.height) / 2)
                    }
                }
            } else {
                Layout(content = {
                    Box(contentAlignment = Alignment.Center) {
                        if (image != null) Image(
                            bitmap = image, contentDescription = null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        )
                    }
                    CommunicationCaption(label, maxWidth)
                }, measurePolicy = CommunicationCardMeasurePolicy)
            }
        }
    }
}
