package su.linka.pictures.ui.components

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import su.linka.pictures.ui.theme.LinkaTargets
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
fun LabeledActionButton(
    label: String,
    enabled: Boolean,
    destructive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLinkaPalette.current
    PrimitiveSurface(
        label, label, enabled, null,
        if (destructive) palette.danger else palette.primary,
        if (destructive) palette.onDanger else palette.onPrimary,
        onClick, modifier.sizeIn(minWidth = LinkaTargets.actionButton, minHeight = LinkaTargets.actionButton),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.button, textAlign = TextAlign.Center, softWrap = true)
    }
}
