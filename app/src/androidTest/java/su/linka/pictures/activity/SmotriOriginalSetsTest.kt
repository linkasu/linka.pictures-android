package su.linka.pictures.activity

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmotriOriginalSetsTest {
    @Test fun inspectRepairedSoundsMediaWithoutPublishing() = kotlinx.coroutines.runBlocking {
        val original = java.io.File(target.cacheDir, "smotri-sounds-validated-v2.linka")
        assertEquals(fixtures.single { it.slug == "sounds2" }.sha,
            MessageDigest.getInstance("SHA-256").digest(original.readBytes()).joinToString("") { "%02x".format(it) })
        val inspection = Class.forName("su.linka.pictures.platform.capabilities.AndroidMediaInspectionPort")
            .getDeclaredConstructor().newInstance() as su.linka.pictures.application.ports.MediaInspectionPort
        java.util.zip.ZipFile(original).use { zip ->
            val config = org.json.JSONObject(zip.getInputStream(zip.getEntry("config.json")).bufferedReader().readText())
            val cards = config.getJSONArray("cards")
            val references = linkedMapOf<String, Boolean>()
            for (i in 0 until cards.length()) for (key in listOf("imagePath", "audioPath")) {
                val path = cards.getJSONObject(i).optString(key)
                if (path.isNotEmpty()) references[path] = key == "imagePath"
            }
            for ((name, image) in references) {
                val temporary = java.io.File.createTempFile("smotri-inspection-", ".media", target.cacheDir)
                try {
                    zip.getInputStream(zip.getEntry(name)).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    val file = su.linka.pictures.application.ports.LocalFile::class.java
                        .getDeclaredConstructor(String::class.java, String::class.java, String::class.java)
                        .newInstance(temporary.path, null, null)
                    val result = if (image) inspection.image(file) else inspection.audio(file)
                    instrumentation.sendStatus(0, Bundle().apply { putString("smotriMediaInspection", "$name|$result") })
                    assertTrue("Repaired fixture media $name: $result", result is su.linka.pictures.application.OperationResult.Completed)
                } finally { check(temporary.delete()) }
            }
        }
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val picker = setOf("com.android.documentsui", "com.google.android.documentsui")
    private var capturePrefix = ""
    private data class Fixture(val file: String, val name: String, val slug: String, val pages: Int,
        val rows: Int, val columns: Int, val mode: String, val sha: String)
    private val fixtures = listOf(
        Fixture("Фраза.linka", "SMOTRI Фраза", "phrase", 2, 3, 3, "PHRASE", "ea95ece0ed1b9555f6c4be61f89c8775523a5a9490ce183045d81ed1cc9616b5"),
        Fixture("Азбука.linka", "SMOTRI Азбука", "alphabet", 5, 2, 2, "DIRECT", "0c4e1bd65e216f17e3fd63120cc9aa858edc948df4245c45e4b96fa609624c65"),
        // Authorized test copy: two MP3s repaired, trailing CRLF removed from two JPEGs; image codestream/config unchanged.
        Fixture("sounds2-validated-v2.linka", "SMOTRI звуки2 (копия)", "sounds2", 10, 2, 3, "DIRECT", "a67647676804d4e016d06af694fb04b300abcb5511ea3243a3ee65c02681afcd"),
        Fixture("3x3.linka", "SMOTRI 3x3", "keyboard3x3", 5, 3, 3, "LITERAL", "04a9b92ea6a33d727e5d7044bbde0ff690ff96539d8efe67be35ac4bf17070b0"),
        Fixture("Клавиатура.linka", "SMOTRI Клавиатура", "keyboard", 2, 4, 12, "LITERAL", "c7b1d07cf4c3e926dbf7256d5c3f10acb1de329df70a4753f834128b1320e3b8"),
    )

    @Test fun importOrReuseOriginalsAndCaptureEveryPageWithoutSelection() {
        assertEquals("su.linka.pictures.development", target.packageName)
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ActivityScenario.launch(DevelopmentActivity::class.java).use {
            awaitNamed("Ваши наборы")
            click("Меню библиотеки")
            click("Настройки")
            awaitNamed("1.0", scroll = true)
            click("Назад")
            awaitNamed("Ваши наборы")
            for (fixture in fixtures) {
                if (!findLibraryBoard(fixture.name)) import(fixture)
                assertTrue("Imported original must be listed", findLibraryBoard(fixture.name))
            }
            captureBoards()
        }
    }

    @Test fun captureExistingOriginalsAtCurrentScale() {
        assertEquals("su.linka.pictures.development", target.packageName)
        capturePrefix = "current-scale-"
        ActivityScenario.launch(DevelopmentActivity::class.java).use {
            awaitNamed("Ваши наборы")
            captureBoards()
        }
    }

    @Test fun captureExistingPhraseWithoutWrites() {
        ActivityScenario.launch(DevelopmentActivity::class.java).use {
            awaitNamed("Ваши наборы")
            captureBoards(fixtures.take(1))
        }
    }

    @Test fun restoreBaselineCaptureSettingsAndComposeMode() {
        ActivityScenario.launch(DevelopmentActivity::class.java).use {
            awaitNamed("Ваши наборы")
            click("Меню библиотеки")
            click("Настройки")
            awaitNamed("1.0", scroll = true)
            click("Назад")
            check(findLibraryBoard(fixtures.first().name))
            click("Действия с набором «${fixtures.first().name}»")
            click("Открыть")
            awaitNamed("Меню общения")
            setSessionMode(false)
            click("Меню общения")
            awaitNamed("Перейти в прямой режим")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("message semantics restored") { publicState("Общее сообщение:") != null }
            capture("restored-compose", stable = false)
            click("Открыть библиотеку")
            awaitNamed("Ваши наборы")
        }
    }

    private fun captureBoards(selected: List<Fixture> = fixtures) {
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        for (fixture in selected) {
            check(findLibraryBoard(fixture.name)) { "Missing ${fixture.name}; capture never imports" }
            click("Действия с набором «${fixture.name}»")
            click("Открыть")
            awaitNamed("Меню общения")
            awaitNamed(fixture.name)
            awaitNamed("Страница 1 из ${fixture.pages}")
            val previousDirect = setSessionMode(fixture.mode == "DIRECT")
            await("message semantics after mode change") { publicState("Общее сообщение:") != null }
            val messageBefore = publicState("Общее сообщение:")
            assertNotNull("Message semantics required for no-selection check", messageBefore)
            val audioBefore = publicState("Произнести")
            try { for (page in 1..fixture.pages) {
                awaitNamed("Страница $page из ${fixture.pages}")
                assertFalse("No playback or card selection", exists("Остановить"))
                assertEquals("Pagination must not edit message", messageBefore, publicState("Общее сообщение:"))
                assertEquals("Pagination must not start audio", audioBefore, publicState("Произнести"))
                captureOverflow(fixture, page)
                assertEquals(messageBefore, publicState("Общее сообщение:"))
                assertEquals(audioBefore, publicState("Произнести"))
                if (page < fixture.pages) click("Следующая страница")
            } } finally { if (exists("Меню общения")) setSessionMode(previousDirect) }
            click("Открыть библиотеку")
            awaitNamed("Ваши наборы")
        }
    }

    private fun setSessionMode(direct: Boolean): Boolean {
        val current = publicState("Меню общения")
        val expected = if (direct) "Прямой режим" else "Составление сообщения"
        if (current?.endsWith("|$expected") == true) return direct
        click("Меню общения")
        await("session mode menu") { root -> find(root) {
            matches(it, listOf("Перейти в прямой режим", "Составлять сообщение"))
        } }
        val previous = exists("Составлять сообщение")
        if (previous != direct) click(if (direct) "Перейти в прямой режим" else "Составлять сообщение")
        else instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        await("mode menu dismissed") { root -> find(root) {
            it.text?.startsWith("Страница ") == true || it.contentDescription?.startsWith("Страница ") == true
        } }
        return previous
    }

    @Test fun providerRejectsUnknownPathsAndWrites() {
        val provider = SmotriDocumentsProvider()
        for (name in listOf("../Фраза.linka", "/Фраза.linka", "other.linka")) {
            assertTrue(runCatching { provider.queryDocument(name, null) }.exceptionOrNull() is FileNotFoundException)
        }
        for (mode in listOf("w", "rw", "rwt")) {
            assertTrue(runCatching { provider.openDocument("Фраза.linka", mode, null) }.exceptionOrNull() is FileNotFoundException)
        }
    }

    private fun captureOverflow(fixture: Fixture, page: Int) {
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val viewport = Rect()
        var cellWidth = 0
        var cellHeight = 0
        try {
            check(find(root) { grid ->
                if (AccessibilityNodeInfoCompat.wrap(grid).stateDescription?.startsWith("Сетка ") != true) false
                else {
                    grid.getBoundsInScreen(viewport)
                    find(grid) { node ->
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (node != grid && node.collectionInfo == null && !node.contentDescription.isNullOrBlank() && Rect.intersects(bounds, viewport)) {
                            cellWidth = maxOf(cellWidth, bounds.width())
                            cellHeight = maxOf(cellHeight, bounds.height())
                        }
                        false
                    }
                    true
                }
            }) { "Actual grid bounds missing" }
        } finally { root.recycle() }
        check(cellWidth > 0 && cellHeight > 0)
        val horizontalSteps = maxOf(0, kotlin.math.ceil((cellWidth.toDouble() * fixture.columns - viewport.width() - 1) / (viewport.width() * 0.4)).toInt())
        val verticalSteps = maxOf(0, kotlin.math.ceil((cellHeight.toDouble() * fixture.rows - viewport.height() - 1) / (viewport.height() * 0.4)).toInt())
        check(horizontalSteps <= 64 && verticalSteps <= 64 && (horizontalSteps + 1) * (verticalSteps + 1) <= 256)
        fun scroll(horizontal: Boolean, forward: Boolean) {
            val low = if (horizontal) viewport.left else viewport.top
            val length = if (horizontal) viewport.width() else viewport.height()
            val start = low + length * (if (forward) 0.8f else 0.2f)
            val end = low + length * (if (forward) 0.2f else 0.8f)
            val x1 = if (horizontal) start.toInt() else viewport.centerX()
            val y1 = if (horizontal) viewport.centerY() else start.toInt()
            val x2 = if (horizontal) end.toInt() else viewport.centerX()
            val y2 = if (horizontal) viewport.centerY() else end.toInt()
            instrumentation.uiAutomation.executeShellCommand("input swipe $x1 $y1 $x2 $y2 250").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
            instrumentation.uiAutomation.waitForIdle(100, 2000)
        }
        fun centeredLabels(): Set<String> {
            val current = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
            val labels = mutableSetOf<String>()
            try {
                find(current) { node ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    node.contentDescription?.toString()?.takeIf { viewport.contains(bounds.centerX(), bounds.centerY()) }
                        ?.let(labels::add)
                    false
                }
            } finally { current.recycle() }
            return labels
        }
        val origin = centeredLabels()
        val seen = linkedSetOf<String>()
        var captures = 0
        fun captureViewport(row: Int, column: Int) {
            check(++captures <= 256) { "Grid exceeds 256 viewports; evidence incomplete" }
            val content = gridSnapshot()
            seen.addAll(content.lines())
            inspectGrid(fixture, page)
            instrumentation.sendStatus(0, Bundle().apply {
                putString("smotriVisibleIdentifiers", content)
                putString("smotriViewport", "row=$row;column=$column")
            })
            capture(capturePrefix + "${fixture.slug}-page-$page" + if (captures == 1) "" else "-v$row-h$column")
        }
        for (row in 0..verticalSteps) {
            for (column in 0..horizontalSteps) {
                captureViewport(row, column)
                if (column < horizontalSteps) scroll(true, true)
            }
            repeat(horizontalSteps) { scroll(true, false) }
            if (row < verticalSteps) scroll(false, true)
        }
        repeat(verticalSteps) { scroll(false, false) }
        await("grid origin restored") { centeredLabels() == origin }
        instrumentation.sendStatus(0, Bundle().apply { putString("smotriReachableIdentifiers", seen.joinToString("\n")) })
    }

    private fun gridSnapshot(): String {
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val visible = mutableListOf<String>()
        try {
            assertEquals(target.packageName, root.packageName?.toString())
            check(find(root) { grid ->
                if (AccessibilityNodeInfoCompat.wrap(grid).stateDescription?.startsWith("Сетка ") != true) false
                else {
                    val viewport = Rect().also(grid::getBoundsInScreen)
                    find(grid) { node ->
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (bounds.intersect(viewport)) {
                            val item = node.collectionItemInfo
                            val label = node.contentDescription ?: node.text
                            if (item != null || !label.isNullOrBlank()) visible.add(
                                "row=${item?.rowIndex};col=${item?.columnIndex};id=${node.viewIdResourceName};label=$label;bounds=${bounds.toShortString()}")
                        }
                        false
                    }
                    true
                }
            }) { "Actual board grid semantics missing" }
        } finally { root.recycle() }
        return visible.sorted().joinToString("\n")
    }

    private fun findLibraryBoard(name: String): Boolean {
        for (i in 0..100) {
            if (!scrollLibrary(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) break
            check(i < 100) { "Cannot establish library start; do not import" }
        }
        for (i in 0..100) {
            if (exists("Действия с набором «$name»")) return true
            if (!scrollLibrary(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return false
        }
        error("Cannot establish library end; do not import")
    }

    private fun scrollLibrary(action: Int): Boolean {
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        val moved = try {
            assertEquals(target.packageName, root.packageName?.toString())
            find(root) { it.isScrollable && it.performAction(action) }
        } finally { root.recycle() }
        if (moved) instrumentation.uiAutomation.waitForIdle(100, 2000)
        return moved
    }

    private fun import(fixture: Fixture) {
        click("Меню библиотеки")
        click("Импорт, резервная копия и восстановление")
        awaitNamed("Файлы и восстановление")
        await("transfer top anchor", scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { root ->
            find(root) { it.text?.toString() == "Пароль помощника не установлен." }
        }
        await("import name", scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { root -> find(root) { node ->
            (node.isEditable || node.className?.toString() == "android.widget.EditText") && node.isEnabled && setText(node, fixture.name)
        } }
        await("accepted import name") { root -> find(root) { it.text?.toString() == fixture.name } }
        if (instrumentation.uiAutomation.windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }) instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        click("Выбрать .linka для импорта (без записи в библиотеку)", scroll = true)
        await("DocumentsUI", picker + target.packageName) { it.packageName?.toString() in picker }
        if (!exists(fixture.file, picker)) {
            clickAny(listOf("Show roots", "Показать корневые папки", "Открыть панель навигации"), picker)
            click("SMOTRI originals", picker)
        }
        click(fixture.file, picker)
        await("original preview", picker + target.packageName, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { root ->
            root.packageName?.toString() == target.packageName && find(root) {
                it.text?.toString()?.startsWith("Страниц: ${fixture.pages};") == true
            }
        }
        val uri = android.provider.DocumentsContract.buildDocumentUri(
            "${instrumentation.context.packageName}.smotri.documents", fixture.file)
        val digest = MessageDigest.getInstance("SHA-256")
        checkNotNull(target.contentResolver.openInputStream(uri)).use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        assertEquals("Selected original SHA before confirmation", fixture.sha,
            digest.digest().joinToString("") { "%02x".format(it) })
        if (fixture.slug == "keyboard") {
            await("explicit inactive-media warning", scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { root ->
                find(root) { it.text?.startsWith("Неактивных вложений EMPTY/SPACE: 11.") == true }
            }
            capture("keyboard-inactive-preview", stable = false)
        }
        click("Разрешить команды в этом экране", scroll = true, backwards = true)
        // Never replay this confirmation after an ambiguous timeout; next run first searches the unique name.
        click("Подтверждаю импорт «${fixture.name}» как новой доски", scroll = true)
        await("completed transfer", scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { root ->
            find(root) { matches(it, listOf("Операция завершена.")) }
        }
        click("Назад")
        awaitNamed("Ваши наборы")
    }

    private fun inspectGrid(fixture: Fixture, page: Int) {
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        try {
            val metadata = mutableListOf<String>()
            var items = 0
            find(root) { node ->
                if (node.collectionItemInfo != null) items++
                node.collectionInfo?.let {
                    metadata.add("rows=${it.rowCount};columns=${it.columnCount}")
                    if (AccessibilityNodeInfoCompat.wrap(node).stateDescription?.startsWith("Сетка ") == true) {
                        assertEquals(fixture.rows, it.rowCount)
                        assertEquals(fixture.columns, it.columnCount)
                    }
                }
                if (matches(node, listOf("Меню общения"))) {
                    AccessibilityNodeInfoCompat.wrap(node).stateDescription?.let {
                        assertEquals(if (fixture.mode == "DIRECT") "Прямой режим" else "Составление сообщения", it.toString())
                    }
                }
                false
            }
            val attachment = Bundle().apply {
                putString("smotriPage", "${fixture.slug}:$page")
                putString("smotriGrid", metadata.joinToString("|").ifEmpty { "AX collection metadata absent" })
                putString("smotriExpectedMode", fixture.mode)
                putInt("smotriVisibleCollectionItems", items)
            }
            instrumentation.sendStatus(0, attachment)
        } finally { root.recycle() }
    }

    private fun publicState(prefix: String): String? {
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        var state: String? = null
        try {
            find(root) { node ->
                val label = node.contentDescription?.toString() ?: node.text?.toString()
                if (label?.startsWith(prefix) == true) {
                    state = "$label|${AccessibilityNodeInfoCompat.wrap(node).stateDescription}"
                    true
                } else false
            }
        } finally { root.recycle() }
        return state
    }

    private fun capture(name: String, stable: Boolean = true) {
        require(name.matches(Regex("[a-z0-9-]+")))
        if (stable) {
            awaitVisibleMedia()
            awaitStablePixels()
        }
        val path = "/data/local/tmp/smotri-$name.png"
        instrumentation.uiAutomation.executeShellCommand("screencap -p $path").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("smotriScreenshot", path) })
    }

    private var lastPendingImage: String? = null
    private fun awaitVisibleMedia() = await("visible card bitmaps decoded (30s)") { root ->
        find(root) { grid ->
            if (AccessibilityNodeInfoCompat.wrap(grid).stateDescription?.startsWith("Сетка ") != true) false
            else {
                val viewport = Rect().also(grid::getBoundsInScreen)
                !find(grid) { node ->
                    val bounds = Rect().also(node::getBoundsInScreen)
                    // API21 also returns stale anonymous layout nodes; wait on the actual accessible card buttons.
                    val pending = node.isClickable && !node.contentDescription.isNullOrBlank() && bounds.intersect(viewport) &&
                        AccessibilityNodeInfoCompat.wrap(node).stateDescription?.contains("Изображение ещё не показано") == true
                    if (pending) {
                        val detail = "${node.contentDescription}|${node.text}|${bounds.toShortString()}|viewport=${viewport.toShortString()}"
                        if (detail != lastPendingImage) {
                            lastPendingImage = detail
                            instrumentation.sendStatus(0, Bundle().apply { putString("smotriPendingImage", detail) })
                        }
                    }
                    pending
                }
            }
        }
    }

    private fun awaitStablePixels() {
        val deadline = SystemClock.uptimeMillis() + 20_000
        var previous: android.graphics.Bitmap? = null
        var since = SystemClock.uptimeMillis()
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                val next = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val same = previous?.sameAs(next) == true
                previous?.recycle()
                previous = next
                if (!same) since = SystemClock.uptimeMillis()
                if (same && SystemClock.uptimeMillis() - since >= 250) return
            }
            error("Native pixels did not settle")
        } finally { previous?.recycle() }
    }

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return true
        val bounds = Rect().also(node::getBoundsInScreen)
        instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (node.isVisibleToUser && !node.isPassword && predicate(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { if (find(child, predicate)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun matches(node: AccessibilityNodeInfo, names: List<String>) =
        node.text?.toString() in names || node.contentDescription?.toString() in names

    private fun exists(name: String, packages: Set<String> = setOf(target.packageName)): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { root.packageName?.toString() in packages && find(root) { matches(it, listOf(name)) } }
        finally { root.recycle() }
    }

    private fun awaitNamed(name: String, scroll: Boolean = false) = await(name,
        scrollAction = if (scroll) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else null) { root ->
        find(root) { matches(it, listOf(name)) }
    }

    private fun await(state: String, packages: Set<String> = setOf(target.packageName), scrollAction: Int? = null,
        predicate: (AccessibilityNodeInfo) -> Boolean) {
        instrumentation.sendStatus(0, Bundle().apply { putString("smotriAwait", state) })
        val deadline = SystemClock.uptimeMillis() + 30_000
        var scrolls = 0
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: continue
            try {
                assertTrue("Unexpected package at $state", root.packageName?.toString() in packages)
                if (predicate(root)) return
                if (scrollAction != null && scrolls < 12 && root.packageName?.toString() == target.packageName) {
                    swipeWindow(root, scrollAction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    scrolls++
                }
            } finally { root.recycle() }
        }
        capture("timeout", stable = false)
        error("Timed out: $state; inspect state before retrying import")
    }

    private fun swipeWindow(root: AccessibilityNodeInfo, forward: Boolean) {
        val bounds = Rect().also(root::getBoundsInScreen)
        val keyboardTop = instrumentation.uiAutomation.windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }?.let { Rect().also(it::getBoundsInScreen).top }
        val top = bounds.top + (bounds.height() * 0.12f).toInt()
        val bottom = minOf(bounds.bottom, keyboardTop ?: bounds.bottom)
        val height = bottom - top
        check(height > 60)
        // Stay inside the form content; the outer right gutter does not dispatch drags to its scroll container.
        val x = bounds.left + (bounds.width() * 0.8f).toInt()
        val start = top + (height * (if (forward) 0.8f else 0.2f)).toInt()
        val end = top + (height * (if (forward) 0.2f else 0.8f)).toInt()
        instrumentation.uiAutomation.executeShellCommand("input swipe $x $start $x $end 250").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        instrumentation.uiAutomation.waitForIdle(100, 2000)
    }

    private fun click(name: String, packages: Set<String> = setOf(target.packageName), scroll: Boolean = false,
        backwards: Boolean = false) = clickAny(listOf(name), packages, scroll, backwards)

    private fun clickAny(names: List<String>, packages: Set<String> = setOf(target.packageName),
        scroll: Boolean = false, backwards: Boolean = false) {
        val direction = if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        await("click ${names.first()}", packages, if (scroll) direction else null) { root -> find(root) { node ->
            if (!matches(node, names) || node.isEditable) false else {
                var action: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
                try {
                    while (action != null && !action.isClickable) {
                        val parent = action.parent
                        action.recycle()
                        action = parent
                    }
                    action?.let { it.isEnabled && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
                } finally { action?.recycle() }
            }
        } }
    }
}
