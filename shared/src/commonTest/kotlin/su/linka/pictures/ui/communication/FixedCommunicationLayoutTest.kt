package su.linka.pictures.ui.communication

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixedCommunicationLayoutTest {
    @Test
    fun keepsWholeCaptionsAndPicturesWithinFixedTouchTargets() {
        // A deterministic monospace font: 8px characters and 20px lines, wrapping at spaces.
        val caption: (String, Int) -> IntSize = { label, width ->
            var line = 0
            var widest = 0
            var lines = 1
            for (word in label.split(' ')) {
                val next = word.length * 8
                if (line > 0 && line + 8 + next > width) {
                    widest = maxOf(widest, line)
                    line = next
                    lines++
                } else line += (if (line == 0) 0 else 8) + next
            }
            IntSize(maxOf(widest, line), lines * 20)
        }
        fun pack(width: Int, height: Int, label: String = "one two", image: Boolean = true) =
            packCommunicationCell(width, height, label, image, 32, 6, caption)

        assertEquals(CommunicationCellPacking(), pack(100, 80))
        assertEquals(CommunicationCellPacking(38), pack(100, 40))
        assertNull(pack(60, 40))
        assertNull(pack(50, 100, "longword", image = false))
        assertNotNull(pack(32, 32, " \n "))
        assertNull(pack(31, 100, ""))
        assertNotNull(pack(40, 40, image = false))
        assertNull(pack(40, 39, image = false))

        for (rows in 1..6) for (columns in 1..8) {
            val size = fixedCommunicationCellSize(393.5f, 301.5f, rows, columns, 48)
            if (size != null) {
                assertTrue(size.width >= 48 && size.height >= 48)
                assertTrue(size.width * columns <= 393.5f && size.height * rows <= 301.5f)
            }
        }
        assertNull(fixedCommunicationCellSize(143f, 300f, 3, 3, 48))
        assertNull(fixedCommunicationCellSize(300f, 143f, 3, 3, 48))
    }
}
