package su.linka.pictures

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImportDestinationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun rejectsDecodedPathSegmentsBeforeWritingOutsideDirectory() {
        val directory = temporaryFolder.newFolder("sets")
        val outside = temporaryFolder.newFile("outside.linka").apply { writeText("original") }
        val segments = listOf(
            "..%2Foutside.linka", "%2e%2e%2foutside.linka", "%2Foutside.linka",
            "..%5Coutside.linka", "%2E", "%2E%2E", "%00.linka"
        )

        for (segment in segments) {
            val uri = Uri.parse("content://external.provider/$segment")
            assertThrows(segment, IOException::class.java) {
                resolveImportedSetFile(directory, uri.lastPathSegment).writeText("overwrite")
            }
            assertEquals("original", outside.readText())
        }
        for (name in listOf(null, "")) {
            assertThrows(IOException::class.java) { resolveImportedSetFile(directory, name) }
        }
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test
    fun rejectsSymlinkToFileOutsideDirectory() {
        val directory = temporaryFolder.newFolder("sets")
        val outside = temporaryFolder.newFile("outside.linka").apply { writeText("original") }
        Files.createSymbolicLink(File(directory, "linked.linka").toPath(), outside.toPath())

        assertThrows(IOException::class.java) {
            resolveImportedSetFile(directory, "linked.linka").writeText("overwrite")
        }
        assertEquals("original", outside.readText())
    }

    @Test
    fun acceptsOrdinaryDecodedLinkaName() {
        val directory = temporaryFolder.newFolder("sets")
        val uri = Uri.parse("content://external.provider/My%20set.linka")

        val destination = resolveImportedSetFile(directory, uri.lastPathSegment)
        destination.writeText("archive")

        assertEquals(File(directory, "My set.linka").canonicalFile, destination)
        assertEquals("archive", destination.readText())
    }
}
