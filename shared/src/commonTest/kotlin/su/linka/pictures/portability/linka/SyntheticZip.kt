package su.linka.pictures.portability.linka

import okio.Buffer
import su.linka.pictures.portability.zip.Crc32

// Independent ZIP fixture builder, not the production writer; knobs intentionally produce invalid metadata.
internal data class SyntheticEntry(
    val name: String,
    val data: ByteArray,
    val compressed: ByteArray = data,
    val method: Int = 0,
    val flags: Int = 0x800,
    val size: Long = data.size.toLong(),
    val crc: Long = Crc32().also { it.update(data) }.value,
    val localSize: Long = size,
    val localName: String = name,
    val external: Int = 0,
    val madeBy: Int = 0x314,
    val extra: ByteArray = byteArrayOf(),
    val needed: Int = 20,
    val disk: Int = 0,
    val localOffsetDelta: Int = 0,
    val descriptor: Boolean = flags and 8 != 0,
)

internal fun syntheticZip(entries: List<SyntheticEntry>): ByteArray {
    val zip = Buffer()
    val offsets = mutableListOf<Long>()
    for (e in entries) {
        offsets.add(zip.size)
        val name = e.localName.encodeToByteArray()
        zip.writeIntLe(0x04034b50).writeShortLe(e.needed).writeShortLe(e.flags).writeShortLe(e.method).writeIntLe(0)
            .writeIntLe(if (e.descriptor) 0 else e.crc.toInt()).writeIntLe(if (e.descriptor) 0 else e.compressed.size)
            .writeIntLe(if (e.descriptor) 0 else e.localSize.toInt()).writeShortLe(name.size).writeShortLe(e.extra.size).write(name).write(e.extra).write(e.compressed)
        if (e.descriptor) zip.writeIntLe(0x08074b50).writeIntLe(e.crc.toInt()).writeIntLe(e.compressed.size).writeIntLe(e.size.toInt())
    }
    val start = zip.size
    for ((index, e) in entries.withIndex()) {
        val name = e.name.encodeToByteArray()
        zip.writeIntLe(0x02014b50).writeShortLe(e.madeBy).writeShortLe(e.needed).writeShortLe(e.flags).writeShortLe(e.method).writeIntLe(0)
            .writeIntLe(e.crc.toInt()).writeIntLe(e.compressed.size).writeIntLe(e.size.toInt()).writeShortLe(name.size).writeShortLe(e.extra.size)
            .writeShortLe(0).writeShortLe(e.disk).writeShortLe(0).writeIntLe(e.external).writeIntLe((offsets[index] + e.localOffsetDelta).toInt()).write(name).write(e.extra)
    }
    val centralSize = zip.size - start
    zip.writeIntLe(0x06054b50).writeShortLe(0).writeShortLe(0).writeShortLe(entries.size).writeShortLe(entries.size)
        .writeIntLe(centralSize.toInt()).writeIntLe(start.toInt()).writeShortLe(0)
    return zip.readByteArray()
}

internal fun configEntry(json: String) = SyntheticEntry("config.json", json.encodeToByteArray())
internal const val simpleConfig = """{"version":"3.0","withoutSpace":false,"pages":[{"id":"p","mode":"standard","rows":1,"columns":1,"cards":[{"id":"c","cardType":0,"title":"test"}]}]}"""
