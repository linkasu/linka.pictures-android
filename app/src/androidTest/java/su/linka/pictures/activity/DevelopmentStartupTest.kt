package su.linka.pictures.activity

import android.os.SystemClock
import android.os.Bundle
import android.system.Os
import android.system.OsConstants
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
        InstrumentationRegistry.getArguments().getString("expected_page_size")?.let { expected ->
            assertEquals("Unexpected runtime page size", expected.toLong(), Os.sysconf(OsConstants._SC_PAGESIZE))
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            val startedAt = SystemClock.uptimeMillis()
            val wait = StartupWait(startedAt)
            var lastTexts = emptyList<String>()
            while (true) {
                instrumentation.waitForIdleSync()
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null) {
                    val texts = mutableListOf<String>()
                    try {
                        if (root.packageName?.toString() == context.packageName) collect(root, texts)
                        if (texts != lastTexts) {
                            android.util.Log.i("StartupEvidence", "package=${root.packageName};texts=$texts")
                            lastTexts = texts
                        }
                    } finally { root.recycle() }
                    assertFalse("Startup refused its own fresh storage: $texts", texts.any(UiText.StartupBlocked::matches))
                    assertFalse("Startup requires explicit recovery: $texts", texts.any(UiText.StartupResume::matches))
                    if (texts.any(UiText.YourBoards::matches)) {
                        instrumentation.sendStatus(0, Bundle().apply {
                            putLong("coldStartupMillis", SystemClock.uptimeMillis() - startedAt)
                        })
                        return
                    }
                }
                if (wait.expired(SystemClock.uptimeMillis(), lastTexts)) break
            }
            val directory = java.io.File(context.getExternalFilesDir(null), "message-row-layout").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    java.io.File(directory, "startup-timeout.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
            fail("Cold startup stalled for 45s or exceeded 180s; last texts=$lastTexts")
        }
    }

    private fun collect(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        if (node.isPassword) return
        node.text?.toString()?.let(texts::add)
        for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it, texts); it.recycle() }
    }
}
