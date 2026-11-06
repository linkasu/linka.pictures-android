package su.linka.pictures.ui.components

import kotlin.test.*

class ToolbarHeightTest {
    @Test fun ordinaryTextKeepsCalibratedHeightsAndLargeTextGetsScaledRoom() {
        for (base in listOf(48f, 56f, 64f)) {
            assertEquals(base, toolbarHeightForFontScale(base, 0.5f))
            assertEquals(base, toolbarHeightForFontScale(base, 1f))
            assertEquals(base * 2, toolbarHeightForFontScale(base, 2f))
            assertEquals(base * 3, toolbarHeightForFontScale(base, 3f))
        }
    }

    @Test fun invalidDimensionsAndScalesDoNotBecomeLayoutBounds() {
        for (invalid in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { toolbarHeightForFontScale(invalid, 1f) }
            assertFailsWith<IllegalArgumentException> { toolbarHeightForFontScale(56f, invalid) }
        }
    }
}
