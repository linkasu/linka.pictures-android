package su.linka.pictures.platform.capabilities

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

internal object TinyMediaFixtures {
    fun wave(samples: Int = 1600): ByteArray {
        val data = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()); data.putInt(36 + samples * 2); data.put("WAVEfmt ".toByteArray())
        data.putInt(16); data.putShort(1); data.putShort(1); data.putInt(16000); data.putInt(32000); data.putShort(2); data.putShort(16)
        data.put("data".toByteArray()); data.putInt(samples * 2)
        repeat(samples) { data.putShort(if (it % 2 == 0) 100 else -100) }
        return data.array().also {
            check(data.position() == it.size && it.size == 44 + samples * 2)
            check(ByteBuffer.wrap(it, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int + 8 == it.size)
        }
    }
    fun png(width: Int = 1, height: Int = 1): ByteArray {
        require(width in 1..8193 && height in 1..8192)
        val output = ByteArrayOutputStream()
        val stream = DataOutputStream(output)
        stream.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(name: String, bytes: ByteArray) {
            val type = name.toByteArray(Charsets.US_ASCII)
            stream.writeInt(bytes.size); stream.write(type); stream.write(bytes)
            stream.writeInt(CRC32().also { it.update(type); it.update(bytes) }.value.toInt())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(6).put(0).put(0).put(0).array())
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { encoded -> val row = ByteArray(1 + width * 4); repeat(height) { encoded.write(row) } }
        chunk("IDAT", compressed.toByteArray()); chunk("IEND", byteArrayOf())
        return output.toByteArray().also { check(it.size > 60 && it.copyOfRange(it.size - 8, it.size - 4).contentEquals("IEND".toByteArray())) }
    }
}
