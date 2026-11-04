package su.linka.pictures

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FileSaveFailureTest {
    private val folder = Files.createTempDirectory("file-save-failure").toFile()

    @After
    fun tearDown() {
        folder.deleteRecursively()
    }

    @Test
    fun copy_readFailure_preservesDestinationAndClosesInput() {
        val destination = File(folder, "existing").apply { writeText("original") }
        var closed = false
        val failure = IOException("read failed")
        val input = object : InputStream() {
            var reads = 0
            override fun read(): Int = error("Use bulk read")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (reads++ > 0) throw failure
                bytes[offset] = 42
                return 1
            }
            override fun close() { closed = true }
        }

        assertSame(failure, assertThrows(IOException::class.java) { Utils.copy(input, destination) })
        assertTrue(closed)
        assertEquals("original", destination.readText())
        assertEquals(listOf("existing"), folder.list()!!.toList())
    }

    @Test
    fun copy_closeFailure_doesNotPublishNewFile() {
        val destination = File(folder, "new")
        val input = object : java.io.ByteArrayInputStream(byteArrayOf(42)) {
            override fun close() { throw IOException("close failed") }
        }

        assertThrows(IOException::class.java) { Utils.copy(input, destination) }
        assertFalse(destination.exists())
        assertTrue(folder.list()!!.isEmpty())
    }

    @Test
    fun writeConfig_failure_throwsIOException() {
        val config = File(folder, "config.json").apply { mkdir() }
        val original = File(config, "keep").apply { writeText("original") }
        val set = Set(SetManifest(config), folder)

        assertThrows(IOException::class.java) { set.writeConfig() }
        assertEquals("original", original.readText())
    }

    @Test
    fun save_configFailure_callsOnFailAndPreservesArchiveReference() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = File(folder, "config.json").apply { mkdir() }
        File(config, "keep").writeText("original")
        val archive = File(folder, "original.linka").apply { writeText("archive") }
        val manifest = SetManifest(config, archiveFile = archive)
        var done = false
        var failure: Exception? = null

        SetsManager(context).save(Set(manifest, folder), "unused.linka", object : Callback<Any?>() {
            override fun onDone(result: Any?) { done = true }
            override fun onFail(error: Exception?) { failure = error }
        })

        assertFalse(done)
        assertTrue(failure is IOException)
        assertSame(archive, manifest.archiveFile)
        assertEquals("archive", archive.readText())
    }

    @Test
    @Config(shadows = [FailingBitmap::class])
    fun bitmap_compressFalse_throwsAndPreservesDestination() {
        val destination = File(folder, "image.png").apply { writeText("original") }
        val bitmap = Shadow.newInstanceOf(Bitmap::class.java)

        assertThrows(IOException::class.java) {
            Utils.saveBitmapToFile(folder, destination.name, bitmap, Bitmap.CompressFormat.PNG, 100)
        }

        assertEquals("original", destination.readText())
        assertEquals(listOf("image.png"), folder.list()!!.toList())
    }

    @Test
    fun bitmap_invalidDirectory_throwsWithoutChangingExistingData() {
        val notDirectory = File(folder, "file").apply { writeText("original") }
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        assertThrows(IOException::class.java) {
            Utils.saveBitmapToFile(notDirectory, "image.png", bitmap, Bitmap.CompressFormat.PNG, 100)
        }
        assertEquals("original", notDirectory.readText())
    }

    @Implements(Bitmap::class)
    class FailingBitmap {
        @Implementation
        fun compress(format: Bitmap.CompressFormat, quality: Int, stream: OutputStream): Boolean {
            stream.write(42)
            return false
        }
    }
}
