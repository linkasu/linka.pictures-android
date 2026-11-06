package su.linka.pictures.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import su.linka.pictures.ui.theme.Linka14
import su.linka.pictures.ui.theme.LinkaPalette
import su.linka.pictures.ui.theme.linkaPalette

internal object PrimitiveSurfaceChrome {
    fun border(palette: LinkaPalette, selected: Boolean?): BorderStroke? = when {
        selected == true -> BorderStroke(3.dp, palette.primary)
        // Coordinated U1.4 exception: the baseline gradient outline fails 3:1 when pressed.
        selected != null && palette == linkaPalette(false, false) -> BorderStroke(0.5.dp, Color(0xFF696969))
        selected != null -> BorderStroke(1.dp, palette.border)
        else -> null
    }

    fun cardGradient(palette: LinkaPalette, contentColor: Color, pressed: Boolean): List<Color> =
        listOf(Linka14.cardTop, Linka14.cardBottom).map {
            if (pressed) palette.pressedSurface(it, contentColor) else it
        }
}
