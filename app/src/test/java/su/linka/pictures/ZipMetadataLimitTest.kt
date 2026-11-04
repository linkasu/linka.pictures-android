package su.linka.pictures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipMetadataLimitTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun rejectsExcessiveDeclaredEntriesBeforeZip4jBuildsTheHeaderList() {
        val archive = temporary.newFile().apply { writeBytes(endRecord(entries = 10_001)) }
        val error = assertThrows(IOException::class.java) {
            ArchiveReader().extract(archive, temporary.newFolder())
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("entry limit"))
    }

    @Test
    fun rejectsExcessiveDirectoryBytesBeforeParsingEntries() {
        val archive = temporary.newFile().apply { writeBytes(endRecord(size = 1024 * 1024 + 1)) }
        val error = assertThrows(IOException::class.java) {
            ArchiveReader().extract(archive, temporary.newFolder())
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("metadata limit"))
    }

    @Test
    fun rejectsEntryLengthsOutsideTheDeclaredDirectory() {
        val header = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, 0x02014b50).putShort(28, 60_000.toShort()).array()
        val archive = temporary.newFile().apply {
            writeBytes(header + endRecord(entries = 1, size = header.size))
        }
        val error = assertThrows(IOException::class.java) {
            ArchiveReader().extract(archive, temporary.newFolder())
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("metadata"))
    }

    @Test
    fun acceptsSmallZipAndZip64Directories() {
        val ordinary = ordinaryArchive()
        val trailing = temporary.newFile().apply { writeBytes(ordinary.readBytes() + ByteArray(7)) }
        for (archive in listOf(ordinary, trailing, zip64Archive(ordinary), zip64Archive(ordinary, sentinelFooter = false))) {
            val output = temporary.newFolder()
            ArchiveReader().extract(archive, output)
            assertEquals("{}", File(output, "config.json").readText())
        }
    }

    @Test
    fun validatesTheSameFooterAsZip4jWhenSignaturesAppearInComments() {
        val original = ordinaryArchive().readBytes()
        val comment = endRecord(entries = 10_001) + ByteArray(22)
        val realEnd = original.copyOfRange(original.size - 22, original.size)
        ByteBuffer.wrap(realEnd).order(ByteOrder.LITTLE_ENDIAN).putShort(20, comment.size.toShort())
        val archive = temporary.newFile().apply {
            writeBytes(original.copyOfRange(0, original.size - 22) + realEnd + comment)
        }
        val error = assertThrows(IOException::class.java) {
            ArchiveReader().extract(archive, temporary.newFolder())
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("entry limit"))
    }

    @Test
    fun validatesZip64EvenWhenClassicFieldsHaveNoSentinels() {
        val archive = zip64Archive(ordinaryArchive(), sentinelFooter = false, entries = 10_001)
        val error = assertThrows(IOException::class.java) {
            ArchiveReader().extract(archive, temporary.newFolder())
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("entry limit"))
    }

    @Test
    fun boundsZip64ExtensionAllocation() {
        val archive = zip64Archive(ordinaryArchive(), recordSize = Long.MAX_VALUE)
        val error = assertThrows(IOException::class.java) {
            ArchiveReader().extract(archive, temporary.newFolder())
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("metadata limit"))
    }

    @Test
    fun rejectsNumberedSplitsBeforeZip4jReadsUnvalidatedNeighbourMetadata() {
        val record = ByteBuffer.allocate(47).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0, 0x02014b50).putShort(28, 1).put(46, 'x'.code.toByte()).array()
        val archive = temporary.newFile("p.zip.001").apply {
            writeBytes(record + endRecord(entries = 1, size = record.size))
        }
        temporary.newFile("p.zip.002").outputStream().use { output ->
            repeat(30_000) { output.write(record) }
            output.write(endRecord(entries = 30_000, size = 30_000 * record.size, offset = 69))
        }
        val error = assertThrows(IOException::class.java) {
            ArchiveReader().extract(archive, temporary.newFolder())
        }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("Numbered split"))
    }

    private fun ordinaryArchive(): File = temporary.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("config.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
    }

    private fun zip64Archive(
        ordinary: File,
        sentinelFooter: Boolean = true,
        entries: Long = 1,
        recordSize: Long = 44
    ): File {
        val original = ordinary.readBytes()
        val end = ByteBuffer.wrap(original.copyOfRange(original.size - 22, original.size))
            .order(ByteOrder.LITTLE_ENDIAN)
        val endOffset = original.size - 22
        val zip64Record = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x06064b50).putLong(recordSize).putShort(45).putShort(45)
            .putInt(0).putInt(0).putLong(entries).putLong(entries)
            .putLong(end.getInt(12).toLong()).putLong(end.getInt(16).toLong()).array()
        val locator = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x07064b50).putInt(0).putLong(endOffset.toLong()).putInt(1).array()
        val zip64End = if (sentinelFooter) endRecord(entries = 0xffff, size = -1, offset = -1) else end.array()
        return temporary.newFile().apply {
            writeBytes(original.copyOfRange(0, endOffset) + zip64Record + locator + zip64End)
        }
    }

    private fun endRecord(entries: Int = 0, size: Int = 0, offset: Int = 0): ByteArray =
        ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x06054b50).putShort(0).putShort(0)
            .putShort(entries.toShort()).putShort(entries.toShort())
            .putInt(size).putInt(offset).putShort(0).array()
}
