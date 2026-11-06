package su.linka.pictures.activity

import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fixture/transport preflight only: this does NOT assert filled-board parity or perform an import. */
@RunWith(AndroidJUnit4::class)
class DevelopmentParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val fixture = "u14/ХОЧУ.linka"
    private val expectedSha = "a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b"
    private val titles = listOf("КУШАТЬ", "ПИТЬ", "СПАТЬ", "КУПАТЬСЯ", "ХОЧУ",
        "ПИСАТЬ КАКАТЬ", "ГУЛЯТЬ", "ИГРАТЬ", "ОБНИМАТЬСЯ")

    @Test fun baselineFixtureHasUnmodifiedNineCardMedia() = withOwnedCopy { copy ->
        ZipFile(copy).use { zip ->
            val config = JSONObject(zip.getInputStream(zip.getEntry("config.json")).bufferedReader().use { it.readText() })
            assertEquals("1.2", config.getString("version"))
            assertEquals(3, config.getInt("rows"))
            assertEquals(3, config.getInt("columns"))
            assertFalse(config.getBoolean("directSet"))
            val cards = config.getJSONArray("cards")
            assertEquals(9, cards.length())
            for (index in titles.indices) {
                val card = cards.getJSONObject(index)
                assertEquals(titles[index], card.getString("title"))
                assertEquals(0, card.getInt("cardType"))
                for (key in listOf("imagePath", "audioPath")) {
                    val entry = zip.getEntry(card.getString(key))
                    assertNotNull("Missing $key for fixture card $index", entry)
                    assertTrue("Empty $key for fixture card $index", entry.size > 0)
                    zip.getInputStream(entry).use { assertTrue(it.read() >= 0) }
                }
            }
        }
    }

    @Test fun incomingFixtureRequiresExplicitUiImport() = withOwnedCopy { copy ->
        assertFalse("Use a fresh OWNED development sandbox; do not reset a user's library",
            File(target.filesDir, "pokazhi-development/library.sqlite3").exists())
        // file:// is admitted by the development ingress, NOT by the document reader. Stop before pick.
        val intent = Intent(target, DevelopmentActivity::class.java)
            .setAction(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(copy), "application/zip")
        ActivityScenario.launch(DevelopmentActivity::class.java).use { scenario ->
            await("library startup (accepted 1.4 navigation binding)") { root -> find(root) { node ->
                node.text?.toString() == "Библиотека" || node.contentDescription?.toString() == "Библиотека"
            } }
            // Same-UID synthetic native delivery, not a file URI shared across a process boundary.
            scenario.onActivity { instrumentation.callActivityOnNewIntent(it, intent) }
            await("incoming notice") { root -> find(root) { node ->
                node.text?.toString()?.startsWith("Получен файл.") == true
            } }
            await("library; incoming document must not auto-import") { root -> find(root) { node ->
                node.text?.toString()?.startsWith("В библиотеке пока нет досок.") == true
            } }
        }
    }

    private fun withOwnedCopy(check: (File) -> Unit) {
        assertEquals("su.linka.pictures.development", target.packageName)
        assertEquals("Instrumentation must run in the target sandbox", target.applicationInfo.uid, Process.myUid())
        fun source() = instrumentation.context.assets.open(fixture).use { it.readBytes() }
        val original = source()
        assertEquals(expectedSha, sha(original))
        val copy = File.createTempFile("u14-owned-", ".linka", target.cacheDir)
        try {
            assertEquals(target.cacheDir.canonicalFile, copy.canonicalFile.parentFile)
            copy.writeBytes(original)
            check(copy)
            assertEquals("Transport changed fixture bytes", expectedSha, sha(copy.readBytes()))
        } finally {
            assertEquals("Packaged fixture changed", expectedSha, sha(source()))
            assertTrue("Could not remove this test's own cache copy", copy.delete())
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (node.isVisibleToUser && !node.isPassword && predicate(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (find(child, predicate)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun await(state: String, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        var lastPackage: String? = null
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: continue
            try {
                lastPackage = root.packageName?.toString()
                assertEquals("Unexpected system UI at $state; no provider/auth automation is allowed", target.packageName, lastPackage)
                if (predicate(root)) return
            } finally { root.recycle() }
        }
        fail("Timed out at $state; activePackage=$lastPackage (no text/password dump)")
    }

}
