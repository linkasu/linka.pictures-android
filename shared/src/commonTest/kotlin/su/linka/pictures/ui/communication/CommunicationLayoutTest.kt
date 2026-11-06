package su.linka.pictures.ui.communication

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        assertEquals(CommunicationVerticalBudget(100f, 500f), communicationVerticalBudget(500f, 1f, false))
        assertEquals(CommunicationVerticalBudget(48f, 240f), communicationVerticalBudget(240f, 1f, false))
        val short = communicationVerticalBudget(80f, 3f, true)
        assertEquals(144f, short.phrase)
        assertEquals(449f, short.content)
        assertTrue(short.content > 80)
    }

    @Test
    fun baselinePhoneLandscapeFitsNineWithoutChangingBoardDimensions() {
        val cell = communicationCellSize(784f, 208f, 3, 3, 1f)
        assertEquals(208f, cell.height * 3 + 8)
        assertEquals(784f, cell.width * 3 + 8)
    }

    @Test
    fun tabletAndPortraitUseAllAvailableSpace() {
        for ((width, height) in listOf(784f to 330f, 344f to 440f, 984f to 420f)) {
            val cell = communicationCellSize(width, height, 3, 3, 1f)
            assertEquals(width, cell.width * 3 + 8, 0.001f)
            assertEquals(height, cell.height * 3 + 8, 0.001f)
        }
    }

    @Test
    fun largeTextDensePagesAndNarrowWindowsScrollRatherThanShrinkTargets() {
        val dense = communicationCellSize(320f, 180f, 64, 64, 1f)
        assertEquals(CommunicationCellSize(48f, 64f), dense)
        val large = communicationCellSize(320f, 180f, 3, 3, 3f)
        assertEquals(192f, large.height)
        assertTrue(large.height * 3 > 180)
        assertEquals(CommunicationCellSize(48f, 64f), communicationCellSize(0f, 0f, 1, 1, 1f))
        assertFailsWith<IllegalArgumentException> { communicationCellSize(320f, 180f, 0, 3, 1f) }
        assertFailsWith<IllegalArgumentException> { communicationCellSize(Float.NaN, 180f, 3, 3, 1f) }
    }
}
