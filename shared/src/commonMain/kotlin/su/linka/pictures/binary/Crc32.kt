package su.linka.pictures.binary

internal class Crc32 {
    private companion object {
        val table = IntArray(256) { byte ->
            var value = byte
            repeat(8) { value = (value ushr 1) xor (if (value and 1 != 0) 0xedb88320.toInt() else 0) }
            value
        }
    }
    private var crc = -1
    fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size - offset) {
        require(offset >= 0 && offset <= bytes.size && count >= 0 && count <= bytes.size - offset)
        for (index in offset until offset + count) {
            crc = (crc ushr 8) xor table[(crc xor bytes[index].toInt()) and 255]
        }
    }
    val value: Long get() = (crc xor -1).toLong() and 0xffffffffL
}
