package su.linka.pictures.activity

import android.app.UiAutomation
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.FileInputStream

internal fun scrollTestWindow(automation: UiAutomation, root: AccessibilityNodeInfo, forward: Boolean): Boolean {
    fun viewport(node: AccessibilityNodeInfo): String = buildString {
        fun collect(current: AccessibilityNodeInfo) {
            if (!current.isVisibleToUser || current.isPassword) return
            if (!current.text.isNullOrEmpty() || !current.contentDescription.isNullOrEmpty()) {
                append(current.text).append(current.contentDescription).append(Rect().also(current::getBoundsInScreen))
            }
            for (index in 0 until current.childCount) {
                val child = current.getChild(index) ?: continue
                try { collect(child) } finally { child.recycle() }
            }
        }
        collect(node)
    }
    val before = viewport(root)
    val bounds = Rect().also(root::getBoundsInScreen)
    val keyboardTop = automation.windows.firstOrNull {
        it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }?.let { Rect().also(it::getBoundsInScreen).top }
    val top = bounds.top + (bounds.height() * 0.12f).toInt()
    val bottom = minOf(bounds.bottom, keyboardTop ?: bounds.bottom)
    val height = bottom - top
    check(height > 60)
    // Stay inside form content and overlap viewports so a whole setting row cannot be skipped.
    val x = bounds.left + (bounds.width() * 0.8f).toInt()
    val start = top + (height * (if (forward) 0.65f else 0.35f)).toInt()
    val end = top + (height * (if (forward) 0.35f else 0.65f)).toInt()
    automation.executeShellCommand("input swipe $x $start $x $end 250").use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
    }
    automation.waitForIdle(100, 2000)
    if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache()
    val after = automation.rootInActiveWindow ?: return false
    return try { after.packageName == root.packageName && viewport(after) != before }
    finally { after.recycle() }
}
