package su.linka.pictures.portability.linka

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.*
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.ports.*

internal class Crc32 {
    private companion object {
        val table = IntArray(256) { byte ->
            var value = byte
            repeat(8) { value = (value ushr 1) xor (if (value and 1 != 0) 0xedb88320.toInt() else 0) }
            value
        }
    }
    private var crc = -1
    fun update(bytes: ByteArray) {
        for (b in bytes) crc = (crc ushr 8) xor table[(crc xor b.toInt()) and 255]
    }
    val value: Long get() = (crc xor -1).toLong() and 0xffffffffL
}

internal data class ZipItem(
    val name: String, val nameBytes: ByteArray, val flags: Int, val method: Int,
    val crc: Long, val compressed: Long, val size: Long, val offset: Long,
) { val directory: Boolean get() = name.endsWith('/') }

private fun BufferedSource.u16(): Int = readShortLe().toInt() and 65535
private fun BufferedSource.u32(): Long = readIntLe().toLong() and 0xffffffffL
private fun extra(bytes: ByteArray) {
    val source = Buffer().write(bytes)
    while (!source.exhausted()) {
        checkZip(source.size >= 4)
        val id = source.u16()
        val size = source.u16()
        checkZip(id != 1 && size.toLong() <= source.size)
        source.skip(size.toLong())
    }
}

