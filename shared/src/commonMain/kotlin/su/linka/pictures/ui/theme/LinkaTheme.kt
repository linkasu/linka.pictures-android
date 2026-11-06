package su.linka.pictures.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

val LocalLinkaPalette = staticCompositionLocalOf { linkaPalette(false, false) }

object LinkaTargets {
    val communicationCard = 48.dp
    val actionButton = 48.dp
    val focusRing = 3.dp
}

fun linkaFontScale(systemFontScale: Float, textScale: Float): Float {
    require(systemFontScale.isFinite() && systemFontScale > 0f)
    require(textScale.isFinite())
    return systemFontScale * textScale.coerceIn(0.5f, 3f)
}

@Composable
fun LinkaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = false,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val palette = linkaPalette(darkTheme, highContrast)
    val density = LocalDensity.current
    val colors = if (darkTheme) darkColors() else lightColors()
    val baseline = !darkTheme && !highContrast
    CompositionLocalProvider(
        LocalLinkaPalette provides palette,
        LocalDensity provides Density(density.density, linkaFontScale(density.fontScale, textScale)),
    ) {
        MaterialTheme(
            colors = colors.copy(
                primary = palette.primary, primaryVariant = if (baseline) Linka14.primaryDark else palette.primary,
                secondary = if (baseline) Linka14.accent else palette.primary,
                secondaryVariant = if (baseline) Linka14.accent else palette.primary,
                onPrimary = palette.onPrimary, onSecondary = if (baseline) Color.Black else palette.onPrimary,
                background = palette.background, surface = palette.surface,
                onBackground = palette.text, onSurface = palette.text,
                error = palette.danger, onError = palette.onDanger,
            ),
            content = content,
        )
    }
}
