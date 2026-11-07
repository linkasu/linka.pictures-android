package su.linka.pictures.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Colors
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

val LocalLinkaPalette = staticCompositionLocalOf { linkaPalette(false, false) }

object LinkaTargets {
    val communicationCard = LinkaMetrics.touchTarget
    val actionButton = LinkaMetrics.touchTarget
    val focusRing = LinkaMetrics.focusRing
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
    CompositionLocalProvider(
        LocalLinkaPalette provides palette,
        LocalDensity provides Density(density.density, linkaFontScale(density.fontScale, textScale)),
    ) {
        MaterialTheme(
            colors = linkaMaterialColors(darkTheme, palette),
            content = content,
        )
    }
}

internal fun linkaMaterialColors(darkTheme: Boolean, palette: LinkaPalette): Colors {
    val colors = if (darkTheme) darkColors() else lightColors()
    return colors.copy(
        primary = palette.primary, primaryVariant = palette.primary,
        // Brand yellow cannot identify selected controls against a white surface.
        secondary = palette.primary, secondaryVariant = palette.primary,
        onPrimary = palette.onPrimary, onSecondary = palette.onPrimary,
        background = palette.background, surface = palette.surface,
        onBackground = palette.text, onSurface = palette.text,
        error = palette.danger, onError = palette.onDanger,
    )
}
