package su.linka.pictures.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import su.linka.pictures.ui.theme.linkaPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrimitiveSurfaceChromeTest {
    @Test
    fun actualDefaultOutlinePassesAllGradientEndsIncludingPressedAndDisabled() {
        val palette = linkaPalette(false, false)
        for (selected in listOf(false, true)) {
            val border = assertNotNull(PrimitiveSurfaceChrome.border(palette, selected))
            val brush = border.brush
            assertTrue(brush is SolidColor, "The whole outline must have the measured colour, not a lighter gradient edge")
            val outline = brush.value
            assertEquals(if (selected) palette.primary else Color(0xFF696969), outline)
            assertEquals(if (selected) 3.dp else 0.5.dp, border.width)
            for (enabled in listOf(false, true)) for (pressed in listOf(false, true)) {
                val surfaces = if (enabled) PrimitiveSurfaceChrome.cardGradient(palette, palette.text, pressed)
                    else listOf(palette.disabledSurface)
                for (surface in surfaces) {
                    val a = outline.luminance()
                    val b = surface.luminance()
                    val contrast = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                    assertEquals(1f, outline.alpha)
                    assertEquals(1f, surface.alpha)
                    assertTrue(contrast >= 3f, "selected=$selected enabled=$enabled pressed=$pressed: $outline / $surface = $contrast")
                }
            }
        }
    }

    @Test
    fun darkHighContrastAndActionBordersKeepTheirExistingChoices() {
        for ((dark, highContrast) in listOf(true to false, false to true, true to true)) {
            val palette = linkaPalette(dark, highContrast)
            for (selected in listOf(false, true)) {
                val border = assertNotNull(PrimitiveSurfaceChrome.border(palette, selected))
                assertEquals(if (selected) 3.dp else 1.dp, border.width)
                assertEquals(SolidColor(if (selected) palette.primary else palette.border), border.brush)
            }
            assertNull(PrimitiveSurfaceChrome.border(palette, null))
        }
        assertNull(PrimitiveSurfaceChrome.border(linkaPalette(false, false), null))
    }
}
