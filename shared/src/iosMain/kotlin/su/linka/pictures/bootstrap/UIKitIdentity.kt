@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package su.linka.pictures.bootstrap

import kotlinx.cinterop.ObjCObject
import kotlinx.cinterop.objcPtr

internal fun sameUIKitObject(left: ObjCObject?, right: ObjCObject?): Boolean =
    left != null && right != null && left.objcPtr() == right.objcPtr()
