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
    fun phraseKeepsSinglePreviewAndFitsFirstTwoBeforeScrolling() {
        assertEquals(120f, communicationMessagePartWidth(180f, 120f, 1))
        assertEquals(100f, communicationMessagePartWidth(100f, 120f, 1))
        val two = communicationMessagePartWidth(180f, 120f, 2)
        assertEquals(90f, two)
        assertEquals(180f, two * 2)
        for (count in listOf(3, 4, 100)) {
            val width = communicationMessagePartWidth(180f, 120f, count)
            assertEquals(two, width)
            assertTrue(width * count > 180f)
        }
        assertEquals(96f, communicationMessagePartWidth(400f, 60f, 2))
    }

    @Test
    fun tinyPhraseViewportsScrollInsteadOfShrinkingBelowFortyEightDp() {
        assertEquals(48f, communicationMessagePartWidth(80f, 120f, 2))
        assertEquals(48f, communicationMessagePartWidth(20f, 20f, 1))
        assertEquals(90f, communicationMessagePartWidth(180f, 20f, 2))
        assertEquals(120f, communicationMessagePartWidth(180f, 120f, 0))
        assertEquals(120f, communicationMessagePartWidth(180f, 120f, -1))
    }

    @Test
    fun invalidAndUnboundedPhraseBoundsKeepFinitePreferredWidth() {
        for (bound in listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, 0f, -1f)) {
            assertEquals(120f, communicationMessagePartWidth(bound, 120f, 2))
            assertEquals(48f, communicationMessagePartWidth(180f, bound, 2))
            assertEquals(48f, communicationMessagePartWidth(bound, bound, 2))
        }
    }

    @Test
    fun shortLandscapePreviewsKeepCaptionRoomWithoutClippingTheSecondPreview() {
        for (width in listOf(192f, 320f, 667f)) {
            val part = communicationMessagePartWidth(width, 48f, 2)
            assertEquals(96f, part)
            assertTrue(part * 2 <= width)
        }
        assertEquals(48f, communicationMessagePartWidth(80f, 48f, 2))
        assertEquals(120f, communicationMessagePartWidth(320f, 120f, 2))
    }

    @Test
    fun everyActionGlyphParsesWithoutPlatformResources() {
        for (icon in CommunicationIcon.entries) {
            assertEquals(24f, icon.vector.viewportWidth)
            assertEquals(24f, icon.vector.viewportHeight)
            assertTrue(icon.vector.root.size > 0)
        }
    }

    @Test
    fun phraseKeepsOneFifthWithoutMakingTheWholeScreenScrollable() {
        assertEquals(CommunicationVerticalBudget(96.6f, 500f), communicationVerticalBudget(500f, 1f, false))
        assertEquals(CommunicationVerticalBudget(64f, 240f), communicationVerticalBudget(240f, 1f, false))
        val short = communicationVerticalBudget(80f, 3f, true)
        assertTrue(short.phrase <= 40f)
        assertEquals(80f, short.content)
    }

    @Test
    fun everyViewportBudgetStaysInsideItsParentEvenAtMaximumTextScale() {
        for (height in listOf(0f, 32f, 80f, 240f, 664f)) for (scale in listOf(1f, 3f, 6f)) {
            val budget = communicationVerticalBudget(height, scale, true)
            assertEquals(height, budget.content)
            assertTrue(budget.phrase in 0f..height / 2f)
        }
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
        assertEquals(112f, communicationToolbarHeight(400f, 800f, 2f))
        assertEquals(96f, communicationToolbarHeight(900f, 599f, 2f))
        assertEquals(192f, communicationToolbarHeight(1280f, 800f, 3f))
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
    fun phraseActionsUseNavigationSurfaceInEveryThemeNotDisabledChrome() {
        assertEquals(Color(0xFFE2EEEA), communicationActionSurface(linkaPalette(false, false)))
        for (palette in listOf(linkaPalette(true, false), linkaPalette(false, true), linkaPalette(true, true))) {
            assertEquals(palette.navigationSurface, communicationActionSurface(palette))
        }
    }
}
