package su.linka.pictures

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchiveReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun boundedImportPreservesExistingDestinationAndRemovesStagingOnFailure() {
        val directory = temporary.newFolder()
        val destination = File(directory, "existing.linka").apply { writeText("original") }
        val input = object : ByteArrayInputStream("123456".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 2))
        }
        assertThrows(IOException::class.java) { Utils.copy(BoundedStreams.limit(input, 4), destination) }
        assertEquals("original", destination.readText())
        assertEquals(listOf("existing.linka"), directory.list()!!.toList())
        Utils.copy(BoundedStreams.limit(ByteArrayInputStream("1234".toByteArray()), 4), destination)
        assertEquals("1234", destination.readText())

        val single = BoundedStreams.limit(ByteArrayInputStream(byteArrayOf(1, 2)), 1)
        assertEquals(1, single.read())
        assertThrows(IOException::class.java) { single.read() }
    }

    private fun archive(vararg entries: Pair<String, String>): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    @Test fun extractsNestedMediaAndConfig() {
        val output = temporary.newFolder()
        ArchiveReader().extract(archive("config.json" to "{}", "media/nested/a" to "abc"), output)
        assertEquals("abc", File(output, "media/nested/a").readText())
    }

    @Test fun rejectsMetadataLimitsBeforeOverwriting() {
        val output = temporary.newFolder()
        val config = File(output, "config.json").apply { writeText("original") }
        val zip = archive("config.json" to "{}", "image" to "1234")
        val limits = listOf(
            ArchiveLimits(maxEntries = 1), ArchiveLimits(maxConfigBytes = 1),
            ArchiveLimits(maxMediaBytes = 3), ArchiveLimits(maxTotalBytes = 5),
            ArchiveLimits(maxInputBytes = 0)
        )
        for (limit in limits) {
            assertThrows(IOException::class.java) { ArchiveReader(limit).extract(zip, output) }
            assertEquals("original", config.readText())
        }
    }

    @Test fun rejectsTraversalDuplicatesAndConflictingPathsBeforeWriting() {
        val output = temporary.newFolder()
        for (path in listOf("../escape", "/absolute", "a/../b", "a\\b", "a//b")) {
            assertThrows(IOException::class.java) {
                ArchiveReader().extract(archive("config.json" to "{}", path to "bad"), output)
            }
            assertFalse(File(output, "config.json").exists())
        }
        assertThrows(IOException::class.java) {
            ArchiveReader().extract(archive("a" to "file", "a/b" to "nested"), output)
        }
        // ZipOutputStream refuses duplicates; replace an equal-length central/local name instead.
        val duplicate = archive("a" to "one", "b" to "two")
        val bytes = duplicate.readBytes()
        for (i in 0 until bytes.size - 46) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte()) bytes[i + 46] = 'a'.code.toByte()
        }
        duplicate.writeBytes(bytes)
        assertThrows(IOException::class.java) { ArchiveReader().extract(duplicate, output) }
    }

    @Test fun rejectsArchiveAndFilesystemSymlinks() {
        val output = temporary.newFolder()
        val zip = archive("link" to "outside")
        val bytes = zip.readBytes()
        for (i in 0 until bytes.size - 46) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte()) {
                bytes[i + 40] = 0xff.toByte()
                bytes[i + 41] = 0xa1.toByte()
            }
        }
        zip.writeBytes(bytes)
        assertThrows(IOException::class.java) { ArchiveReader().extract(zip, output) }
        val outside = temporary.newFolder()
        Files.createSymbolicLink(File(output, "media").toPath(), outside.toPath())
        assertThrows(IOException::class.java) {
            ArchiveReader().extract(archive("media/image" to "bad"), output)
        }
        assertFalse(File(outside, "image").exists())
    }

    @Test fun actualBytesAreLimitedEvenWhenMetadataLies() {
        val zip = archive("config.json" to "1234567890")
        val bytes = zip.readBytes()
        for (i in 0 until bytes.size - 46) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte()) {
                for (offset in 24..27) bytes[i + offset] = 0
            }
        }
        zip.writeBytes(bytes)
        val output = temporary.newFolder()
        assertThrows(IOException::class.java) {
            ArchiveReader(ArchiveLimits(maxConfigBytes = 3)).extract(zip, output, "config.json")
        }
        assertTrue(File(output, "config.json").length() <= 3)
        val streamOutput = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            BoundedStreams.copy(ByteArrayInputStream(byteArrayOf(1)), streamOutput, 0)
        }
        assertEquals(0, streamOutput.size())
    }
}