internal class LinkaZip private constructor(
    private val files: PlatformFilesPort, private val file: LocalFile,
    val items: List<ZipItem>, private val centralOffset: Long,
) {
    companion object {
        suspend fun open(files: PlatformFilesPort, file: LocalFile): LinkaZip {
            val tail = Buffer()
            var total = 0L
            files.source(file).value().buffer().use { source ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(tail, 8192)
                    if (n == -1L) break
                    total += n
                    limit(total <= ARCHIVE_LIMIT)
                    if (tail.size > 65557) tail.skip(tail.size - 65557)
                }
            }
            val bytes = tail.readByteArray()
            var end = -1
            for (i in bytes.size - 22 downTo 0) {
                if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 5.toByte() && bytes[i + 3] == 6.toByte()) {
                    val comment = (bytes[i + 20].toInt() and 255) or ((bytes[i + 21].toInt() and 255) shl 8)
                    if (i + 22 + comment == bytes.size) { end = i; break }
                }
            }
            checkZip(end >= 0)
            val eocd = Buffer().write(bytes, end, bytes.size - end)
            eocd.skip(4)
            checkZip(eocd.u16() == 0 && eocd.u16() == 0)
            val diskCount = eocd.u16()
            val count = eocd.u16()
            checkZip(count != 65535 && count == diskCount)
            limit(count <= 2000)
            val centralSize = eocd.u32()
            val centralOffset = eocd.u32()
            checkZip(centralSize != 0xffffffffL && centralOffset != 0xffffffffL)
            checkZip(centralOffset + centralSize == total - bytes.size + end)
            val entries = mutableListOf<ZipItem>()
            val keys = mutableMapOf<String, Boolean>()
            var budget = 0L
            files.source(file).value().buffer().use { source ->
                source.skip(centralOffset)
                val central = LimitedSource(source, centralSize).buffer()
                repeat(count) {
                    currentCoroutineContext().ensureActive()
                    checkZip(central.u32() == 0x02014b50L)
                    central.u16()
                    checkZip(central.u16() <= 20)
                    val flags = central.u16()
                    val method = central.u16()
                    checkZip(method == 0 || method == 8)
                    checkZip(flags and (if (method == 8) 0x080e else 0x0808).inv() == 0)
                    central.skip(4)
                    val crc = central.u32()
                    val compressed = central.u32()
                    val size = central.u32()
                    checkZip(compressed != 0xffffffffL && size != 0xffffffffL)
                    val nameSize = central.u16()
                    val extraSize = central.u16()
                    val commentSize = central.u16()
                    checkZip(central.u16() == 0)
                    central.skip(2)
                    val external = central.u32()
                    val offset = central.u32()
                    checkZip(offset != 0xffffffffL && offset < centralOffset)
                    val nameBytes = central.readByteArray(nameSize.toLong())
                    checkZip(flags and 0x800 != 0 || nameBytes.all { it >= 0 })
                    val name = try { nameBytes.decodeToString(throwOnInvalidSequence = true) } catch (_: IllegalArgumentException) { fail(ProblemCode.UNSAFE_ARCHIVE) }
                    val key = safePath(name)
                    checkZip(key !in keys)
                    keys[key] = name.endsWith('/')
                    val type = (external ushr 16).toInt() and 0xf000
                    checkZip(type == 0 || type == if (name.endsWith('/')) 0x4000 else 0x8000)
                    checkZip(external and 8L == 0L)
                    checkZip(external and 0x10L == 0L || name.endsWith('/'))
                    extra(central.readByteArray(extraSize.toLong()))
                    central.skip(commentSize.toLong())
                    budget += maxOf(compressed, size)
                    limit(budget <= ARCHIVE_LIMIT)
                    limit(size <= if (name == "config.json") CONFIG_LIMIT else MEDIA_LIMIT)
                    if (method == 0) checkZip(size == compressed)
                    if (name.endsWith('/')) checkZip(size == 0L)
                    entries.add(ZipItem(name, nameBytes, flags, method, crc, compressed, size, offset))
                }
                checkZip(central.exhausted())
            }
            for (key in keys.keys) {
                var slash = key.indexOf('/')
                while (slash >= 0) {
                    checkZip(keys[key.substring(0, slash)] != false)
                    slash = key.indexOf('/', slash + 1)
                }
            }
            checkZip(entries.any { it.name == "config.json" && !it.directory })
            return LinkaZip(files, file, entries.sortedBy { it.offset }, centralOffset)
        }
    }

    // No filesystem extraction: each stream is consumed under both declared and actual byte limits.
    suspend fun visit(consume: suspend (ZipItem, Source) -> Unit) {
        var actualTotal = 0L
        var offset = 0L
        files.source(file).value().buffer().use { source ->
            for (entry in items) {
                currentCoroutineContext().ensureActive()
                checkZip(offset == entry.offset)
                checkZip(source.u32() == 0x04034b50L)
                checkZip(source.u16() <= 20)
                checkZip(source.u16() == entry.flags && source.u16() == entry.method)
                source.skip(4)
                val crc = source.u32()
                val compressed = source.u32()
                val size = source.u32()
                val nameSize = source.u16()
                val extraSize = source.u16()
                val descriptor = entry.flags and 8 != 0
                checkZip((crc == entry.crc || descriptor && crc == 0L) &&
                    (compressed == entry.compressed || descriptor && compressed == 0L) &&
                    (size == entry.size || descriptor && size == 0L))
                checkZip(source.readByteArray(nameSize.toLong()).contentEquals(entry.nameBytes))
                extra(source.readByteArray(extraSize.toLong()))
                offset += 30L + nameSize + extraSize
                checkZip(offset + entry.compressed <= centralOffset)
                val raw = LimitedSource(source, entry.compressed)
                val inflated: Source = if (entry.method == 8) raw.inflate(Inflater(true)) else raw
                var actual = 0L
                val checksum = Crc32()
                val checked = object : Source {
                    override fun timeout() = source.timeout()
                    override fun close() = Unit
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        require(byteCount >= 0)
                        if (byteCount == 0L) return 0
                        val chunk = Buffer()
                        val n = inflated.read(chunk, minOf(byteCount, 8192))
                        if (n == -1L) return -1
                        actual += n
                        actualTotal += n
                        limit(actual <= (if (entry.name == "config.json") CONFIG_LIMIT else MEDIA_LIMIT) && actualTotal <= ARCHIVE_LIMIT)
                        checkZip(actual <= entry.size)
                        val bytes = chunk.readByteArray()
                        checksum.update(bytes)
                        sink.write(bytes)
                        return n
                    }
                }
                try {
                    consume(entry, checked)
                    val remaining = Buffer()
                    while (checked.read(remaining, 8192) != -1L) { currentCoroutineContext().ensureActive(); remaining.clear() }
                    checkZip(actual == entry.size && checksum.value == entry.crc && raw.remaining == 0L)
                } finally { inflated.close() }
                offset += entry.compressed
                if (descriptor) {
                    val first = source.u32()
                    val descriptorCrc = if (first == 0x08074b50L) { offset += 4; source.u32() } else first
                    checkZip(descriptorCrc == entry.crc && source.u32() == entry.compressed && source.u32() == entry.size)
                    offset += 12
                }
            }
            checkZip(offset == centralOffset)
        }
    }
}

