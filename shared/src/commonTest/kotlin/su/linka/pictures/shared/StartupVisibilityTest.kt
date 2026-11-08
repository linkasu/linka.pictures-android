package su.linka.pictures.shared

import kotlin.test.*
import su.linka.pictures.bootstrap.StartupVisibility

class StartupVisibilityTest {
    @Test fun replacingOneStartedWindowWithAnotherDoesNotCancelProcessStartup() {
        val visibility = StartupVisibility()
        val first = Any()
        val second = Any()
        visibility.start(first)
        visibility.start(second)
        assertFalse(visibility.stop(first, false))
        assertFalse(visibility.stop(first, false))
        assertTrue(visibility.stop(second, false))
    }

    @Test fun configurationRecreationIsNotApplicationBackground() {
        val visibility = StartupVisibility()
        val first = Any()
        visibility.start(first)
        assertFalse(visibility.stop(first, true))
        val replacement = Any()
        visibility.start(replacement)
        assertTrue(visibility.stop(replacement, false))
    }
}
