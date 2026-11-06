package su.linka.pictures.activity

import android.content.Intent
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
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
    private val libraryTitles = listOf("Ваши наборы", "Библиотека")
    private val libraryMenu = listOf("Меню библиотеки", "Меню", "Ещё")

    @Test fun filledBoardThroughRealSafImport() {
        assertFixtureIdentity()
        ActivityScenario.launch(DevelopmentActivity::class.java).use {
            assertEmptyLibraryAndMessage()
            prepareNamedImport()
            selectOwnedSafFixture()
            confirmImportAndCompose()
            evidenceReady("real-saf")
        }
    }

    /** API21 visual fixture path only: does NOT exercise SAF picker or an OS cross-UID grant. */
    @Test fun filledBoardThroughOwnedIncomingContent() {
        assertFixtureIdentity()
        val authority = "${target.packageName}.parity-fixtures"
        val provider = target.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)
            ?: error("Missing approved debug target FileProvider; integrate primary manifest/XML first")
        assertEquals(authority, provider.authority)
        assertEquals(target.packageName, provider.packageName)
        assertEquals(target.applicationInfo.uid, provider.applicationInfo.uid)
        assertEquals("androidx.core.content.FileProvider", provider.name)
        assertFalse("Fixture provider must not be exported", provider.exported)
        assertTrue("Fixture provider must support scoped URI grants", provider.grantUriPermissions)
        assertTrue("Fixture provider must be enabled", provider.enabled)
        val directory = File(target.cacheDir, "u14-fixtures")
        assertEquals("Fixture directory must stay directly under target cache", target.cacheDir.canonicalFile,
            directory.canonicalFile.parentFile)

        ActivityScenario.launch(DevelopmentActivity::class.java).use { scenario ->
            assertEmptyLibraryAndMessage()
            assertTrue("Cannot create owned fixture cache directory", directory.isDirectory || directory.mkdir())
            withOwnedCopy(directory) { copy ->
                try {
                    val uri = FileProvider.getUriForFile(target, authority, copy)
                    assertEquals("content", uri.scheme)
                    assertEquals(authority, uri.authority)
                    assertEquals("Only the approved cache subtree may be published",
                        listOf("u14-fixtures", copy.name), uri.pathSegments)
                    val source = target.contentResolver.openInputStream(uri)
                        ?: error("Owned fixture content URI is not readable")
                    assertEquals(expectedSha, source.use { sha(it.readBytes()) })
                    stage("owned-content-read-verified")
                    val intent = Intent(target, DevelopmentActivity::class.java)
                        .setAction(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream")
                    // Actual same-UID native delivery; no external URI grant or substituted reader.
                    stage("before-native-content-intent")
                    scenario.onActivity { instrumentation.callActivityOnNewIntent(it, intent) }
                    await("owned incoming notice without auto-import") { root -> find(root) { node ->
                        node.text?.toString()?.startsWith("Получен файл.") == true
                    } && emptyLibrary(root) }
                    prepareNamedImport()
                    // Every following wait permits only target package: unexpected DocumentsUI fails.
                    confirmImportAndCompose()
                    evidenceReady("owned-incoming-content; SAF-picker/OS-grant NOT evaluated")
                } catch (failure: Throwable) {
                    // Revoke/close the activity owner before deleting its own source on a failed import.
                    try { scenario.close() }
                    catch (cleanupFailure: Throwable) {
                        if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                    }
                    throw failure
                }
            }
        }
    }

    @Test fun reopenFilledBoardWithoutImportOrMessageEdits() {
        assertFixtureIdentity()
        assertTrue("No existing owned library to reopen", File(target.filesDir, "pokazhi-development/library.sqlite3").exists())
        ActivityScenario.launch(DevelopmentActivity::class.java).use {
            awaitLibrary()
            clickNamed(listOf("Открыть «ХОЧУ»", "ХОЧУ"), scroll = true)
            awaitNineCards()
            awaitFilledMessage()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitLibrary()
            clickNamed(listOf("Открыть «ХОЧУ»", "ХОЧУ"), scroll = true)
            awaitNineCards()
            awaitFilledMessage()
            evidenceReady("reopen-native-back-regression")
        }
    }

    @Test fun inspectOwnedFixtureGridSemantics() {
        assertFixtureIdentity()
        assertTrue("No existing owned library to inspect", File(target.filesDir, "pokazhi-development/library.sqlite3").exists())
        ActivityScenario.launch(DevelopmentActivity::class.java).use {
            awaitLibrary()
            clickNamed(listOf("Открыть «ХОЧУ»", "ХОЧУ"), scroll = true)
            await("owned public fixture labels for read-only AX inspection") { root ->
                titles.all { title -> named(root, listOf(title)) }
            }
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: error("Missing owned fixture window")
            try {
                assertEquals(target.packageName, root.packageName?.toString())
                var collections = 0
                find(root) { node ->
                    node.collectionInfo?.takeIf { collections < 4 }?.let { info ->
                        collections++
                        instrumentation.sendStatus(0, Bundle().apply {
                            putString("u14Collection", "rows=${info.rowCount};columns=${info.columnCount}")
                        })
                    }
                    false
                }
                if (collections == 0) instrumentation.sendStatus(0, Bundle().apply { putString("u14Collection", "ABSENT") })
                for (title in titles) {
                    var matchCount = 0
                    find(root) { node ->
                        if (matchCount < 3 && matches(node, listOf(title))) {
                            matchCount++
                            val chain = mutableListOf<String>()
                            var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                            try {
                                while (current != null && chain.size < 16) {
                                    val item = current.collectionItemInfo
                                    val bounds = Rect().also(current::getBoundsInScreen)
                                    val action = current.actionList.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }?.label
                                    chain.add("class=${current.className};clickable=${current.isClickable};enabled=${current.isEnabled};visible=${current.isVisibleToUser};row=${item?.rowIndex};col=${item?.columnIndex};action=${action?.take(80)};bounds=${bounds.toShortString()}")
                                    if (current.isClickable) break
                                    val parent = current.parent
                                    current.recycle()
                                    current = parent
                                }
                            } finally { current?.recycle() }
                            instrumentation.sendStatus(0, Bundle().apply {
                                putString("u14FixtureLabel", title)
                                putString("u14NamedActionChain", chain.joinToString(" | "))
                            })
                        }
                        false
                    }
                }
            } finally { root.recycle() }
        }
    }

    private fun assertFixtureIdentity() {
        assertSandbox()
        val args = InstrumentationRegistry.getArguments()
        assertEquals("Primary must identify the exact owned fixture", fixtureName, args.getString("u14FixtureName"))
        assertEquals("Primary must verify the source SHA", expectedSha, args.getString("u14FixtureSha256"))
        assertEquals(expectedSha, instrumentation.context.assets.open(fixture).use { sha(it.readBytes()) })
    }

    private fun assertEmptyLibraryAndMessage() {
        awaitLibrary()
        await("fixturealreadyimported / library not empty: use reopen-only verification; never reset or duplicate", predicate = ::emptyLibrary)
        clickNamed(libraryMenu)
        clickNamed(listOf("К сообщению"))
        await("message not empty: use reopen-only verification; never clear existing parts") { root ->
            named(root, listOf("Общее сообщение: пусто"))
        }
        clickNamed(listOf("Открыть библиотеку"))
        awaitLibrary()
    }

    private fun prepareNamedImport() {
        stage("prepare-import-enter")
        val importLabels = listOf("Импорт", "Импорт, резервная копия и восстановление")
        if (!visibleNamed(importLabels)) clickNamed(libraryMenu)
        clickNamed(importLabels)
        awaitNamed("Файлы и восстановление")
        setField("Название импортируемой доски", "Импортированная доска", "ХОЧУ")
        stage("prepare-import-before-click")
        clickNamed(listOf("Выбрать .linka для импорта (без записи в библиотеку)"), scroll = true)
        stage("prepare-import-click-delivered")
    }

    private fun confirmImportAndCompose() {
        await("real imported media preview", scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { root ->
            find(root) { node -> node.text?.toString()?.contains(
                "Страниц: 1; карточек: 9; изображений: 9; записей: 9;") == true }
        }
        stage("import-preview-observed")
        // New owner capability comes only from the actual foreground UI, never a direct access API.
        clickNamed(listOf("Разрешить команды в этом экране"), scroll = true, backwards = true)
        clickNamed(listOf("Подтверждаю импорт «ХОЧУ» как новой доски"), scroll = true)
        awaitNamed("Операция завершена.", scroll = true)
        clickNamed(listOf("Назад"))
        awaitLibrary()
        clickNamed(listOf("Открыть «ХОЧУ»", "ХОЧУ"), scroll = true)
        awaitNineCards()
        await("empty composed message") { root -> named(root, listOf("Общее сообщение: пусто")) }
        clickNamed(listOf("ХОЧУ"), cardOnly = true)
        await("first composed part without playback") { root ->
            named(root, listOf("Произнести")) && !named(root, listOf("Остановить")) &&
                named(root, listOf("Общее сообщение: ХОЧУ"))
        }
        clickNamed(listOf("КУШАТЬ"), cardOnly = true)
        awaitFilledMessage()
    }

    private fun selectOwnedSafFixture() {
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        await("real DocumentsUI", packages = pickerPackages + target.packageName) { root ->
            root.packageName?.toString() in pickerPackages
        }
        if (!visibleNamed(listOf(fixtureName), pickerPackages, requiredViewId = "android:id/title")) {
            clickNamed(listOf("Show roots", "Показать корневые папки", "Открыть панель навигации"), packages = pickerPackages)
            clickNamed(listOf("Downloads", "Загрузки"), packages = pickerPackages)
        }
        if (!visibleNamed(listOf(fixtureName), pickerPackages, requiredViewId = "android:id/title")) {
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
        clickNamed(listOf(fixtureName), packages = pickerPackages, requiredViewId = "android:id/title")
        await("SAF returned to target", packages = pickerPackages + target.packageName) { root ->
            root.packageName?.toString() == target.packageName && named(root, listOf("Файлы и восстановление"))
        }
    }

    private fun awaitNineCards() = await("nine visible named cards with explicit 3x3 AX metadata; if absent run inspectOwnedFixtureGridSemantics") { root ->
        titles.all { title -> namedHitBounds(root, listOf(title), cardOnly = true) != null } &&
            find(root) { node -> node.collectionInfo?.let { it.rowCount == 3 && it.columnCount == 3 } == true }
    }

    private fun awaitFilledMessage() = await("ХОЧУ КУШАТЬ composed; no displayed autoplay") { root ->
        named(root, listOf("Общее сообщение: ХОЧУ КУШАТЬ")) && named(root, listOf("Произнести")) && !named(root, listOf("Остановить"))
    }

    private fun awaitLibrary() = await("Ваши наборы / library") { named(it, libraryTitles) }

    private fun emptyLibrary(root: AccessibilityNodeInfo) = find(root) { node ->
        node.text?.toString()?.startsWith("В библиотеке пока нет досок.") == true
    }

    private fun evidenceReady(inputPath: String) = instrumentation.sendStatus(0, Bundle().apply {
        putString("u14State", "board-filled")
        putString("u14InputPath", inputPath)
        putString("u14FixtureSha256", expectedSha)
        putString("u14MessageParts", "ХОЧУ|КУШАТЬ")
        putString("u14Verdict", "UI_STATE_READY_ONLY; pixels/visual parity/user approval NOT evaluated")
    })

    private fun stage(id: String) = instrumentation.sendStatus(0, Bundle().apply { putString("u14Stage", id) })

    @Test fun baselineFixtureHasUnmodifiedNineCardMedia() = withOwnedCopy(target.cacheDir) { copy ->
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

    private fun withOwnedCopy(directory: File, check: (File) -> Unit) {
        assertSandbox()
        fun source() = instrumentation.context.assets.open(fixture).use { it.readBytes() }
        val original = source()
        assertEquals(expectedSha, sha(original))
        val copy = File.createTempFile("u14-owned-", ".linka", directory)
        var failure: Throwable? = null
        try {
            assertEquals(directory.canonicalFile, copy.canonicalFile.parentFile)
            copy.writeBytes(original)
            check(copy)
        } catch (primaryFailure: Throwable) {
            failure = primaryFailure
        } finally {
            for (cleanup in listOf<() -> Unit>(
                { assertEquals("Packaged fixture changed", expectedSha, sha(source())) },
                { assertEquals("Transport changed fixture bytes", expectedSha, sha(copy.readBytes())) },
                { assertTrue("Could not remove this test's own cache copy", copy.delete()) },
            )) {
                try { cleanup() }
                catch (cleanupFailure: Throwable) {
                    val primary = failure
                    if (primary == null) failure = cleanupFailure
                    else if (cleanupFailure !== primary) primary.addSuppressed(cleanupFailure)
                }
            }
        }
        failure?.let { throw it }
    }

    @Test fun ownedCopyKeepsPrimaryFailureWhenShaCleanupFails() {
        val primary = AssertionError("Synthetic primary failure")
        var ownCopy: File? = null
        try {
            withOwnedCopy(target.cacheDir) { copy ->
                ownCopy = copy
                copy.writeBytes(byteArrayOf(0))
                throw primary
            }
            fail("Expected the original synthetic failure")
        } catch (actual: Throwable) {
            assertSame(primary, actual)
            assertEquals(1, actual.suppressed.size)
            assertTrue(actual.suppressed.single() is AssertionError)
        }
        assertNotNull(ownCopy)
        assertFalse("Own cache copy must be deleted even when SHA cleanup fails", ownCopy!!.exists())
    }

    private fun assertSandbox() {
        assertEquals("su.linka.pictures.development", target.packageName)
        assertEquals("Instrumentation must run in the target sandbox", target.applicationInfo.uid, Process.myUid())
        assertTrue("Parity fixture instrumentation requires a debuggable target",
            target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
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
        instrumentation.sendStatus(0, Bundle().apply { putString("u14Await", state) })
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

    private fun visibleNamed(labels: List<String>, packages: Set<String> = setOf(target.packageName), requiredViewId: String? = null): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { root.packageName?.toString() in packages && namedHitBounds(root, labels, requiredViewId = requiredViewId) != null }
        finally { root.recycle() }
    }

    private fun awaitNamed(label: String, scroll: Boolean = false) = await(label,
        scrollAction = if (scroll) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else null) { named(it, listOf(label)) }

    @Test fun namedHitRectangleUsesVisibleLabelNotWideRowCenter() {
        val label = Rect(160, 554, 1017, 597)
        val row = Rect(0, 512, 2560, 640)
        val window = Rect(0, 0, 2560, 1600)
        val hit = requireNotNull(visibleNamedBounds(label, row, window))
        assertEquals(label, hit)
        assertTrue(label.contains(hit.centerX(), hit.centerY()))
        assertFalse(label.contains(row.centerX(), row.centerY()))
        assertEquals(Rect(160, 554, 900, 597), visibleNamedBounds(label, row, Rect(0, 0, 900, 1600)))
        assertNull(visibleNamedBounds(label, Rect(0, 0, 100, 100), window))
        assertNull(visibleNamedBounds(Rect(), row, window))
    }

    private fun visibleNamedBounds(named: Rect, clickable: Rect, window: Rect): Rect? =
        Rect(named).takeIf { it.intersect(clickable) && it.intersect(window) }

    @Test fun namedClickCandidateRejectsSearchQueryButAcceptsResultWithSameName() {
        assertFalse(isNamedClickCandidate(named = true, enabled = true, editable = true))
        assertTrue(isNamedClickCandidate(named = true, enabled = true, editable = false))
        assertFalse(isNamedClickCandidate(named = true, enabled = false, editable = false))
        assertFalse(isNamedClickCandidate(named = false, enabled = true, editable = false))
        assertFalse(isNamedClickCandidate(true, true, false, "com.google.android.documentsui:id/search_src_text", "android:id/title"))
        assertFalse(isNamedClickCandidate(true, true, false, null, "android:id/title"))
        assertTrue(isNamedClickCandidate(true, true, false, "android:id/title", "android:id/title"))
    }

    private fun isNamedClickCandidate(named: Boolean, enabled: Boolean, editable: Boolean,
        viewId: String? = null, requiredViewId: String? = null) =
        named && enabled && !editable && (requiredViewId == null || viewId == requiredViewId)

    @Test fun fixtureCellRequiresExplicitMatchingRowAndColumn() {
        assertTrue(matchesFixtureCell(4, 1, 1))
        assertFalse(matchesFixtureCell(4, null, null))
        assertFalse(matchesFixtureCell(4, 0, 4))
        assertFalse(matchesFixtureCell(-1, 0, 0))
    }

    private fun matchesFixtureCell(position: Int, row: Int?, column: Int?) =
        position >= 0 && row == position / 3 && column == position % 3

    private fun namedHitBounds(root: AccessibilityNodeInfo, labels: List<String>, cardOnly: Boolean = false,
        requiredViewId: String? = null): Rect? {
        var bounds: Rect? = null
        find(root) { node ->
            if (!isNamedClickCandidate(matches(node, labels), node.isEnabled, node.isEditable, node.viewIdResourceName, requiredViewId)) false else {
                var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                var row: Int? = null
                var column: Int? = null
                try {
                    while (candidate != null) {
                        candidate.collectionItemInfo?.takeIf { row == null }?.let {
                            row = it.rowIndex
                            column = it.columnIndex
                        }
                        if (candidate.isClickable) break
                        val parent = candidate.parent
                        candidate.recycle()
                        candidate = parent
                    }
                    val position = titles.indexOfFirst { matches(node, listOf(it)) }
                    candidate?.takeIf { it.isEnabled && it.isVisibleToUser && (!cardOnly || matchesFixtureCell(position, row, column)) }?.let {
                        // A wide SAF row's center can hit metadata; stay on the actual named label/icon.
                        bounds = visibleNamedBounds(Rect().also(node::getBoundsInScreen),
                            Rect().also(it::getBoundsInScreen), Rect().also(root::getBoundsInScreen))
                    }
                    bounds != null
                } finally { candidate?.recycle() }
            }
        }
        return bounds
    }

    private fun clickNamed(labels: List<String>, packages: Set<String> = setOf(target.packageName),
        scroll: Boolean = false, backwards: Boolean = false, cardOnly: Boolean = false, requiredViewId: String? = null) {
        var bounds: Rect? = null
        val direction = if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        await("click ${labels.joinToString(" / ")}", packages, if (scroll) direction else null) { root ->
            bounds = namedHitBounds(root, labels, cardOnly, requiredViewId)
            bounds != null
        }
        val rect = bounds ?: error("Missing visible named hit rectangle inside clickable ancestor")
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
