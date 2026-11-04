package su.linka.pictures

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WorkspaceRetentionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = SetsManager(context)
    private val callback = object : Callback<Any?>() {
        override fun onDone(result: Any?) = Unit
        override fun onFail(error: Exception?) { throw AssertionError(error) }
    }

    @Test
    fun repeatedOpenCloseReclaimsOnlyOwnedWorkspacesWithoutChangingArchive() {
        val source = manager.createSet()
        val name = "retention-${System.nanoTime()}.linka"
        val archive = manager.getSetFile(name)
        try {
            manager.save(source, name, callback)
            val bytes = archive.readBytes()
            source.close()
            repeat(5) {
                val opened = manager.getSet(name)
                val folder = opened.getFolder()
                assertTrue(folder.isDirectory)
                opened.close()
                opened.close()
                assertFalse(folder.exists())
                assertArrayEquals(bytes, archive.readBytes())
            }
            val foreign = Files.createTempDirectory("unowned-workspace").toFile()
            try {
                File(foreign, "user-file").writeText("keep")
                Set(SetManifest(File(foreign, "config.json")), foreign).close()
                assertEquals("keep", File(foreign, "user-file").readText())
            } finally {
                foreign.deleteRecursively()
            }
        } finally {
            source.close()
            archive.delete()
        }
    }

    @Test
    fun failedOpenRemovesItsWorkspaceAndPreservesSourceArchive() {
        val archive = manager.getSetFile("broken-${System.nanoTime()}.linka")
        val root = File(context.cacheDir, "workspace").apply { mkdirs() }
        val before = root.list()!!.toSet()
        archive.writeText("not a zip")
        try {
            assertThrows(Exception::class.java) { manager.getSet(archive.name) }
            assertEquals(before, root.list()!!.toSet())
            assertEquals("not a zip", archive.readText())
        } finally {
            archive.delete()
        }
    }

    @Test
    fun packagingExcludesDeletedAndReplacedAudioButKeepsSharedNestedAssets() {
        val board = manager.createSet()
        val name = "filtered-${System.nanoTime()}.linka"
        val archive = manager.getSetFile(name)
        try {
            listOf("old.wav", "deleted.wav", "current.wav", "shared/image.png").forEach { path ->
                File(board.getFolder(), path).apply { parentFile!!.mkdirs(); writeText(path) }
            }
            board.getManifest().cards.clear()
            board.addCard(0, Card(0, "shared/image.png", "first", "old.wav", 0))
            board.addCard(1, Card(1, "shared/image.png", "second", "deleted.wav", 0))
            board.addCard(0, Card(0, "shared/image.png", "replacement", "current.wav", 0))
            board.addCard(1, Card(1, cardType = 3))
            manager.save(board, name, callback)
            ZipFile(archive).use { zip ->
                assertEquals(setOf("config.json", "current.wav", "shared/image.png"),
                    zip.entries().asSequence().map { it.name }.toSet())
            }
            // Pending dialog/snapshot may still reference these files until explicit finish.
            assertTrue(File(board.getFolder(), "old.wav").exists())
            board.addCard(0, Card(0, cardType = 3))
            manager.save(board, name, callback)
            ZipFile(archive).use { zip -> assertEquals(listOf("config.json"), zip.entries().asSequence().map { it.name }.toList()) }
        } finally {
            board.close()
            archive.delete()
        }
    }

    @Test
    fun genericZipWithoutFilterStillIncludesUnreferencedFiles() {
        val board = manager.createSet()
        val archive = File.createTempFile("generic", ".zip")
        try {
            File(board.getFolder(), "unreferenced.wav").writeText("generic data")
            Utils.zipFolder(board.getFolder().absolutePath, archive.absolutePath)
            ZipFile(archive).use { zip -> assertNotNull(zip.getEntry("unreferenced.wav")) }
        } finally {
            board.close()
            archive.delete()
        }
    }

    @Test
    fun durableSnapshotsSurviveCloseAndUnrelatedScratchCleanupWithoutAgeLimit() {
        val source = manager.createSet()
        val folder = source.getFolder()
        File(folder, "image.png").writeText("active")
        val store = EditorDraftStore(context)
        val draft = store.create(source)
        val phrases = PhraseDraftStore(context)
        val phrase = phrases.capture(null, "document", intArrayOf(2, 4))
        try {
            assertFalse(folder.exists())
            store.capture(draft.token, draft.set, JSONObject().put("active", true))
            draft.set.getFolder().parentFile!!.walkTopDown().forEach { it.setLastModified(1) }
            File(context.noBackupFilesDir, "phrase-drafts/$phrase.bin").setLastModified(1)
            draft.set.close()
            manager.createSet().close()
            val restored = EditorDraftStore(context).restore(draft.token)!!
            assertTrue(restored.state.getBoolean("active"))
            assertEquals("active", File(restored.set.getFolder(), "image.png").readText())
            assertArrayEquals(intArrayOf(2, 4), PhraseDraftStore(context).restore(phrase, "document"))
        } finally {
            store.discard(draft.token)
            phrases.discard(phrase)
        }
    }

    @Test
    fun failedDraftCopyRemovesOnlyPartialDraftAndRetainsSourceAndActiveSnapshot() {
        val store = EditorDraftStore(context)
        val active = store.create(manager.createSet())
        store.capture(active.token, active.set, JSONObject().put("active", true))
        val source = manager.createSet()
        val outside = File.createTempFile("outside", ".wav")
        val root = File(context.noBackupFilesDir, "editor-drafts")
        val before = root.list()!!.toSet()
        try {
            Files.createSymbolicLink(File(source.getFolder(), "outside.wav").toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { store.create(source) }
            assertEquals(before, root.list()!!.toSet())
            assertTrue(source.getFolder().isDirectory)
            assertNotNull(EditorDraftStore(context).restore(active.token))
            assertTrue(outside.exists())
        } finally {
            source.close()
            outside.delete()
            store.discard(active.token)
        }
    }
}
