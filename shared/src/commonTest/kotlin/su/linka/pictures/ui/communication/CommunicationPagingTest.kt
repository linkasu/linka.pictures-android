package su.linka.pictures.ui.communication

import kotlin.test.*

class CommunicationPagingTest {
    @Test
    fun pageTargetsRemainAvailableWithoutButtonsButRespectBoundariesAndDisabledState() {
        assertEquals(1, pageTarget(0, 3, 1, true))
        assertEquals(0, pageTarget(1, 3, -1, true))
        assertNull(pageTarget(0, 3, -1, true))
        assertNull(pageTarget(2, 3, 1, true))
        assertNull(pageTarget(-1, 3, 1, true))
        assertNull(pageTarget(0, 0, 1, true))
        assertNull(pageTarget(0, 3, 1, false))
        assertNull(pageTarget(0, 3, 2, true))
    }

    @Test
    fun physicalReleaseActivatesExactlyOnceDespiteNativeKeyRepeat() {
        val gate = PageKeyActivation()
        val calls = mutableListOf<Int>()
        repeat(30) { assertTrue(gate.event(1, true, false, false) { calls.add(it) }) }
        assertTrue(calls.isEmpty())
        assertTrue(gate.event(1, false, false, false) { calls.add(it) })
        gate.event(1, false, false, false) { calls.add(it) }
        assertEquals(listOf(1), calls)
        gate.event(-1, true, false, false) { calls.add(it) }
        gate.event(-1, false, false, false) { calls.add(it) }
        assertEquals(listOf(1, -1), calls)
    }

    @Test
    fun typingModifiedKeysAndLostFocusNeverNavigateOrLeaveAnArmedKey() {
        val gate = PageKeyActivation()
        fun event(direction: Int, down: Boolean, text: Boolean = false, modified: Boolean = false) =
            gate.event(direction, down, text, modified) { error("must not navigate") }
        assertFalse(event(1, true, text = true))
        assertFalse(event(1, false, text = true))
        assertFalse(event(-1, true, modified = true))
        assertFalse(event(0, true))
        event(1, true)
        assertFalse(event(1, false, text = true))
        event(1, false)
        event(-1, true)
        gate.reset()
        event(-1, false)
        assertFalse(gate.event(1, true, false, false, enabled = false) { error("disabled") })
        event(1, false)
        event(1, true)
        assertFalse(gate.event(1, false, false, false, enabled = false) { error("disabled") })
        event(1, false)
    }
}
