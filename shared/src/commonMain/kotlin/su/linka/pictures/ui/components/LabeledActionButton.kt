package su.linka.pictures.ui.components

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import su.linka.pictures.ui.theme.LinkaMetrics
import su.linka.pictures.ui.theme.LocalLinkaPalette

enum class ActionAppearance { PRIMARY, SECONDARY, DESTRUCTIVE }

@Composable
fun LabeledActionButton(
    label: String,
    enabled: Boolean,
    destructive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    appearance: ActionAppearance = if (destructive) ActionAppearance.DESTRUCTIVE else ActionAppearance.PRIMARY,
) {
    val palette = LocalLinkaPalette.current
    PrimitiveSurface(
        label, label, enabled, null,
        when (appearance) {
            ActionAppearance.PRIMARY -> palette.primary
            ActionAppearance.SECONDARY -> palette.surface
            ActionAppearance.DESTRUCTIVE -> palette.danger
        },
        when (appearance) {
            ActionAppearance.PRIMARY -> palette.onPrimary
            ActionAppearance.SECONDARY -> palette.primary
            ActionAppearance.DESTRUCTIVE -> palette.onDanger
        },
        onClick, modifier.sizeIn(minWidth = LinkaMetrics.touchTarget, minHeight = LinkaMetrics.touchTarget),
        kind = if (appearance == ActionAppearance.SECONDARY) PrimitiveSurfaceKind.SECONDARY_ACTION else PrimitiveSurfaceKind.ACTION,
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.button, textAlign = TextAlign.Center, softWrap = true)
    }
}
