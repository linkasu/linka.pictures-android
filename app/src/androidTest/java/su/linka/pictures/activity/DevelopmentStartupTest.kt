package su.linka.pictures.activity

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only launcher smoke; migration fixtures and data preservation are checked separately. */
@RunWith(AndroidJUnit4::class)
class DevelopmentStartupTest {
    @Test fun sharedLauncherOpensTheLibraryScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("su.linka.pictures.development", context.packageName)
        ActivityScenario.launch(MainActivity::class.java).use {
            val deadline = SystemClock.uptimeMillis() + 20_000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null) {
                    val texts = mutableListOf<String>()
                    try { collect(root, texts) } finally { root.recycle() }
                    assertFalse("Startup refused its own fresh storage: $texts",
                        texts.any { text -> text.contains("Не удалось безопасно открыть приложение") })
                    if (texts.any { text -> text == "Ваши наборы" || text == "Your boards" }) return
                }
            }
            fail("Shared launcher did not reach the library screen")
        }
    }

    private fun collect(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        node.text?.toString()?.let(texts::add)
        for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it, texts); it.recycle() }
    }
}
