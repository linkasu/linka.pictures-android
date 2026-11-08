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
    private val automation = instrumentation.uiAutomation.apply {
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }
    private val target = instrumentation.targetContext
    private val picker = setOf("com.android.documentsui", "com.google.android.documentsui")
    private val capturePrefix = "fixed-"
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
        ActivityScenario.launch(MainActivity::class.java).use {
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

    @Test fun testFixedPagesAndAdultRejection() {
        assertEquals("su.linka.pictures.development", target.packageName)
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitNamed("Ваши наборы")
            captureBoards()
        }
    }

    @Test fun captureExistingPhraseWithoutWrites() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitNamed("Ваши наборы")
            captureBoards(fixtures.take(1))
        }
    }

    @Test fun captureExistingKeyboardWithoutWrites() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitNamed("Ваши наборы")
            captureBoards(fixtures.takeLast(1))
        }
    }

    @Test fun testBaselinePresentationSettingsReadOnly() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitNamed("Ваши наборы")
            openSettings()
            assertEquals("Portable baseline", "1.0" to false, readPresentationSettings())
            click("Назад")
            awaitNamed("Ваши наборы")
        }
    }

    @Test fun landscapePolicyPreservesMessageAcrossDeviceRotation() {
        val automatic = android.provider.Settings.System.getInt(target.contentResolver,
            android.provider.Settings.System.ACCELEROMETER_ROTATION, 1) != 0
        val original = (target.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                awaitNamed("Ваши наборы")
                scenario.onActivity { assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, it.requestedOrientation) }
                check(findLibraryBoard(fixtures.first().name))
                click("Действия с набором «${fixtures.first().name}»")
                click("Открыть")
                awaitNamed("Меню общения")
                await("message before rotation") { publicState("Общее сообщение:") != null }
                val before = publicState("Общее сообщение:")
                for (rotation in listOf(0, 1, 2, 3)) {
                    assertTrue(automation.setRotation(rotation))
                    await("landscape after rotation $rotation") {
                        target.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
                            publicState("Общее сообщение:") == before
                    }
                    assertFalse(exists("Остановить"))
                }
                capture("landscape-policy", stable = false)
                click("Открыть библиотеку")
                awaitNamed("Ваши наборы")
            }
        } finally {
            check(automation.setRotation(if (automatic) android.app.UiAutomation.ROTATION_UNFREEZE else original))
        }
    }

    @Test fun testUnsupportedScaleDoesNotChangeWorkingBoard() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitNamed("Ваши наборы")
            openSettings()
            assertEquals("Portable baseline", "1.0" to false, readPresentationSettings())
            click("Назад")
            openPhrase()
            val message = checkNotNull(publicState("Общее сообщение:"))
            val audio = checkNotNull(publicState("Произнести"))
            val counter = checkNotNull(publicState("Страница "))
            val mode = checkNotNull(publicState("Меню общения"))
            click("Открыть библиотеку")
            awaitNamed("Ваши наборы")
            openSettings()
            click("Масштаб текста", scroll = true)
            await("proposed unsupported scale") { root -> find(root) { node ->
                (node.isEditable || node.className?.toString() == "android.widget.EditText") &&
                    node.isEnabled && setText(node, "3.0")
            } }
            await("scale input updated") { root -> find(root) { node ->
                (node.isEditable || node.className?.toString() == "android.widget.EditText") &&
                    node.text?.toString() == "3.0"
            } }
            dismissKeyboard()
            click("Применить", scroll = true)
            awaitNamed("Масштаб не поддерживается")
            assertFalse("Scale rejection must remain adult-only", exists("Меню общения"))
            capture("fixed-scale-unsupported", stable = false)
            click("Закрыть")
            dismissKeyboard()
            click("Отмена", scroll = true)
            click("Назад")
            awaitNamed("Ваши наборы")
            openSettings()
            assertEquals("1.0" to false, readPresentationSettings())
            click("Назад")
            returnToMessage()
            awaitNamed(fixtures.first().name)
            await("rejected scale preserves working board") { root ->
                unchangedCommunication(root, message, audio) &&
                    publicState("Страница ", root) == counter && publicState("Меню общения", root) == mode
            }
        }
    }

    @Test fun testRejectedBoardPreservesWorkingBoardAndMessage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitNamed("Ваши наборы")
            openPhrase()
            click("Следующая страница")
            awaitNamed("Страница 2 из 2")
            val message = checkNotNull(publicState("Общее сообщение:"))
            val audio = checkNotNull(publicState("Произнести"))
            val counter = checkNotNull(publicState("Страница "))
            val mode = checkNotNull(publicState("Меню общения"))
            click("Открыть библиотеку")
            awaitNamed("Ваши наборы")
            val dense = fixtures.single { it.slug == "keyboard" }
            check(findLibraryBoard(dense.name))
            click("Действия с набором «${dense.name}»")
            click("Открыть")
            awaitNamed("Масштаб не поддерживается")
            assertFalse("Rejected board must not enter child mode", exists("Меню общения"))
            assertFalse("Rejection must not start playback", exists("Остановить"))
            capture("fixed-rejected-board", stable = false)
            click("Закрыть")
            awaitNamed("Ваши наборы")
            assertFalse(exists("Меню общения"))
            returnToMessage()
            awaitNamed(fixtures.first().name)
            await("rejected board preserves working board and message") { root ->
                unchangedCommunication(root, message, audio) &&
                    publicState("Страница ", root) == counter && publicState("Меню общения", root) == mode
            }
        }
    }

    private fun openPhrase() {
        val phrase = fixtures.first()
        if (!findLibraryBoard(phrase.name)) await("phrase library entry", scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { root ->
            find(root) { matches(it, listOf("Действия с набором «${phrase.name}»")) }
        }
        click("Действия с набором «${phrase.name}»")
        click("Открыть")
        awaitNamed("Меню общения")
        awaitNamed(phrase.name)
        awaitNamed("Страница 1 из ${phrase.pages}")
        await("phrase message and audio ready") { root ->
            publicState("Общее сообщение:", root) != null && publicState("Произнести", root) != null
        }
    }

    private fun returnToMessage() {
        click("Меню библиотеки")
        click("К сообщению")
        awaitNamed("Меню общения")
    }

    private fun openSettings() {
        click("Меню библиотеки")
        click("Настройки")
        awaitNamed("Настройки")
    }

    private fun readPresentationSettings(): Pair<String, Boolean> {
        await("settings form start", scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { root ->
            find(root) { matches(it, listOf("Родительский контроль")) }
        }
        var contrast: Boolean? = null
        await("saved high contrast", scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { root ->
            find(root) { node ->
                if (matches(node, listOf("Высокий контраст"))) {
                    contrast = checkableState(node)
                    contrast != null
                } else false
            }
        }
        click("Масштаб текста", scroll = true)
        var scale: String? = null
        await("portable text scale dialog") { root -> find(root) { node ->
            if (node.isEditable || node.className?.toString() == "android.widget.EditText") {
                scale = node.text?.toString()
                scale != null
            } else false
        } }
        dismissKeyboard()
        click("Отмена", scroll = true)
        return checkNotNull(scale) to checkNotNull(contrast)
    }

    private fun dismissKeyboard() {
        automation.waitForIdle(250, 5000)
        fun shown() = automation.windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        if (shown()) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("keyboard dismissed") { !shown() }
        }
    }

    private fun checkableState(node: AccessibilityNodeInfo): Boolean? {
        if (node.isCheckable) return node.isChecked
        val parent = node.parent ?: return null
        return try { checkableState(parent) } finally { parent.recycle() }
    }

    private fun captureBoards(selected: List<Fixture> = fixtures) {
        for (fixture in selected) {
            if (!findLibraryBoard(fixture.name)) await("fixture library entry", scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { root ->
                find(root) { matches(it, listOf("Действия с набором «${fixture.name}»")) }
            }
            click("Действия с набором «${fixture.name}»")
            click("Открыть")
            await("adult rejection or admitted child board") { root ->
                find(root) { matches(it, listOf("Масштаб не поддерживается", "Меню общения")) }
            }
            if (exists("Масштаб не поддерживается")) {
                assertFalse("Rejected board must remain adult-only", exists("Меню общения"))
                assertFalse("Rejection must not start playback", exists("Остановить"))
                capture(capturePrefix + "${fixture.slug}-unsupported", stable = false)
                instrumentation.sendStatus(0, Bundle().apply { putString("smotriAdmission", "${fixture.slug}:unsupported") })
                click("Закрыть")
                awaitNamed("Ваши наборы")
                assertFalse(exists("Меню общения"))
                continue
            }
            awaitNamed("Меню общения")
            awaitNamed(fixture.name)
            awaitNamed("Страница 1 из ${fixture.pages}")
            instrumentation.sendStatus(0, Bundle().apply { putString("smotriAdmission", "${fixture.slug}:supported") })
            val previousDirect = setSessionMode(fixture.mode == "DIRECT")
            var messageBefore: String? = null
            var audioBefore: String? = null
            await("message and speech semantics after mode change") { root ->
                messageBefore = publicState("Общее сообщение:", root)
                audioBefore = publicState("Произнести", root)
                messageBefore != null && audioBefore != null
            }
            assertNotNull("Message semantics required for no-selection check", messageBefore)
            assertNotNull("Speech semantics required for no-selection check", audioBefore)
            try { for (page in 1..fixture.pages) {
                awaitNamed("Страница $page из ${fixture.pages}")
                await("pagination preserves communication state") { root -> unchangedCommunication(root, messageBefore!!, audioBefore!!) }
                captureWholePage(fixture, page)
                await("whole-page capture preserves communication state") { root -> unchangedCommunication(root, messageBefore!!, audioBefore!!) }
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
        // API21 may omit the menu stateDescription; its actual opposite-mode action is authoritative.
        click("Меню общения")
        awaitNamed(if (direct) "Составлять сообщение" else "Перейти в прямой режим")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
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

    private fun captureWholePage(fixture: Fixture, page: Int) {
        awaitVisibleMedia()
        inspectGrid(fixture, page)
        instrumentation.sendStatus(0, Bundle().apply {
            putString("smotriVisibleIdentifiers", gridSnapshot())
        })
        capture(capturePrefix + "${fixture.slug}-page-$page", stable = false)
    }

    private fun gridSnapshot(): String {
        val root = checkNotNull(automation.rootInActiveWindow)
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
        val root = checkNotNull(automation.rootInActiveWindow)
        val moved = try {
            assertEquals(target.packageName, root.packageName?.toString())
            find(root) { it.isScrollable && it.performAction(action) }
        } finally { root.recycle() }
        if (moved) automation.waitForIdle(100, 2000)
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
        if (automation.windows.any {
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
        val root = checkNotNull(automation.rootInActiveWindow)
        try {
            var phraseBottom: Int? = null
            var footerTop: Int? = null
            find(root) { node ->
                val label = node.contentDescription?.toString() ?: node.text?.toString()
                val bounds = Rect().also(node::getBoundsInScreen)
                if (label?.startsWith("Общее сообщение:") == true) phraseBottom = bounds.bottom
                if (matches(node, listOf("Страница $page из ${fixture.pages}", "Предыдущая страница", "Следующая страница"))) {
                    footerTop = minOf(footerTop ?: bounds.top, bounds.top)
                }
                false
            }
            val positions = linkedSetOf<Pair<Int, Int>>()
            val minimum = Math.round(48f * target.resources.displayMetrics.density)
            check(find(root) { grid ->
                if (AccessibilityNodeInfoCompat.wrap(grid).stateDescription?.startsWith("Сетка ") != true) false
                else {
                    val collection = checkNotNull(grid.collectionInfo) { "Grid collection metadata missing" }
                    assertEquals("Full page rows", fixture.rows, collection.rowCount)
                    assertEquals("Full page columns", fixture.columns, collection.columnCount)
                    assertFalse("Fixed grid must not scroll", grid.isScrollable)
                    val viewport = Rect().also(grid::getBoundsInScreen)
                    assertTrue("Positive grid viewport", viewport.width() > 0 && viewport.height() > 0)
                    assertTrue("Grid below phrase", viewport.top >= checkNotNull(phraseBottom))
                    assertTrue("Grid above footer", viewport.bottom <= checkNotNull(footerTop))
                    find(grid) { node ->
                        node.collectionItemInfo?.let { item ->
                            val position = item.rowIndex to item.columnIndex
                            val bounds = Rect().also(node::getBoundsInScreen)
                            assertTrue("Cell $position row", item.rowIndex in 0 until fixture.rows)
                            assertTrue("Cell $position column", item.columnIndex in 0 until fixture.columns)
                            assertTrue("Cell $position has positive bounds", bounds.width() > 0 && bounds.height() > 0)
                            assertTrue("Cell $position fully within viewport", viewport.contains(bounds))
                            assertTrue("Cell $position width at least 48dp", bounds.width() >= minimum)
                            assertTrue("Cell $position height at least 48dp", bounds.height() >= minimum)
                            positions.add(position)
                        }
                        false
                    }
                    true
                }
            }) { "Actual board grid semantics missing" }
            assertEquals("Every cell, including EMPTY, must be visible", fixture.rows * fixture.columns, positions.size)
            val attachment = Bundle().apply {
                putString("smotriPage", "${fixture.slug}:$page")
                putString("smotriGrid", "rows=${fixture.rows};columns=${fixture.columns}")
                putString("smotriExpectedMode", fixture.mode)
                putInt("smotriVisibleCollectionItems", positions.size)
            }
            instrumentation.sendStatus(0, attachment)
        } finally { root.recycle() }
    }

    private fun publicState(prefix: String): String? {
        val root = automation.rootInActiveWindow ?: return null
        return try { publicState(prefix, root) } finally { root.recycle() }
    }

    private fun publicState(prefix: String, root: AccessibilityNodeInfo): String? {
        var state: String? = null
        find(root) { node ->
            val label = node.contentDescription?.toString() ?: node.text?.toString()
            if (label?.startsWith(prefix) == true) {
                state = "$label|${AccessibilityNodeInfoCompat.wrap(node).stateDescription}"
                true
            } else false
        }
        return state
    }

    private fun unchangedCommunication(root: AccessibilityNodeInfo, message: String, audio: String): Boolean {
        assertFalse("No playback or card selection", find(root) { matches(it, listOf("Остановить")) })
        val actualMessage = publicState("Общее сообщение:", root) ?: return false
        val actualAudio = publicState("Произнести", root) ?: return false
        assertEquals("Navigation must not edit message", message, actualMessage)
        assertEquals("Navigation must not start audio", audio, actualAudio)
        return true
    }

    private fun capture(name: String, stable: Boolean = true) {
        require(name.matches(Regex("[a-z0-9-]+")))
        if (stable) {
            awaitVisibleMedia()
            awaitStablePixels()
        }
        // UiAutomation rotates native pixels correctly; API21 shell screencap keeps the panel's portrait axes.
        val file = java.io.File(target.cacheDir, "smotri-$name.png")
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            file.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("smotriScreenshot", file.path) })
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
                val next = checkNotNull(automation.takeScreenshot())
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
        automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { descriptor ->
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
        val root = automation.rootInActiveWindow ?: return false
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
            val root = automation.rootInActiveWindow ?: continue
            try {
                assertTrue("Unexpected package at $state", root.packageName?.toString() in packages)
                if (predicate(root)) return
                if (scrollAction != null && scrolls < 24 && root.packageName?.toString() == target.packageName) {
                    swipeWindow(root, scrollAction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    scrolls++
                }
            } finally { root.recycle() }
        }
        automation.rootInActiveWindow?.let { root ->
            try {
                val nodes = mutableListOf<String>()
                find(root) { node ->
                    if (nodes.size < 80 && (!node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty())) {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        nodes += "${node.className}|${node.text}|${node.contentDescription}|enabled=${node.isEnabled}|${bounds.toShortString()}"
                    }
                    false
                }
                instrumentation.sendStatus(0, Bundle().apply { putString("smotriTimeoutTree", nodes.joinToString("\n")) })
            } finally { root.recycle() }
        }
        capture("timeout", stable = false)
        error("Timed out: $state; inspect state before retrying import")
    }

    private fun swipeWindow(root: AccessibilityNodeInfo, forward: Boolean) {
        val bounds = Rect().also(root::getBoundsInScreen)
        val keyboardTop = automation.windows.firstOrNull {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }?.let { Rect().also(it::getBoundsInScreen).top }
        val top = bounds.top + (bounds.height() * 0.12f).toInt()
        val bottom = minOf(bounds.bottom, keyboardTop ?: bounds.bottom)
        val height = bottom - top
        check(height > 60)
        // Stay inside the form content; the outer right gutter does not dispatch drags to its scroll container.
        val x = bounds.left + (bounds.width() * 0.8f).toInt()
        // Overlap short large-text viewports instead of jumping past a whole setting row.
        val start = top + (height * (if (forward) 0.65f else 0.35f)).toInt()
        val end = top + (height * (if (forward) 0.35f else 0.65f)).toInt()
        automation.executeShellCommand("input swipe $x $start $x $end 250").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        automation.waitForIdle(100, 2000)
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
                    if (action?.isEnabled != true) false else {
                        val bounds = Rect().also(node::getBoundsInScreen)
                        if (bounds.isEmpty) false else {
                            // Tap the labelled element itself: merged API21 nodes can route ACTION_CLICK to its tile.
                            automation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use { descriptor ->
                                FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                            }
                            automation.waitForIdle(100, 2000)
                            true
                        }
                    }
                } finally { action?.recycle() }
            }
        } }
    }
}
