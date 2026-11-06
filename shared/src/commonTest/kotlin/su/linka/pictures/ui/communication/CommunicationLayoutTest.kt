package su.linka.pictures.ui.communication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull
import su.linka.pictures.ui.theme.Linka14
import su.linka.pictures.ui.theme.linkaPalette
import androidx.compose.ui.graphics.Color

class CommunicationLayoutTest {
    @Test
    fun everyActionGlyphParsesWithoutPlatformResources() {
        for (icon in CommunicationIcon.entries) {
            assertEquals(24f, icon.vector.viewportWidth)
            assertEquals(24f, icon.vector.viewportHeight)
            assertTrue(icon.vector.root.size > 0)
        }
    }

    @Test
    fun phraseKeepsOneFifthAndShortLargeTextWindowsCanScrollToGrid() {
        assertEquals(CommunicationVerticalBudget(96.6f, 500f), communicationVerticalBudget(500f, 1f, false))
        assertEquals(CommunicationVerticalBudget(48f, 240f), communicationVerticalBudget(240f, 1f, false))
        val short = communicationVerticalBudget(80f, 3f, true)
        assertEquals(144f, short.phrase)
        assertEquals(449f, short.content)
        assertTrue(short.content > 80)
    }

    @Test
    fun baselinePhoneLandscapeFitsNineWithoutChangingBoardDimensions() {
        val cell = communicationCellSize(784f, 208f, 3, 3, 1f)
        assertEquals(208f, cell.height * 3)
        assertEquals(784f, cell.width * 3)
    }

    @Test
    fun tabletAndPortraitUseAllAvailableSpace() {
        for ((width, height) in listOf(784f to 330f, 344f to 440f, 984f to 420f)) {
            val cell = communicationCellSize(width, height, 3, 3, 1f)
            assertEquals(width, cell.width * 3, 0.001f)
            assertEquals(height, cell.height * 3, 0.001f)
        }
    }

    @Test
    fun largeTextDensePagesAndNarrowWindowsScrollRatherThanShrinkTargets() {
        val dense = communicationCellSize(320f, 180f, 64, 64, 1f)
        assertEquals(CommunicationCellSize(56f, 64f), dense)
        val large = communicationCellSize(320f, 180f, 3, 3, 3f)
        assertEquals(192f, large.height)
        assertTrue(large.height * 3 > 180)
        assertEquals(CommunicationCellSize(56f, 64f), communicationCellSize(0f, 0f, 1, 1, 1f))
        assertFailsWith<IllegalArgumentException> { communicationCellSize(320f, 180f, 0, 3, 1f) }
        assertFailsWith<IllegalArgumentException> { communicationCellSize(Float.NaN, 180f, 3, 3, 1f) }
    }

    @Test
    fun toolbarFollowsAppCompatWindowClassesRatherThanDeviceOrFixture() {
        assertEquals(64f, communicationToolbarHeight(1280f, 800f))
        assertEquals(64f, communicationToolbarHeight(600f, 900f))
        assertEquals(56f, communicationToolbarHeight(599f, 900f))
        assertEquals(48f, communicationToolbarHeight(900f, 599f))
        assertEquals(56f, communicationToolbarHeight(400f, 800f))
        assertFailsWith<IllegalArgumentException> { communicationToolbarHeight(Float.NaN, 800f) }
    }

    @Test
    fun fixedMarginsAndDividerAreRemovedBeforeOneToFourWeights() {
        val budget = communicationVerticalBudget(664f, 1f, false)
        assertEquals(129.4f, budget.phrase)
        val grid = budget.content - budget.phrase - 17f
        assertEquals(budget.phrase * 4, grid, 0.001f)
        val cell = communicationCellSize(1248f, grid, 3, 3, 1f)
        assertEquals(416f, cell.width)
        assertEquals(grid, cell.height * 3, 0.001f)
        assertTrue(cell.height - 7.5f >= 64f)
    }

    @Test
    fun visibleInsetsDoNotConsumeMinimumTouchTargetOrLargeTextBudget() {
        for (columns in listOf(1, 2, 3, 7, 64)) for (scale in listOf(1f, 2f, 3f)) {
            val cell = communicationCellSize(160f, 100f, columns, 5, scale)
            assertTrue(cell.width - 8f >= 48f)
            assertTrue(cell.height >= 64f * scale)
            assertTrue(cell.height - 7.5f >= 48f)
        }
    }

    @Test
    fun readOnlyPreviewsUseHouseGradientOnlyInDefaultLightTheme() {
        assertEquals(listOf(Linka14.cardTop, Linka14.cardBottom), communicationMessageGradient(linkaPalette(false, false)))
        assertNull(communicationMessageGradient(linkaPalette(true, false)))
        assertNull(communicationMessageGradient(linkaPalette(false, true)))
        assertNull(communicationMessageGradient(linkaPalette(true, true)))
    }

    @Test
    fun phraseActionGrayIsMeasuredLightChromeNotADarkOrHighContrastFallback() {
        assertEquals(Color(0xFFD6D7D7), communicationActionSurface(linkaPalette(false, false)))
        for (palette in listOf(linkaPalette(true, false), linkaPalette(false, true), linkaPalette(true, true))) {
            assertEquals(palette.disabledSurface, communicationActionSurface(palette))
        }
    }
}
