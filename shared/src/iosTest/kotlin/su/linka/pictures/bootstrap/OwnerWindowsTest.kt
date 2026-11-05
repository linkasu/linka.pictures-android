package su.linka.pictures.bootstrap

import kotlin.test.*

class OwnerWindowsTest {
    @Test fun closingAnOwnerOrItsWindowClosesNativeAdmissionWithoutBorrowingAnotherWindow() {
        val owners = OwnerWindows()
        owners.openWindow("a"); owners.openWindow("b")
        owners.open("alice", "a"); owners.open("bob", "b")
        owners.setActive("b", true)
        assertFalse(owners.active("alice"))
        assertTrue(owners.active("bob"))
        assertEquals("a", owners.window("alice"))
        owners.setActive("a", true)
        assertTrue(owners.active("alice"))
        owners.close("alice")
        assertFalse(owners.active("alice"))
        assertNull(owners.window("alice"))
        assertEquals(setOf("bob"), owners.closeWindow("b"))
        assertFalse(owners.active("bob"))
        assertFailsWith<IllegalStateException> { owners.open("late", "b") }
    }
}
