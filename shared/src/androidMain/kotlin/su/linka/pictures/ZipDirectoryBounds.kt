package su.linka.pictures

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Bound metadata before Zip4j allocates its complete header and extra-record lists. */
internal object ZipDirectoryBounds {
    fun validate(archive: File, limits: ArchiveLimits) {
        RandomAccessFile(archive, "r").use { input ->
            val length = input.length()
            if (length < 22) throw IOException("Invalid ZIP metadata")
            val tail = ByteArray(minOf(length, 65_558L).toInt())
            input.seek(length - tail.size)
            input.readFully(tail)
            var end = tail.size - 22
            // Match Zip4j's last-signature choice; never validate one footer and parse another.
            while (end >= 0 && u32(tail, end) != 0x06054b50L) end--
            if (end < 0) throw IOException("Missing ZIP metadata end record")
            if (u16(tail, end + 4) != 0 || u16(tail, end + 6) != 0) {
                throw IOException("Split ZIP archives are not supported")
            }
            var diskEntries = u16(tail, end + 8).toLong()
            var entries = u16(tail, end + 10).toLong()
            var size = u32(tail, end + 12)
            var offset = u32(tail, end + 16)
            var directoryEnd = length - tail.size + end
            val locatorOffset = directoryEnd - 20
            val locator = ByteArray(20)
            if (locatorOffset >= 0) {
                input.seek(locatorOffset)
                input.readFully(locator)
            }
            val hasZip64Locator = locatorOffset >= 0 && u32(locator, 0) == 0x07064b50L
            if (hasZip64Locator || entries == 0xffffL || diskEntries == 0xffffL ||
                size == 0xffffffffL || offset == 0xffffffffL) {
                if (!hasZip64Locator || u32(locator, 4) != 0L || u32(locator, 16) != 1L) {
                    throw IOException("Invalid ZIP64 metadata locator")
                }
                val recordOffset = u64(locator, 8)
                if (recordOffset < 0 || recordOffset > locatorOffset - 56) {
                    throw IOException("Invalid ZIP64 metadata offset")
                }
                val record = ByteArray(56)
                input.seek(recordOffset)
                input.readFully(record)
                val recordSize = u64(record, 4)
                if (u32(record, 0) != 0x06064b50L || recordSize < 44 ||
                    recordSize > limits.maxDirectoryBytes || recordSize > locatorOffset - recordOffset - 12) {
                    throw IOException("ZIP64 metadata limit exceeded")
                }
                if (u32(record, 16) != 0L || u32(record, 20) != 0L) {
                    throw IOException("Split ZIP64 archives are not supported")
                }
                diskEntries = u64(record, 24)
                entries = u64(record, 32)
                size = u64(record, 40)
                offset = u64(record, 48)
                directoryEnd = recordOffset
            }
            if (entries < 0 || diskEntries != entries) throw IOException("Invalid ZIP metadata entry count")
            if (entries > limits.maxEntries) throw IOException("ZIP metadata entry limit exceeded")
            if (size < 0 || size > limits.maxDirectoryBytes) throw IOException("ZIP metadata limit exceeded")
            if (offset < 0 || offset > directoryEnd - size || entries > size / 46) {
                throw IOException("Invalid ZIP metadata directory bounds")
            }

            // Check actual record lengths too: a forged directory size must not bypass the budget.
            var consumed = 0L
            val header = ByteArray(46)
            repeat(entries.toInt()) {
                if (size - consumed < header.size) throw IOException("Invalid ZIP metadata record length")
                input.seek(offset + consumed)
                input.readFully(header)
                val recordSize = 46L + u16(header, 28) + u16(header, 30) + u16(header, 32)
                if (u32(header, 0) != 0x02014b50L || recordSize > size - consumed) {
                    throw IOException("Invalid ZIP metadata record bounds")
                }
                consumed += recordSize
            }
        }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private fun u64(bytes: ByteArray, offset: Int): Long =
        u32(bytes, offset) or (u32(bytes, offset + 4) shl 32)
}
