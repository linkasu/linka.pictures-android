package su.linka.pictures

import net.lingala.zip4j.ZipFile
import org.json.JSONException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipFile as JavaZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BoardArchiveContractTest {
    private val root = Files.createTempDirectory("board-archive-contract").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun allSixBundledArchivesRoundTripWithoutLosingMedia() {
        // Read source fixtures: Robolectric's local-test APK mangles non-ASCII asset names.
        val fixtures = File("src/main/assets/sets").listFiles()!!.filter { it.extension == "linka" }
        assertEquals(6, fixtures.size)
        fixtures.forEachIndexed { index, fixture ->
            val source = File(root, "source-$index.linka")
            fixture.copyTo(source)
            val workspace = File(root, "workspace-$index").apply { mkdirs() }
            ZipFile(source).extractAll(workspace.absolutePath)
            val config = File(workspace, "config.json")
            val document = BoardCodec.decode(config.readText())
            val manifest = SetManifest(config, JSONObject(config.readText()), source)
            assertEquals(document, manifest.toDocument())
            Set(manifest, workspace).writeConfig()
            val output = File(root, "output-$index.linka")
            Utils.zipFolder(workspace.absolutePath, output.absolutePath)
            JavaZipFile(source).use { before ->
                JavaZipFile(output).use { after ->
                    val entries = before.entries().asSequence().filterNot { it.isDirectory }.toList()
                    assertEquals(entries.map { it.name }.toSet(),
                        after.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet())
                    entries.forEach { entry ->
                        val bytes = after.getInputStream(after.getEntry(entry.name)).use { it.readBytes() }
                        if (entry.name == "config.json") {
                            assertEquals(document, BoardCodec.decode(bytes.toString(Charsets.UTF_8)))
                        } else {
                            assertArrayEquals(entry.name, before.getInputStream(entry).use { it.readBytes() }, bytes)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun nestedMediaSurvivesSaveArchiveAndReopen() {
        val workspace = File(root, "nested").apply { mkdirs() }
        val image = File(workspace, "images/deep/card.png").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val audio = File(workspace, "audio/deep/card.ogg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(4, 5, 6)) }
        val document = BoardDocument(cards = listOf(Card(1, "images/deep/card.png", "Nested", "audio/deep/card.ogg")))
        val config = File(workspace, "config.json")
        val manifest = SetManifest(config, document)
        document.cards.single().title = "Changed outside adapter"
        assertEquals("Nested", manifest.cards.single().title)
        Set(manifest, workspace).writeConfig()
        val output = File(root, "nested.linka")
        Utils.zipFolder(workspace.absolutePath, output.absolutePath)
        val reopened = File(root, "reopened").apply { mkdirs() }
        ZipFile(output).extractAll(reopened.absolutePath)
        assertEquals(manifest.toDocument(), SetManifest(File(reopened, "config.json"),
            JSONObject(File(reopened, "config.json").readText())).toDocument())
        assertArrayEquals(image.readBytes(), File(reopened, "images/deep/card.png").readBytes())
        assertArrayEquals(audio.readBytes(), File(reopened, "audio/deep/card.ogg").readBytes())
    }

    @Test
    fun unknownVersionAndUnsafeMediaAreRejectedWithoutOverwritingConfig() {
        val config = File(root, "config.json").apply { writeText("original") }
        val error = assertThrows(JSONException::class.java) {
            SetManifest(config, JSONObject("""{"version":"2.0"}"""))
        }
        assertTrue(error.message!!.contains("Unsupported board version"))
        assertEquals("original", config.readText())
        val manifest = SetManifest(config).apply { cards.add(Card(1, "../secret")) }
        assertThrows(IllegalArgumentException::class.java) { Set(manifest, root).writeConfig() }
        assertEquals("original", config.readText())
    }

    @Test
    fun archiveSkipsSymlinkFilesAndDirectoriesEvenWhenTargetIsInsideRoot() {
        val workspace = File(root, "links").apply { mkdirs() }
        val regular = File(workspace, "nested/regular.txt").apply { parentFile!!.mkdirs(); writeText("inside") }
        val outside = File(root, "outside.txt").apply { writeText("outside") }
        Files.createSymbolicLink(File(workspace, "outside-link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(workspace, "inside-link").toPath(), regular.toPath())
        Files.createSymbolicLink(File(workspace, "directory-link").toPath(), regular.parentFile!!.toPath())
        val output = File(root, "links.zip")
        Utils.zipFolder(workspace.absolutePath, output.absolutePath)
        JavaZipFile(output).use { archive ->
            assertEquals(listOf("nested/regular.txt"), archive.entries().asSequence().map { it.name }.toList())
        }
        assertFalse(File(workspace, ".metadata").exists())
    }

    @Test
    fun archiveErrorsPropagate() {
        assertThrows(IOException::class.java) {
            Utils.zipFolder(File(root, "missing").absolutePath, File(root, "missing.zip").absolutePath)
        }
        assertThrows(IOException::class.java) { Utils.zipFolder(root.absolutePath, root.absolutePath) }
    }
}
