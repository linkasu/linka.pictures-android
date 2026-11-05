package su.linka.pictures.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinkaThemeTest {
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
    fun targetsExceedBothPlatformMinimumsAndFocusIsSeparate() {
        assertEquals(96f, LinkaTargets.communicationCard.value)
        assertEquals(56f, LinkaTargets.actionButton.value)
        assertTrue(LinkaTargets.communicationCard.value >= 48f)
        assertTrue(LinkaTargets.actionButton.value >= 48f)
        assertEquals(3f, LinkaTargets.focusRing.value)
    }
}
