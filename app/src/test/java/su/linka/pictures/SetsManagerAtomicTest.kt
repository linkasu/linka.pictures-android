package su.linka.pictures

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SetsManagerAtomicTest {
    private lateinit var manager: SetsManager

    @Before fun setup() {
        manager = SetsManager(ApplicationProvider.getApplicationContext<Context>())
        manager.getSetsDirectory().listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun archive(config: String = "{}", path: String = "config.json"): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(config.toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

    private fun save(set: Set, name: String = "same.linka", replace: Boolean = false): Exception? {
        var failure: Exception? = null
        var called = false
        manager.save(set, name, replace, object : Callback<Any?>() {
            override fun onDone(result: Any?) { called = true }
            override fun onFail(error: Exception?) { called = true; failure = error }
        })
        assertTrue(called)
        return failure
    }

    @Test fun importCollisionDoesNotConsumeInputOrWriteUntilConfirmed() {
        val original = archive()
        val destination = manager.importSet(original.inputStream(), "same.linka")
        val replacement = archive("""{"rows":2}""")
        val input = replacement.inputStream()
        assertThrows(SetAlreadyExistsException::class.java) { manager.importSet(input, "same.linka") }
        assertEquals(replacement.size, input.available())
        assertArrayEquals(original, destination.readBytes())
        manager.importSet(input, "same.linka", true)
        assertArrayEquals(replacement, destination.readBytes())
        assertEquals(2, manager.getSetManifest("same.linka").rows)
    }

    @Test fun invalidOrInterruptedImportCannotReplaceArchive() {
        val original = archive()
        val destination = manager.importSet(original.inputStream(), "same.linka")
        val broken = object : InputStream() {
            var count = 0
            override fun read(): Int = if (count++ < 10) 42 else throw IOException("Interrupted")
        }
        val invalid = listOf(
            broken, "not a zip".byteInputStream(), archive("{bad").inputStream(),
            archive("{}", "../escape").inputStream(), archive("{}", "other").inputStream(),
            archive("""{"rows":0}""").inputStream()
        )
        for (input in invalid) {
            assertThrows(IOException::class.java) { manager.importSet(input, "same.linka", true) }
            assertArrayEquals(original, destination.readBytes())
            assertEquals(listOf("same.linka"), manager.getSetsDirectory().list()!!.toList())
        }
    }

    @Test fun createCancellationConfirmationAndOwnedUpdate() {
        val destination = manager.importSet(archive().inputStream(), "same.linka")
        val original = destination.readBytes()
        val set = manager.createSet()
        set.getManifest().rows = 2
        assertTrue(save(set) is SetAlreadyExistsException)
        assertNull(set.getManifest().archiveFile)
        assertArrayEquals(original, destination.readBytes())
        assertNull(save(set, replace = true))
        assertEquals(destination, set.getManifest().archiveFile?.canonicalFile)
        set.getManifest().rows = 4
        assertNull(save(set))
        assertEquals(4, manager.getSetManifest("same.linka").rows)
    }

    @Test fun failedSavePreservesArchiveManifestAndPreview() {
        val destination = manager.importSet(archive().inputStream(), "same.linka")
        val set = manager.getSet("same.linka")
        manager.getSetManifest("same.linka")
        val preview = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "previews/same/config.json")
        val original = destination.readBytes()
        set.getManifest().rows = 0
        assertTrue(save(set) is IOException)
        assertArrayEquals(original, destination.readBytes())
        assertEquals(destination, set.getManifest().archiveFile?.canonicalFile)
        assertTrue(preview.exists())
    }

    @Test fun temporaryArchivesAreNeverListed() {
        val directory = manager.getSetsDirectory()
        File(directory, ".set-interrupted.tmp").writeBytes(archive())
        File(directory, "other.zip").writeBytes(archive())
        manager.importSet(archive().inputStream(), "visible.linka")
        assertEquals(listOf("visible.linka"), manager.getSets().map { it.name })
    }

    @Test fun safeNamesAndRenameCollision() {
        for (name in listOf("../bad.linka", "/bad.linka", "a/b.linka", "a\\b.linka", "bad.zip", ".linka")) {
            assertThrows(IOException::class.java) { manager.importSet(archive().inputStream(), name) }
        }
        val first = manager.importSet(archive().inputStream(), "first.linka")
        val second = manager.importSet(archive("""{"rows":2}""").inputStream(), "second.linka")
        val manifest = manager.getSetManifest("first.linka")
        assertThrows(SetAlreadyExistsException::class.java) { manager.rename(manifest, "second") }
        assertEquals(first, manifest.archiveFile?.canonicalFile)
        assertEquals(2, manager.getSetManifest(second).rows)
        manager.rename(manifest, "second", true)
        assertFalse(first.exists())
        assertEquals(second, manifest.archiveFile)
        assertEquals(3, manager.getSetManifest(second).rows)
    }

    @Test fun differentManagersCannotBothCreateSameName() {
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map {
                executor.submit<Boolean> {
                    start.await()
                    try {
                        SetsManager(ApplicationProvider.getApplicationContext<Context>())
                            .importSet(archive().inputStream(), "same.linka")
                        true
                    } catch (_: SetAlreadyExistsException) { false }
                }
            }
            start.countDown()
            assertEquals(1, results.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, manager.getSets().size)
        } finally { executor.shutdownNow() }
    }

    @Test fun failedRenameDoesNotChangeManifest() {
        val source = manager.importSet(archive().inputStream(), "source.linka")
        val manifest = manager.getSetManifest("source.linka")
        val previous = manifest.archiveFile
        assertTrue(source.delete())
        assertThrows(IOException::class.java) { manager.rename(manifest, "new") }
        assertSame(previous, manifest.archiveFile)
        assertFalse(manager.getSetFile("new.linka").exists())
    }

    @Test fun commitFailureDoesNotPublishManifestOrLeaveTemporaryFile() {
        val destination = manager.getSetFile("same.linka").apply { mkdir() }
        File(destination, "keep").writeText("original")
        val fresh = manager.createSet()
        assertTrue(save(fresh, replace = true) is IOException)
        assertNull(fresh.getManifest().archiveFile)
        assertEquals("original", File(destination, "keep").readText())
        assertEquals(listOf("same.linka"), manager.getSetsDirectory().list()!!.toList())
    }

    @Test fun cannotWriteThroughInDirectoryOrDanglingSymlink() {
        val directory = manager.getSetsDirectory()
        val original = archive()
        val destination = manager.importSet(original.inputStream(), "original.linka")
        val linked = File(directory, "linked.linka")
        Files.createSymbolicLink(linked.toPath(), destination.toPath())
        assertThrows(IOException::class.java) { manager.importSet(archive().inputStream(), linked.name, true) }
        assertArrayEquals(original, destination.readBytes())
        val missing = File(directory, "missing.linka")
        val dangling = File(directory, "dangling.linka")
        Files.createSymbolicLink(dangling.toPath(), missing.toPath())
        assertThrows(IOException::class.java) { manager.importSet(archive().inputStream(), dangling.name, true) }
        assertFalse(missing.exists())
    }

    @Test fun configWriteErrorReachesCallbackWithoutReplacingOldArchive() {
        val destination = manager.importSet(archive().inputStream(), "same.linka")
        val original = destination.readBytes()
        val set = manager.getSet("same.linka")
        val config = set.getManifest().configFile
        assertTrue(config.delete())
        assertTrue(config.mkdir())
        assertTrue(save(set) is IOException)
        assertArrayEquals(original, destination.readBytes())
    }

    @Test fun missingReferencedMediaCannotReplaceAnExistingArchive() {
        val destination = manager.importSet(archive().inputStream(), "same.linka")
        val original = destination.readBytes()
        val opened = manager.getSet("same.linka")
        try {
            opened.getManifest().cards.add(Card(1, "missing.png"))
            assertTrue(save(opened) is IOException)
            assertArrayEquals(original, destination.readBytes())
            for (field in listOf("imagePath", "audioPath")) {
                for (path in listOf("missing.png", "config.json")) {
                    val config = """{"cards":[{"id":1,"$field":"$path"}]}"""
                    assertThrows(IOException::class.java) {
                        manager.importSet(archive(config).inputStream(), "same.linka", true)
                    }
                    assertArrayEquals(original, destination.readBytes())
                }
            }
        } finally { opened.close() }
    }

    @Test fun defaultInstallRetryNeverOverwritesAnExistingSet() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val names = requireNotNull(context.assets.list("sets"))
        assertEquals(6, names.size)
        names.forEach { manager.importSet(archive("""{"rows":2}""").inputStream(), it) }
        val destination = manager.getSetFile(names.first())
        val edited = destination.readBytes()
        Cookie(context).set(Cookie.ASSETS_LOADER, false)
        manager.loadDefaultSets()
        assertArrayEquals(edited, destination.readBytes())
        assertTrue(Cookie(context).get(Cookie.ASSETS_LOADER, false))
    }

}
