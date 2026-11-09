package su.linka.pictures.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.*

class EditorCellDragTest {
    private val bounds = mapOf(0 to Rect(0f, 0f, 100f, 100f), 1 to Rect(110f, 0f, 210f, 100f))

    @Test fun longPressDropEmitsOneSwapAndSuppressesOnlyThatGestureTap() {
        val drag = EditorCellDrag()
        drag.resetTapSuppression()
        assertFalse(drag.suppressTap)
        drag.start(Offset(50f, 50f), bounds)
        assertEquals(0, drag.source)
        drag.move(Offset(150f, 50f), bounds)
        assertEquals(1, drag.target)
        assertEquals(0 to 1, drag.finish())
        assertTrue(drag.suppressTap)
        assertNull(drag.finish())
        drag.resetTapSuppression()
        assertFalse(drag.suppressTap)
    }

    @Test fun outsideSelfAndCancelNeverProduceMoves() {
        val drag = EditorCellDrag()
        drag.start(Offset(50f, 50f), bounds)
        assertNull(drag.finish())
        drag.start(Offset(50f, 50f), bounds)
        drag.move(Offset(105f, 50f), bounds)
        assertNull(drag.target)
        assertNull(drag.finish())
        drag.start(Offset(50f, 50f), bounds)
        drag.move(Offset(150f, 50f), bounds)
        drag.cancel()
        assertNull(drag.finish())
        assertTrue(drag.suppressTap)
        drag.resetTapSuppression()
        drag.start(Offset(-1f, -1f), bounds)
        drag.move(Offset(150f, 50f), bounds)
        assertNull(drag.finish())
        assertFalse(drag.suppressTap)
    }
}
