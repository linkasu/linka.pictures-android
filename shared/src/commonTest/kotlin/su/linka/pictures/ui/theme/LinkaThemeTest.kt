package su.linka.pictures.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinkaThemeTest {
    @Test
    fun materialSelectionUsesAccessibleFunctionalColorsInEveryTheme() {
        for (dark in listOf(false, true)) for (highContrast in listOf(false, true)) {
            val palette = linkaPalette(dark, highContrast)
            val colors = linkaMaterialColors(dark, palette)
            assertEquals(palette.primary, colors.secondary)
            assertEquals(palette.primary, colors.secondaryVariant)
            assertEquals(palette.onPrimary, colors.onSecondary)
            assertEquals(palette.primary, colors.primary)
            assertEquals(palette.onPrimary, colors.onPrimary)
            assertEquals(if (!dark && !highContrast) Linka14.primaryDark else palette.primary, colors.primaryVariant)
            assertEquals(palette.surface, colors.surface)
            assertEquals(palette.background, colors.background)
            assertEquals(palette.text, colors.onSurface)
            assertEquals(palette.text, colors.onBackground)
            assertEquals(palette.danger, colors.error)
            assertEquals(palette.onDanger, colors.onError)
            assertEquals(!dark, colors.isLight)
            for (background in listOf(colors.surface, colors.background, palette.disabledSurface,
                palette.pressedSurface(colors.surface, colors.onSurface))) {
                assertContrast(colors.secondary, background, 3f)
            }
            val textMinimum = if (highContrast) 7f else 4.5f
            assertContrast(colors.onSecondary, colors.secondary, textMinimum)
            assertContrast(colors.onSecondary, palette.pressedSurface(colors.secondary, colors.onSecondary), textMinimum)
            assertContrast(palette.disabledText, palette.disabledSurface, textMinimum)
        }
    }

    @Test
    fun brandAccentRemainsSuitableForBlackFabContentNotSelectionOnWhite() {
        assertContrast(Color.Black, Linka14.accent, 4.5f)
        assertTrue(contrastRatio(Linka14.accent, Color.White) < 3f)
        val colors = linkaMaterialColors(false, linkaPalette(false, false))
        assertContrast(colors.secondary, Color.White, 3f)
        assertContrast(colors.secondary, Color.White, 4.5f)
    }

    @Test
    fun allPalettesKeepContrastInNormalDisabledAndPressedStates() {
        for (dark in listOf(false, true)) for (highContrast in listOf(false, true)) {
            val p = linkaPalette(dark, highContrast)
            val textMinimum = if (highContrast) 7f else 4.5f
            fun check(foreground: Color, background: Color, minimum: Float) {
                val a = foreground.luminance()
                val b = background.luminance()
                val ratio = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
                assertEquals(1f, foreground.alpha)
                assertEquals(1f, background.alpha)
                assertTrue(ratio >= minimum, "dark=$dark highContrast=$highContrast: $foreground / $background = $ratio < $minimum")
            }
            for (background in listOf(p.surface, p.background)) {
                check(p.text, background, textMinimum)
                check(p.secondaryText, background, textMinimum)
                check(p.border, background, 3f)
                check(p.focus, background, 3f)
            }
            check(p.disabledText, p.disabledSurface, textMinimum)
            check(p.border, p.disabledSurface, 3f)
            check(p.primary, p.surface, 3f)
            check(p.primary, p.disabledSurface, 3f)
            for ((surface, content) in listOf(p.surface to p.text, p.primary to p.onPrimary, p.danger to p.onDanger)) {
                check(content, surface, textMinimum)
                check(content, p.pressedSurface(surface, content), textMinimum)
            }
            val pressedCard = p.pressedSurface(p.surface, p.text)
            check(p.border, pressedCard, 3f)
            check(p.focus, pressedCard, 3f)
            check(p.primary, pressedCard, 3f)
        }
    }

    @Test
    fun portableScaleIsBoundedWithoutClampingSystemScale() {
        assertEquals(1f, linkaFontScale(1f, 1f))
        assertEquals(6f, linkaFontScale(2f, 3f))
        assertEquals(12f, linkaFontScale(4f, 99f))
        assertEquals(2f, linkaFontScale(4f, -1f))
        assertEquals(0.4f, linkaFontScale(0.8f, 0.5f))
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { linkaFontScale(1f, invalid) }
            assertFailsWith<IllegalArgumentException> { linkaFontScale(invalid, 1f) }
        }
        assertFailsWith<IllegalArgumentException> { linkaFontScale(0f, 1f) }
    }

    @Test
    fun targetsMeetBothPlatformMinimumsWithoutOverridingTheGrid() {
        assertEquals(48f, LinkaTargets.communicationCard.value)
        assertEquals(48f, LinkaTargets.actionButton.value)
        assertTrue(LinkaTargets.communicationCard.value >= 48f)
        assertTrue(LinkaTargets.actionButton.value >= 48f)
        assertEquals(3f, LinkaTargets.focusRing.value)
    }

    @Test
    fun defaultPaletteUses14ResourcesAndBlackCardCaptions() {
        val p = linkaPalette(false, false)
        assertEquals(Color(0xFF197377), p.primary)
        assertEquals(Color(0xFFBED64F), Linka14.primaryDark)
        assertEquals(Color(0xFFFBCC30), Linka14.accent)
        assertEquals(Color.White, p.background)
        assertEquals(Color.White, p.surface)
        assertEquals(Color.Black, p.text)
        assertEquals(Color(0xFFE0E0E0), p.disabledSurface)
        for (surface in listOf(Linka14.cardTop, Linka14.cardBottom)) {
            assertTrue((surface.luminance() + 0.05f) / 0.05f >= 4.5f)
            val pressed = p.pressedSurface(surface, p.text)
            assertEquals(1f, pressed.alpha)
            assertTrue(pressed.luminance() < surface.luminance())
            assertTrue((pressed.luminance() + 0.05f) / 0.05f >= 4.5f)
        }
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val a = foreground.luminance()
        val b = background.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        assertEquals(1f, foreground.alpha)
        assertEquals(1f, background.alpha)
        val ratio = contrastRatio(foreground, background)
        assertTrue(ratio >= minimum, "$foreground / $background = $ratio < $minimum")
    }
}
