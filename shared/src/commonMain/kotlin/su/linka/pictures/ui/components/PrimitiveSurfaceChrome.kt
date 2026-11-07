package su.linka.pictures.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import su.linka.pictures.ui.theme.LinkaMetrics
import su.linka.pictures.ui.theme.LinkaPalette

internal enum class PrimitiveSurfaceKind { CARD, ACTION, SECONDARY_ACTION }

internal object PrimitiveSurfaceChrome {
    fun border(
        palette: LinkaPalette,
        selected: Boolean?,
        kind: PrimitiveSurfaceKind = if (selected != null) PrimitiveSurfaceKind.CARD else PrimitiveSurfaceKind.ACTION,
    ): BorderStroke? = when {
        kind == PrimitiveSurfaceKind.CARD && selected == true -> BorderStroke(LinkaMetrics.focusRing, palette.primary)
        kind != PrimitiveSurfaceKind.ACTION -> BorderStroke(1.dp, palette.border)
        else -> null
    }

}
