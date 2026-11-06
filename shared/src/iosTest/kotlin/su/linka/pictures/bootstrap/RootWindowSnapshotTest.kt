package su.linka.pictures.bootstrap

import kotlin.test.*

class RootWindowSnapshotTest {
    private class Window(var hidden: Boolean = false, var active: Boolean = true, var background: Boolean = false,
        val nativeIdentity: Any = Any())
    private fun snapshot(open: Boolean, current: Window?, cached: Window?, active: Boolean = true, background: Boolean = false) =
        rootWindowSnapshot(open, current, cached, active, background, { it.hidden }, { it.active }, { it.background },
            { left, right -> left.nativeIdentity === right.nativeIdentity })

    @Test fun twoWrappersForTheSameNativeWindowAreNotAWindowReplacement() {
        val native = Any()
        val cached = Window(nativeIdentity = native)
        val alias = Window(nativeIdentity = native)
        assertNotSame(cached, alias)
        assertEquals(RootWindowSnapshot(true, true), snapshot(true, alias, cached))
        assertEquals(RootWindowSnapshot(false, false), snapshot(true, Window(), cached))
        alias.hidden = true
        assertEquals(RootWindowSnapshot(false, false), snapshot(true, alias, cached))
    }

    @Test fun ownFullscreenModalCanDetachPresentingViewWithoutBackgroundOrForeignWindowFallback() {
        val own = Window()
        val foreign = Window()
        assertEquals(RootWindowSnapshot(true, true), snapshot(true, own, own))
        assertEquals(RootWindowSnapshot(true, true), snapshot(true, null, own))
        assertEquals(RootWindowSnapshot(false, false), snapshot(true, foreign, own))
        assertEquals(RootWindowSnapshot(false, true), snapshot(true, null, own, active = false))
        own.active = false
        assertEquals(RootWindowSnapshot(false, true), snapshot(true, null, own))
        own.background = true
        assertEquals(RootWindowSnapshot(false, false), snapshot(true, null, own))
    }

    @Test fun closedDisconnectedHiddenAndActualApplicationBackgroundNeverUseTheCachedWindowAsForeground() {
        val own = Window()
        assertEquals(RootWindowSnapshot(false, false), snapshot(false, own, own))
        assertEquals(RootWindowSnapshot(false, false), snapshot(true, null, null))
        assertEquals(RootWindowSnapshot(false, false), snapshot(true, null, own, active = false, background = true))
        own.hidden = true
        assertEquals(RootWindowSnapshot(false, false), snapshot(true, null, own))
    }
}
