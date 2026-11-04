package su.linka.pictures

import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.shared.BoardLimits
import su.linka.pictures.shared.GridDimensions

class BoardLimitsTest {
    @Test fun dimensionsRejectInvalidValuesWithoutOverflow() {
        for (value in listOf(Int.MIN_VALUE, -1, 0, 33, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { GridDimensions(value, 4) }
            assertThrows(IllegalArgumentException::class.java) { GridDimensions(4, value) }
        }
        assertEquals(48, GridDimensions(12, 4).cellCount)
        assertEquals(1024, GridDimensions(32, 32).cellCount)
    }

    @Test fun cardAndPathLimits() {
        BoardLimits.validateCardCount(10_000)
        assertThrows(IllegalArgumentException::class.java) { BoardLimits.validateCardCount(10_001) }
        assertThrows(IllegalArgumentException::class.java) { BoardLimits.validateCardCount(-1) }
        for (type in 0..3) BoardLimits.validateCardType(type)
        assertThrows(IllegalArgumentException::class.java) { BoardLimits.validateCardType(4) }
        BoardLimits.validateMediaPath("media/nested/image.png")
        for (path in listOf("", "/image", "../image", "a/../image", "a//b", "a/./b", "C:/a", "a\\b", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { BoardLimits.validateMediaPath(path) }
        }
    }
}
