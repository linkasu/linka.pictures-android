@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class,
    kotlin.experimental.ExperimentalNativeApi::class)

package su.linka.pictures.bootstrap

import kotlinx.cinterop.ObjCObject
import kotlin.native.ref.WeakReference
import kotlin.test.*
import platform.Foundation.NSMutableArray
import platform.darwin.NSObject

class UIKitIdentityTest {
    @Test fun nativeAliasesMatchWhileForeignAndMissingObjectsDoNot() {
        val own = NSObject()
        val foreign = NSObject()
        val retained = NSMutableArray().apply { addObject(own) }
        val borrowed = retained.objectAtIndex(0u) as ObjCObject
        val weak = WeakReference(own).get()
        assertTrue(sameUIKitObject(own, borrowed))
        assertTrue(sameUIKitObject(own, weak))
        assertFalse(sameUIKitObject(own, foreign))
        assertFalse(sameUIKitObject(own, null))
        assertFalse(sameUIKitObject(null, own))
        assertFalse(sameUIKitObject(null, null))
    }
}
