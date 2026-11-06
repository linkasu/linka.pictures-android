package su.linka.pictures.activity

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
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

/** Owned synthetic fixture only. UI readiness is not screenshot parity or user approval. */
@RunWith(AndroidJUnit4::class)
class DevelopmentParityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val fixture = "u14/ХОЧУ.linka"
    private val expectedSha = "a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b"
    private val titles = listOf("КУШАТЬ", "ПИТЬ", "СПАТЬ", "КУПАТЬСЯ", "ХОЧУ",
        "ПИСАТЬ КАКАТЬ", "ГУЛЯТЬ", "ИГРАТЬ", "ОБНИМАТЬСЯ")
    private val fixtureName = "LINKa-U14-HOCHU-cfb199d.linka"
    private val pickerPackages = setOf("com.android.documentsui", "com.google.android.documentsui")

    @Test fun filledBoardThroughRealSafImport() {
        assertSandbox()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("Primary must stage and verify this exact owned Downloads fixture", fixtureName, args.getString("u14FixtureName"))
        assertEquals("Primary must attest the staged file's measured SHA", expectedSha, args.getString("u14FixtureSha256"))
        assertFalse("fixturealreadyimported / nonfresh sandbox: use reopen-only verification; never reset or duplicate",
            File(target.filesDir, "pokazhi-development/library.sqlite3").exists())
        assertEquals(expectedSha, instrumentation.context.assets.open(fixture).use { sha(it.readBytes()) })

        ActivityScenario.launch(DevelopmentActivity::class.java).use {
            awaitNamed("Библиотека")
            await("fresh empty library") { root -> find(root) { node ->
                node.text?.toString()?.startsWith("В библиотеке пока нет досок.") == true
            } }
            val importLabels = listOf("Импорт", "Импорт, резервная копия и восстановление")
            if (!visibleNamed(importLabels)) clickNamed(listOf("Меню", "Ещё"))
            clickNamed(importLabels)
            awaitNamed("Файлы и восстановление")
            setField("Название импортируемой доски", "Импортированная доска", "ХОЧУ")
            clickNamed(listOf("Выбрать .linka для импорта (без записи в библиотеку)"), scroll = true)
            selectOwnedSafFixture()

            await("real imported media preview", scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { root ->
                find(root) { node -> node.text?.toString()?.contains(
                    "Страниц: 1; карточек: 9; изображений: 9; записей: 9;") == true }
            }
            // New owner capability comes only from the actual foreground UI, never a direct access API.
            clickNamed(listOf("Разрешить команды в этом экране"), scroll = true, backwards = true)
            clickNamed(listOf("Подтверждаю импорт «ХОЧУ» как новой доски"), scroll = true)
            awaitNamed("Операция завершена.", scroll = true)
            clickNamed(listOf("Назад"))
            awaitNamed("Библиотека")
            clickNamed(listOf("Открыть «ХОЧУ»", "ХОЧУ"), scroll = true)
            awaitNineCards()
            var initialTitleNodes = 0
            await("empty composed message") { root ->
                val empty = named(root, listOf("Сообщение пусто.", "Сообщение пусто"))
                if (empty) initialTitleNodes = countNamed(root, "ХОЧУ")
                empty
            }
            clickNamed(listOf("ХОЧУ"), cardOnly = true)
            await("first composed part without playback") { root ->
                named(root, listOf("Произнести")) && !named(root, listOf("Остановить")) &&
                    countNamed(root, "ХОЧУ") > initialTitleNodes
            }
            clickNamed(listOf("КУШАТЬ"), cardOnly = true)
            awaitFilledMessage()
            evidenceReady()
        }
    }

    @Test fun reopenFilledBoardWithoutImportOrMessageEdits() {
        assertSandbox()
        assertEquals("Primary must identify the existing owned fixture sandbox", fixtureName,
            InstrumentationRegistry.getArguments().getString("u14FixtureName"))
        assertEquals(expectedSha, InstrumentationRegistry.getArguments().getString("u14FixtureSha256"))
        assertTrue("No existing owned library to reopen", File(target.filesDir, "pokazhi-development/library.sqlite3").exists())
        ActivityScenario.launch(DevelopmentActivity::class.java).use {
            awaitNamed("Библиотека")
            clickNamed(listOf("Открыть «ХОЧУ»", "ХОЧУ"), scroll = true)
            awaitNineCards()
            awaitFilledMessage()
            evidenceReady()
        }
    }

    private fun selectOwnedSafFixture() {
        await("real DocumentsUI", packages = pickerPackages + target.packageName) { root ->
            root.packageName?.toString() in pickerPackages
        }
        if (!visibleNamed(listOf(fixtureName), pickerPackages)) {
            clickNamed(listOf("Show roots", "Показать корневые папки", "Открыть панель навигации"), packages = pickerPackages)
            clickNamed(listOf("Downloads", "Загрузки"), packages = pickerPackages)
        }
        if (!visibleNamed(listOf(fixtureName), pickerPackages)) {
            clickNamed(listOf("Search", "Найти", "Поиск"), packages = pickerPackages)
            await("DocumentsUI search field", packages = pickerPackages) { root -> find(root) { node ->
                if (!node.isEditable) false else {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    replaceText(node, fixtureName)
                }
            } }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        }
        // Availability is checked in the real provider UI before selecting; there is no filesystem/URI guess.
        clickNamed(listOf(fixtureName), packages = pickerPackages)
        await("SAF returned to target", packages = pickerPackages + target.packageName) { root ->
            root.packageName?.toString() == target.packageName && named(root, listOf("Файлы и восстановление"))
        }
    }

    private fun awaitNineCards() = await("nine visible named cards in a 3x3 grid") { root ->
        titles.withIndex().all { (index, title) -> find(root) { node ->
            matches(node, listOf(title)) && node.isClickable && node.isEnabled &&
                node.collectionItemInfo?.let { it.rowIndex == index / 3 && it.columnIndex == index % 3 } == true
        } } &&
            find(root) { node -> node.collectionInfo?.let { it.rowCount == 3 && it.columnCount == 3 } == true }
    }

    private fun awaitFilledMessage() = await("ХОЧУ КУШАТЬ composed; no displayed autoplay") { root ->
        named(root, listOf("ХОЧУ КУШАТЬ")) && named(root, listOf("Произнести")) && !named(root, listOf("Остановить"))
    }

    private fun evidenceReady() = instrumentation.sendStatus(0, Bundle().apply {
        putString("u14State", "board-filled")
        putString("u14FixtureSha256", expectedSha)
        putString("u14MessageParts", "ХОЧУ|КУШАТЬ")
        putString("u14Verdict", "UI_STATE_READY_ONLY; pixels/visual parity/user approval NOT evaluated")
    })

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
        // Historical negative ingress preflight only: pick(LINKA) used */*, not zip/octet-stream.
        // file:// is admitted by ingress, NOT by the reader. The positive test uses real SAF instead.
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
        assertSandbox()
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
            try { assertEquals("Packaged fixture changed", expectedSha, sha(source())) }
            finally { assertTrue("Could not remove this test's own cache copy", copy.delete()) }
        }
    }

    private fun assertSandbox() {
        assertEquals("su.linka.pictures.development", target.packageName)
        assertEquals("Instrumentation must run in the target sandbox", target.applicationInfo.uid, Process.myUid())
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

    private fun await(state: String, packages: Set<String> = setOf(target.packageName),
        scrollAction: Int? = null, predicate: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        var lastPackage: String? = null
        var scrolls = 0
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: continue
            try {
                lastPackage = root.packageName?.toString()
                assertTrue("Unexpected package at $state: $lastPackage; no auth/settings/permission automation", lastPackage in packages)
                if (predicate(root)) return
                if (scrollAction != null && scrolls < 12 && find(root) { node ->
                    node.isScrollable && node.performAction(scrollAction)
                }) scrolls++
            } finally { root.recycle() }
        }
        fail("Timed out at $state; activePackage=$lastPackage (no text/password dump)")
    }

    private fun matches(node: AccessibilityNodeInfo, labels: List<String>) =
        node.text?.toString() in labels || node.contentDescription?.toString() in labels

    private fun named(root: AccessibilityNodeInfo, labels: List<String>) = find(root) { matches(it, labels) }

    private fun countNamed(root: AccessibilityNodeInfo, label: String): Int {
        var count = if (root.isVisibleToUser && !root.isPassword && matches(root, listOf(label))) 1 else 0
        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            try { count += countNamed(child, label) } finally { child.recycle() }
        }
        return count
    }

    private fun visibleNamed(labels: List<String>, packages: Set<String> = setOf(target.packageName)): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { root.packageName?.toString() in packages && clickableBounds(root, labels) != null }
        finally { root.recycle() }
    }

    private fun awaitNamed(label: String, scroll: Boolean = false) = await(label,
        scrollAction = if (scroll) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else null) { named(it, listOf(label)) }

    private fun clickableBounds(root: AccessibilityNodeInfo, labels: List<String>, cardOnly: Boolean = false): Rect? {
        var bounds: Rect? = null
        find(root) { node ->
            if (!matches(node, labels)) false else {
                var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                try {
                    while (candidate != null && !candidate.isClickable) {
                        val parent = candidate.parent
                        candidate.recycle()
                        candidate = parent
                    }
                    candidate?.takeIf { it.isEnabled && it.isVisibleToUser && (!cardOnly || it.collectionItemInfo != null) }?.let {
                        bounds = Rect().also(it::getBoundsInScreen).takeUnless(Rect::isEmpty)
                    }
                    bounds != null
                } finally { candidate?.recycle() }
            }
        }
        return bounds
    }

    private fun clickNamed(labels: List<String>, packages: Set<String> = setOf(target.packageName),
        scroll: Boolean = false, backwards: Boolean = false, cardOnly: Boolean = false) {
        var bounds: Rect? = null
        val direction = if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        await("click ${labels.joinToString(" / ")}", packages, if (scroll) direction else null) { root ->
            bounds = clickableBounds(root, labels, cardOnly)
            bounds != null
        }
        val rect = bounds ?: error("Missing clickable ancestor")
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                rect.exactCenterX(), rect.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("Named UI touch injection failed", instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
    }

    private fun replaceText(node: AccessibilityNodeInfo, value: String) = node.performAction(
        AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })

    private fun setField(label: String, oldValue: String, value: String) {
        await("set named field $label") { root -> find(root) { node ->
            node.isEditable && (matches(node, listOf(label, oldValue)) || node.text?.contains(label) == true) && replaceText(node, value)
        } }
        await("verify field $label") { root -> find(root) { node -> node.isEditable && node.text?.contains(value) == true } }
    }

}
