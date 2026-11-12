package su.linka.pictures.activity

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a fresh CI development sandbox with bundled boards; never resets existing storage. */
@RunWith(AndroidJUnit4::class)
class MessageRowLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val food = "Еда и питьё"
    private val cards = listOf("Хочу", "Не хочу", "Ещё", "Вода", "Сок", "Чай", "Каша", "Суп", "Хватит")

    @Test fun defaultAndSavedMessageHeightKeepFoodGridAlignedAfterRelaunch() {
        assertEquals("su.linka.pictures.development", target.packageName)
        var originalHeight = 0
        ActivityScenario.launch(MainActivity::class.java).use {
            openSettings()
            awaitPercent(20)
            click(UiText.Back.labels)
            openFood()
            originalHeight = assertGridAndCapture("default-20")
            click(UiText.OpenLibrary.labels)
            openSettings()
            click(UiText.MessageRowHeight.labels, scroll = true)
            click(listOf("28%"))
            click(UiText.SaveSettings.labels, scroll = true)
            await("save completed and reloaded form is editable") { root -> find(root) { node ->
                matches(node, UiText.SaveSettings.labels) && withEnabledAction(node) { true }
            } }
            // Re-entering reads a new SettingsSnapshot instead of trusting the edited form.
            click(UiText.Back.labels)
            openSettings()
            awaitPercent(28)
            click(UiText.Back.labels)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            openSettings()
            awaitPercent(28)
            click(UiText.Back.labels)
            openFood()
            assertTrue("Saved 28% must increase the actual message region above the 20% baseline",
                assertGridAndCapture("saved-28-relaunched") > originalHeight)
            click(UiText.OpenLibrary.labels)
            val coreCards = listOf("Да", "Нет", "Хочу", "Не хочу", "Помоги", "Больно", "Пить", "Туалет", "Хватит")
            openBoard("Общаюсь", coreCards, backwards = true)
            await("core board page 1 of 3 with bottom navigation") { root ->
                find(root) { matches(it, UiText.Page.labels(1, 3)) } &&
                    find(root) { matches(it, UiText.PreviousPage.labels) } &&
                    find(root) { matches(it, UiText.NextPage.labels) }
            }
            assertGridAndCapture("core-28-centered-both-axes", coreCards, bottomNavigation = true)
        }
    }

    private fun openSettings() {
        await("library") { find(it) { node -> matches(node, UiText.YourBoards.labels) } }
        click(UiText.LibraryMenu.labels)
        click(UiText.Settings.labels)
        await("settings", scroll = true) { find(it) { node -> matches(node, UiText.TextScale.labels) } }
    }

    private fun awaitPercent(percent: Int) = await("message row $percent%", scroll = true) { root ->
        find(root) { node -> matches(node, UiText.MessageRowHeight.labels) } &&
            find(root) { node -> matches(node, listOf("$percent%")) }
    }

    private fun openFood() = openBoard(food, cards)

    private fun openBoard(board: String, labels: List<String>, backwards: Boolean = false) {
        await("library") { find(it) { node -> matches(node, UiText.YourBoards.labels) } }
        click(listOf(board), scroll = true, backwards = backwards)
        var previousReadiness = ""
        await("$board and all decoded images") { root ->
            val readiness = labels.associateWith { label ->
                var imageState = "missing actionable card"
                find(root) { node ->
                    matches(node, listOf(label)) && withEnabledAction(node) { card ->
                        imageState = AccessibilityNodeInfoCompat.wrap(card).stateDescription?.toString() ?: "missing image state"
                        true
                    }
                }
                imageState
            }
            val message = find(root) { node -> UiText.SharedMessage.starts(node.contentDescription) }
            val detail = "$board;message=$message;cards=$readiness"
            if (detail != previousReadiness) {
                previousReadiness = detail
                android.util.Log.i("MessageRowLayoutEvidence", detail)
            }
            message && readiness.values.all(UiText.ImageDisplayed::matches)
        }
    }

    private fun assertGridAndCapture(name: String, labels: List<String> = cards, bottomNavigation: Boolean = false): Int {
        var messageHeight = 0
        await("aligned grid $name") { root ->
            val window = Rect().also(root::getBoundsInScreen)
            val bounds = labels.mapIndexed { index, label ->
                var result: Rect? = null
                find(root) { node ->
                    matches(node, listOf(label)) && withEnabledAction(node) { card ->
                        assertEquals("Native row for $label", index / 3, card.collectionItemInfo?.rowIndex)
                        assertEquals("Native column for $label", index % 3, card.collectionItemInfo?.columnIndex)
                        result = Rect().also(card::getBoundsInScreen)
                        true
                    }
                }
                checkNotNull(result) { "Missing card: $label" }
            }
            var grid: Rect? = null
            find(root) { node ->
                if (node.collectionInfo?.let { it.rowCount == 3 && it.columnCount == 3 } == true) {
                    grid = Rect().also(node::getBoundsInScreen)
                    true
                } else false
            }
            var message: Rect? = null
            find(root) { node ->
                if (UiText.SharedMessage.starts(node.contentDescription)) {
                    message = Rect().also(node::getBoundsInScreen)
                    true
                } else false
            }
            val actualGrid = checkNotNull(grid) { "Missing native 3x3 collection bounds" }
            val actualMessage = checkNotNull(message) { "Missing native message bounds" }
            assertTrue("Message must have a visible area", actualMessage.width() > 0 && actualMessage.height() > 0)
            assertTrue("Grid must be below the message", actualGrid.top >= actualMessage.bottom)
            assertTrue("Grid must fit the native window", window.contains(actualGrid))
            assertTrue("Grid must be horizontally centered (integer AX coordinates)",
                abs(actualGrid.exactCenterX() - window.exactCenterX()) <= 1f)
            for ((index, cell) in bounds.withIndex()) {
                assertTrue("Card ${labels[index]} must be fully visible", !cell.isEmpty && actualGrid.contains(cell))
                assertEquals("Equal cell widths", bounds[0].width(), cell.width())
                assertEquals("Equal cell heights", bounds[0].height(), cell.height())
                val expectedLeft = bounds[0].left + index % 3 * bounds[0].width()
                val expectedTop = bounds[0].top + index / 3 * bounds[0].height()
                assertEquals("Column alignment for ${labels[index]}", expectedLeft, cell.left)
                assertEquals("Row alignment for ${labels[index]}", expectedTop, cell.top)
            }
            assertEquals(actualGrid.left, bounds.first().left)
            assertEquals(actualGrid.top, bounds.first().top)
            assertEquals(actualGrid.right, bounds.last().right)
            assertEquals(actualGrid.bottom, bounds.last().bottom)
            if (bottomNavigation) {
                fun namedBounds(text: UiText): Rect {
                    var result: Rect? = null
                    find(root) { node ->
                        matches(node, text.labels) && withButtonNode(node) { button ->
                            result = Rect().also(button::getBoundsInScreen)
                            true
                        }
                    }
                    return checkNotNull(result) { "Missing $text geometry" }
                }
                val speech = namedBounds(UiText.Speak)
                val previous = namedBounds(UiText.PreviousPage)
                val next = namedBounds(UiText.NextPage)
                assertEquals("Bottom page buttons share the footer top", previous.top, next.top)
                assertEquals("Bottom page buttons share the footer bottom", previous.bottom, next.bottom)
                assertTrue("Previous page button is disabled on page 1", find(root) {
                    matches(it, UiText.PreviousPage.labels) && withButtonNode(it) { button -> !button.isEnabled }
                })
                // Speech spans the full message row; the message AX node itself has 2dp padding.
                val divider = target.resources.displayMetrics.density.roundToInt()
                val gridArea = Rect(window.left, speech.bottom + divider, window.right, previous.top)
                val cellsUnion = Rect().apply { bounds.forEach { union(it) } }
                assertEquals("Collection bounds equal the actual nine-cell union", actualGrid, cellsUnion)
                assertTrue("Grid must fit between message divider and bottom page controls", gridArea.contains(cellsUnion))
                assertTrue("Grid must be vertically centered in its actual available area",
                    abs(cellsUnion.exactCenterY() - gridArea.exactCenterY()) <= 2f)
                assertTrue("Grid must be horizontally centered in its actual available area",
                    abs(cellsUnion.exactCenterX() - gridArea.exactCenterX()) <= 1f)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("messageRowCenterGeometry", "$name;area=$gridArea;cells=$cellsUnion;dividerPx=$divider")
                })
            }
            messageHeight = actualMessage.height()
            instrumentation.sendStatus(0, Bundle().apply {
                putString("messageRowGeometry", "$name;message=$actualMessage;grid=$actualGrid;cards=$bounds")
            })
            true
        }
        capture(name)
        return messageHeight
    }

    private fun capture(name: String) {
        // CI can pull /sdcard/Android/data/<development package>/files/message-row-layout/.
        val directory = File(checkNotNull(target.getExternalFilesDir(null)), "message-row-layout")
        check(directory.isDirectory || directory.mkdirs())
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val file = File(directory, "$name.png")
            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            instrumentation.sendStatus(0, Bundle().apply { putString("messageRowScreenshot", file.absolutePath) })
        } finally { image.recycle() }
    }

    private fun click(labels: List<String>, scroll: Boolean = false, backwards: Boolean = false) =
        await("click ${labels.first()}", scroll, backwards) { root ->
        find(root) { node ->
            matches(node, labels) && !node.isEditable && withEnabledAction(node) {
                it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
        }
    }

    private fun withEnabledAction(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Boolean) =
        withButtonNode(node) { it.isVisibleToUser && it.isEnabled && action(it) }

    private fun withButtonNode(node: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try {
            while (candidate != null && !candidate.isClickable && candidate.className?.toString() != "android.widget.Button") {
                val parent = candidate.parent
                candidate.recycle()
                candidate = parent
            }
            return candidate?.let(action) ?: false
        } finally { candidate?.recycle() }
    }

    private fun matches(node: AccessibilityNodeInfo, labels: List<String>) =
        node.text?.toString() in labels || node.contentDescription?.toString() in labels

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (node.isVisibleToUser && !node.isPassword && predicate(node)) return true
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (find(child, predicate)) return true } finally { child.recycle() }
        }
        return false
    }

    private fun await(state: String, scroll: Boolean = false, backwards: Boolean = false,
        predicate: (AccessibilityNodeInfo) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        val search = ScrollSearch(backwards)
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: continue
            try {
                // Splash/window transitions may briefly expose System UI; never act on it.
                if (root.packageName?.toString() != target.packageName) continue
                if (predicate(root)) return
                if (scroll) search.advance { direction ->
                    scrollTestWindow(instrumentation.uiAutomation, root, !direction)
                }
            } finally { root.recycle() }
        }
        capture("timeout")
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root != null) try {
            if (root.packageName?.toString() == target.packageName) {
                val tree = buildString {
                    fun appendNode(node: AccessibilityNodeInfo, depth: Int) {
                        if (node.isPassword) return
                        val bounds = Rect().also(node::getBoundsInScreen)
                        appendLine("${" ".repeat(depth)}text=${node.text};desc=${node.contentDescription};state=${AccessibilityNodeInfoCompat.wrap(node).stateDescription};click=${node.isClickable};visible=${node.isVisibleToUser};bounds=$bounds")
                        for (index in 0 until node.childCount) {
                            val child = node.getChild(index) ?: continue
                            try { appendNode(child, depth + 1) } finally { child.recycle() }
                        }
                    }
                    appendNode(root, 0)
                }
                File(target.getExternalFilesDir(null), "message-row-layout/timeout-tree.txt").writeText(tree)
                tree.lineSequence().forEach { android.util.Log.i("MessageRowLayoutEvidence", it) }
                instrumentation.sendStatus(0, Bundle().apply { putString("messageRowTimeoutTree", tree) })
            }
        } finally { root.recycle() }
        fail("Timed out waiting for $state; requires fresh bundled CI boards, no storage reset is performed")
    }
}
