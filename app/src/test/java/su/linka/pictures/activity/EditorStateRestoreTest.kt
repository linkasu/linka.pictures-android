package su.linka.pictures.activity

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import su.linka.pictures.Callback
import su.linka.pictures.Card
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.SetsManager
import su.linka.pictures.components.CardGrid
import su.linka.pictures.components.EditCardDialog
import su.linka.pictures.components.OutputLine
import su.linka.pictures.components.ComponentTestUtils
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class EditorStateRestoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun newEditorOwnerRestoresUnsavedBoardDialogMediaPageAndCurrentCallback() {
        val manager = SetsManager(context)
        val name = "restore-${System.nanoTime()}.linka"
        val source = manager.createSet()
        source.getManifest().rows = 1
        source.getManifest().columns = 1
        source.addCard(0, Card(0, cardType = 1))
        source.addCard(1, Card(1, cardType = 1))
        manager.save(source, name, saveCallback())
        val originalBytes = manager.getSetFile(name).readBytes()
        val intent = Intent(context, SetEditActivity::class.java).putExtra(SetEditActivity.EXTRA_FILE, name)
        val first = Robolectric.buildActivity(SetEditActivity::class.java, intent).setup()
        val owner = first.get()
        val board = field<Set>(owner, "set")
        board.getManifest().withoutSpace = true
        owner.findViewById<CardGrid>(R.id.card_grid).jumpToPage(1)
        SetEditActivity::class.java.getDeclaredMethod("onCardSelected", Card::class.java, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(owner, null, 1, false)
        val dialog = field<EditCardDialog>(owner, "cardDialog")
        dialog.findViewById<EditText>(R.id.card_title_edittext)!!.setText("Draft title")
        setField(dialog, "currentBitmap", Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888))
        setField(dialog, "imageDirty", true)
        val audio = File(owner.cacheDir, "ready-audio").apply { writeText("ready") }
        setField(dialog, "currentAudio", audio)
        setField(dialog, "audioDirty", true)
        dialog.findViewById<android.view.View>(R.id.choose_image_button)!!.performClick()
        val state = Bundle()
        first.saveInstanceState(state).pause().stop().destroy()
        assertTrue("Destroy must preserve the active durable draft", board.getFolder().isDirectory)
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.writeBundle(state)
            assertTrue("Bitmap/model must not be embedded in Bundle", parcel.dataSize() < 64 * 1024)
        } finally {
            parcel.recycle()
        }
        audio.delete()
        source.getFolder().deleteRecursively()

        val second = Robolectric.buildActivity(SetEditActivity::class.java, intent).setup(state)
        val restoredOwner = second.get()
        try {
            val restored = field<EditCardDialog>(restoredOwner, "cardDialog")
            assertTrue(restored.isShowing)
            assertTrue(restored.validate())
            assertTrue(field(restored, "pendingImage"))
            assertEquals("Draft title", restored.findViewById<EditText>(R.id.card_title_edittext)!!.text.toString())
            assertEquals(1, restoredOwner.findViewById<CardGrid>(R.id.card_grid).currentPage())
            assertTrue(field<Set>(restoredOwner, "set").getManifest().withoutSpace)
            // A cancelled external chooser belongs to the restored dialog, not the destroyed owner.
            SetEditActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                .invoke(restoredOwner, field<Int>(restored, "imageRequestCode"), android.app.Activity.RESULT_CANCELED, null)
            assertFalse(field(restored, "pendingImage"))
            restored.findViewById<android.view.View>(R.id.positiveBtn)!!.performClick()
            val cards = field<Set>(restoredOwner, "set").getManifest().cards
            assertEquals(1, cards[0].cardType)
            assertEquals("Draft title", cards[1].title)
            assertArrayEquals(originalBytes, manager.getSetFile(name).readBytes())
        } finally {
            SetEditActivity::class.java.getDeclaredMethod("discardDraft").apply { isAccessible = true }.invoke(restoredOwner)
            second.pause().stop().destroy()
            manager.getSetFile(name).delete()
        }
    }

    @Test
    fun communicationRestoresFullSelectionAndPageOnlyForSameDocumentWithSmallBundle() {
        val manager = SetsManager(context)
        val name = "phrase-${System.nanoTime()}.linka"
        val source = manager.createSet()
        source.getManifest().rows = 1
        source.getManifest().columns = 1
        source.getManifest().withoutSpace = true
        source.addCard(0, Card(0, cardType = 1))
        source.addCard(1, Card(1, cardType = 1))
        manager.save(source, name, saveCallback())
        val intent = Intent(context, GridActivity::class.java).putExtra(GridActivity.EXTRA_FILE, name)
        val first = Robolectric.buildActivity(GridActivity::class.java, intent).setup()
        val firstWorkspace = field<Set>(first.get(), "set").getFolder()
        val line = first.get().findViewById<OutputLine>(R.id.output_line)
        val cards = field<Set>(first.get(), "set").getManifest().cards
        val sequence = IntArray(2401) { (it * 7 + it / 4) % cards.size }
        sequence.forEach { line.addCard(cards[it]) }
        first.get().findViewById<CardGrid>(R.id.card_grid).jumpToPage(1)
        val state = Bundle()
        first.saveInstanceState(state).pause().stop().destroy()
        assertFalse("Destroy must release the playback workspace", firstWorkspace.exists())
        assertFalse(state.containsKey("selection"))
        assertNotNull(state.getString("phraseDraftToken"))
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.writeBundle(state)
            assertTrue("Full phrase must be stored outside Bundle", parcel.dataSize() < 8 * 1024)
        } finally {
            parcel.recycle()
        }
        val second = Robolectric.buildActivity(GridActivity::class.java, intent).setup(state)
        assertArrayEquals(sequence, second.get().findViewById<OutputLine>(R.id.output_line).captureSelection())
        assertEquals(1, second.get().findViewById<CardGrid>(R.id.card_grid).currentPage())
        second.pause().stop().destroy()
        manager.getSetFile(name).setLastModified(manager.getSetFile(name).lastModified() + 1000)
        val changed = Robolectric.buildActivity(GridActivity::class.java, intent).setup(state)
        try {
            assertEquals(0, changed.get().findViewById<OutputLine>(R.id.output_line).captureSelection().size)
            assertEquals(0, changed.get().findViewById<CardGrid>(R.id.card_grid).currentPage())
        } finally {
            changed.pause().stop().destroy()
            manager.getSetFile(name).delete()
            source.getFolder().deleteRecursively()
        }
    }

    @Test
    fun recreationKeepsTokenAndOnlyExplicitSaveChangesArchiveWhileSaveAndCancelCleanOwnedDraft() {
        for (save in listOf(false, true)) {
            val manager = SetsManager(context)
            val name = "finish-${System.nanoTime()}.linka"
            val source = manager.createSet()
            source.getManifest().rows = 1
            manager.save(source, name, saveCallback())
            val before = manager.getSetFile(name).readBytes()
            val intent = Intent(context, SetEditActivity::class.java).putExtra(SetEditActivity.EXTRA_FILE, name)
            val controller = Robolectric.buildActivity(SetEditActivity::class.java, intent).setup()
            val first = controller.get()
            field<Set>(first, "set").getManifest().rows = 2
            first.findViewById<EditText>(R.id.rows_count).setText("2")
            val token = field<String>(first, "draftToken")
            controller.recreate()
            val owner = controller.get()
            val workspace = field<Set>(owner, "set").getFolder()
            try {
                assertEquals(token, field<String>(owner, "draftToken"))
                assertEquals(2, field<Set>(owner, "set").getManifest().rows)
                assertArrayEquals(before, manager.getSetFile(name).readBytes())
                SetEditActivity::class.java.getDeclaredMethod(if (save) "save" else "close")
                    .apply { isAccessible = true }.invoke(owner)
                ShadowAlertDialog.getLatestAlertDialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertFalse(workspace.exists())
                if (save) {
                    val saved = manager.getSet(name)
                    assertEquals(2, saved.getManifest().rows)
                    saved.getFolder().deleteRecursively()
                } else {
                    assertArrayEquals(before, manager.getSetFile(name).readBytes())
                }
                assertTrue(source.getFolder().exists())
            } finally {
                controller.pause().stop().destroy()
                manager.getSetFile(name).delete()
                source.getFolder().deleteRecursively()
            }
        }
    }

    @Test
    fun phraseSnapshotIsCompleteAndInvalidIndicesAreSkippedWithoutDirectPlayback() {
        val card = Card(7, title = "word", cardType = 0)
        val (themed, board, workspace) = ComponentTestUtils.createSet(cards = listOf(card), withoutSpace = true)
        val output = OutputLine(themed)
        output.setSet(board)
        try {
            repeat(600) { output.addCard(card) }
            assertArrayEquals(IntArray(600), output.captureSelection())
            output.clear()
            output.restoreSelection(IntArray(623))
            assertArrayEquals(IntArray(623), output.captureSelection())
            output.setDirectMode(true)
            output.restoreSelection(intArrayOf(-1, 0, Int.MAX_VALUE, 0))
            assertArrayEquals(intArrayOf(0, 0), output.captureSelection())
            assertFalse(field<android.media.MediaPlayer>(output, "mediaPlayer").isPlaying)
            output.addCard(card)
            assertEquals(2, output.captureSelection().size)
        } finally {
            output.release()
            workspace.deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner) as T

    private fun setField(owner: Any, name: String, value: Any) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    private fun saveCallback() = object : Callback<Any?>() {
        override fun onDone(result: Any?) = Unit
        override fun onFail(error: Exception?) { throw AssertionError(error) }
    }
}
