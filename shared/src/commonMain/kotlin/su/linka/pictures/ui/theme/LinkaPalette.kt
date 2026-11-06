package su.linka.pictures.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

@Immutable
data class LinkaPalette(
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
    val surface: Color,
    val text: Color,
    val secondaryText: Color,
    val border: Color,
    val danger: Color,
    val onDanger: Color,
    val focus: Color,
    val disabledSurface: Color,
    val disabledText: Color,
    val highContrast: Boolean,
) {
    // Keep pressed feedback opaque after compositing, including high-contrast mode.
    fun pressedSurface(surface: Color, content: Color): Color =
        content.copy(alpha = if (highContrast) 0.04f else 0.08f).compositeOver(surface)
}

fun linkaPalette(darkTheme: Boolean, highContrast: Boolean): LinkaPalette = when {
    darkTheme && highContrast -> LinkaPalette(
        Color(0xFF8FFFFF), Color.Black, Color.Black, Color.Black,
        Color.White, Color(0xFFE6E6E6), Color.White,
        Color(0xFFFFCAC4), Color.Black, Color(0xFFFFE082),
        Color.Black, Color(0xFFE6E6E6), true,
    )
    highContrast -> LinkaPalette(
        Color(0xFF004747), Color.White, Color.White, Color.White,
        Color.Black, Color(0xFF1A1A1A), Color.Black,
        Color(0xFF8F120A), Color.White, Color(0xFF002F6C),
        Color.White, Color(0xFF1A1A1A), true,
    )
    darkTheme -> LinkaPalette(
        Color(0xFF66D9D9), Color(0xFF003737), Color(0xFF0E1919), Color(0xFF172424),
        Color(0xFFE3F1F1), Color(0xFFB2C8C8), Color(0xFF8DAAAA),
        Color(0xFFFFB4AB), Color(0xFF690005), Color(0xFFFFD666),
        Color(0xFF243737), Color(0xFFB2C8C8), false,
    )
    else -> LinkaPalette(
        Linka14.primary, Color.White, Color.White, Color.White,
        Color.Black, Color(0xFF757575), Color(0xFF777777),
        Color(0xFFB42318), Color.White, Color(0xFF002F6C),
        Color(0xFFE0E0E0), Color(0xFF435B5B), false,
    )
}
