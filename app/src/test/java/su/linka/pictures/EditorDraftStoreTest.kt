package su.linka.pictures

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.linka.pictures.components.ComponentTestUtils
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class EditorDraftStoreTest {
    @Test
    fun newOwnerRestoresPersistedModelAndMediaWithoutCacheOrOriginalArchiveChanges() {
        val (context, source, workspace) = ComponentTestUtils.createSet(cards = listOf(Card(5, cardType = 3)))
        val original = File(workspace.parentFile, "original-${System.nanoTime()}.linka").apply { writeText("original") }
        source.getManifest().archiveFile = original
        File(workspace, "image.png").writeText("image")
        val store = EditorDraftStore(context)
        val draft = store.create(source)
        try {
            assertEquals(3, draft.set.getManifest().cards[0].cardType)
            draft.set.getManifest().rows = 2
            draft.set.addCard(1, Card(6, "image.png", "unsaved", null, 0))
            store.capture(draft.token, draft.set, JSONObject().put("name", "renamed.linka").put("page", 1))
            workspace.deleteRecursively()

            val restored = EditorDraftStore(context).restore(draft.token)!!

            assertEquals(2, restored.set.getManifest().rows)
            assertEquals(3, restored.set.getManifest().cards[0].cardType)
            assertEquals("unsaved", restored.set.getManifest().cards[1].title)
            assertEquals("image", File(restored.set.getFolder(), "image.png").readText())
            assertEquals("renamed.linka", restored.state.getString("name"))
            assertEquals(1, restored.state.getInt("page"))
            assertEquals("original", original.readText())
            assertTrue(store.owns(draft.token, restored.set.getFolder()))
            assertFalse(store.owns(draft.token, original.parentFile!!))
            assertTrue(restored.set.getFolder().canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        } finally {
            store.discard(draft.token)
            original.delete()
            workspace.deleteRecursively()
        }
    }

    @Test
    fun rejectsTraversalAndCorruptSnapshotWithoutDeletingForeignWorkspace() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = EditorDraftStore(context)
        assertNull(store.restore("../../sets"))
        assertThrows(IllegalArgumentException::class.java) { store.discard("../sets") }
        val (_, source, workspace) = ComponentTestUtils.createSet()
        val draft = store.create(source)
        try {
            store.capture(draft.token, draft.set, JSONObject())
            source.getManifest().cards.add(Card(0, "../../outside.png", "bad", null, 0))
            draft.set.getManifest().cards.add(source.getManifest().cards[0])
            assertThrows(IllegalArgumentException::class.java) {
                store.capture(draft.token, draft.set, JSONObject())
            }
            assertTrue(store.restore(draft.token)!!.set.getManifest().cards.isEmpty())
            val snapshotFile = File(draft.set.getFolder().parentFile, "snapshot.json")
            val snapshot = JSONObject(snapshotFile.readText())
            snapshot.getJSONObject("model").getJSONArray("cards").put(EditorDraftJson.card(source.getManifest().cards[0]))
            snapshotFile.writeText(snapshot.toString())
            assertNull(EditorDraftStore(context).restore(draft.token))
            store.discard(draft.token)
            assertTrue(workspace.isDirectory)
        } finally {
            store.discard(draft.token)
            workspace.deleteRecursively()
        }
    }
}
