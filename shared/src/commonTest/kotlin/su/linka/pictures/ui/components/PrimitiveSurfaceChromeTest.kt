package su.linka.pictures.ui.components

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
    fun flatCardOutlinesKeepContrastInEveryPaletteAndState() {
        for (dark in listOf(false, true)) for (highContrast in listOf(false, true)) {
            val palette = linkaPalette(dark, highContrast)
            for (selected in listOf(false, true)) {
                val border = assertNotNull(PrimitiveSurfaceChrome.border(palette, selected))
                val color = if (selected) palette.primary else palette.border
                assertEquals(SolidColor(color), border.brush)
                assertEquals(if (selected) 3.dp else 1.dp, border.width)
                for (surface in listOf(palette.surface, palette.disabledSurface,
                    palette.pressedSurface(palette.surface, palette.text))) {
                    val a = color.luminance()
                    val b = surface.luminance()
                    assertTrue((maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f) >= 3f)
                }
            }
            assertNull(PrimitiveSurfaceChrome.border(palette, null))
        }
    }

    @Test
    fun explicitKindDoesNotInferACardFromSelectionSemantics() {
        val palette = linkaPalette(false, false)
        assertNotNull(PrimitiveSurfaceChrome.border(palette, null, PrimitiveSurfaceKind.CARD))
        assertNull(PrimitiveSurfaceChrome.border(palette, false, PrimitiveSurfaceKind.ACTION))
        assertEquals(SolidColor(palette.border), assertNotNull(
            PrimitiveSurfaceChrome.border(palette, null, PrimitiveSurfaceKind.SECONDARY_ACTION),
        ).brush)
    }
}