private class LimitedSource(private val source: Source, var remaining: Long) : Source {
    override fun timeout() = source.timeout()
    override fun close() = Unit
    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0)
        if (byteCount == 0L) return 0
        if (remaining == 0L) return -1
        val n = source.read(sink, minOf(byteCount, remaining))
        checkZip(n != -1L)
        remaining -= n
        return n
    }
}

internal class LinkaZipWriter(private val sink: BufferedSink) {
    private data class Written(val name: ByteArray, val crc: Long, val size: Long, val offset: Long)
    private val entries = mutableListOf<Written>()
    private var offset = 0L
    private var payload = 0L

    suspend fun add(name: String, source: Source, expectedSize: Long? = null, expectedHash: String? = null) {
        source.use {
            safePath(name)
            limit(entries.size < 2000)
            val bytes = name.encodeToByteArray()
            limit(bytes.size <= 65535)
            val start = offset
            sink.writeIntLe(0x04034b50).writeShortLe(20).writeShortLe(0x808).writeShortLe(0)
                .writeIntLe(0).writeIntLe(0).writeIntLe(0).writeIntLe(0).writeShortLe(bytes.size).writeShortLe(0).write(bytes)
            offset += 30 + bytes.size
            val crc = Crc32()
            var count = 0L
            val hash = HashingSource.sha256(source)
            val buffer = Buffer()
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = hash.read(buffer, 8192)
                if (n == -1L) break
                count += n
                payload += n
                limit(count <= (if (name == "config.json") CONFIG_LIMIT else MEDIA_LIMIT) && payload <= ARCHIVE_LIMIT)
                if (expectedSize != null && count > expectedSize) fail(ProblemCode.INTEGRITY_FAILURE)
                val chunk = buffer.readByteArray()
                crc.update(chunk)
                sink.write(chunk)
            }
            if (expectedSize != null && count != expectedSize || expectedHash != null && hash.hash.hex() != expectedHash) fail(ProblemCode.INTEGRITY_FAILURE)
            sink.writeIntLe(0x08074b50).writeIntLe(crc.value.toInt()).writeIntLe(count.toInt()).writeIntLe(count.toInt())
            offset += count + 16
            limit(offset <= ARCHIVE_LIMIT)
            entries.add(Written(bytes, crc.value, count, start))
        }
    }

    fun finish() {
        val start = offset
        for (entry in entries) {
            sink.writeIntLe(0x02014b50).writeShortLe(20).writeShortLe(20).writeShortLe(0x808).writeShortLe(0)
                .writeIntLe(0).writeIntLe(entry.crc.toInt()).writeIntLe(entry.size.toInt()).writeIntLe(entry.size.toInt())
                .writeShortLe(entry.name.size).writeShortLe(0).writeShortLe(0).writeShortLe(0).writeShortLe(0)
                .writeIntLe(0).writeIntLe(entry.offset.toInt()).write(entry.name)
            offset += 46 + entry.name.size
        }
        sink.writeIntLe(0x06054b50).writeShortLe(0).writeShortLe(0).writeShortLe(entries.size).writeShortLe(entries.size)
            .writeIntLe((offset - start).toInt()).writeIntLe(start.toInt()).writeShortLe(0)
        limit(offset + 22 <= ARCHIVE_LIMIT)
    }
}
