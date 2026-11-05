package su.linka.pictures.platform.capabilities

import java.io.RandomAccessFile
import java.util.zip.CRC32
import su.linka.pictures.application.ProblemCode

internal class InvalidMedia(val code: ProblemCode) : Exception()

/** Bounded structural checks supplement decoders which may silently accept a truncated prefix. */
internal object MediaContainer {
    const val MAX_BYTES = 64L * 1024 * 1024
    const val MAX_AMR_FRAMES = 30_000
    private val amrFrameSizes = intArrayOf(13, 14, 16, 18, 20, 21, 27, 32, 6)
    fun invalid(): Nothing = throw InvalidMedia(ProblemCode.INVALID_DATA)
    fun unsupported(): Nothing = throw InvalidMedia(ProblemCode.UNSUPPORTED_MEDIA)
    private fun RandomAccessFile.ascii(count: Int) = ByteArray(count).also(::readFully).toString(Charsets.US_ASCII)
    private fun RandomAccessFile.le32(): Long = Integer.reverseBytes(readInt()).toLong() and 0xffffffffL

    fun audio(input: RandomAccessFile, check: () -> Unit): String {
        if (input.length() < 12) invalid()
        val prefix = input.ascii(12)
        input.seek(0)
        return when {
            prefix.startsWith("RIFF") && prefix.substring(8) == "WAVE" -> { wave(input, check); "audio/wav" }
            prefix.startsWith("OggS") -> { vorbis(input, check); "audio/ogg" }
            prefix.startsWith("#!AMR\n") -> { amr(input, check); "audio/amr" }
            prefix.startsWith("#!AMR-WB") -> unsupported()
            prefix.substring(4, 8) == "ftyp" -> { threeGp(input, check); "audio/3gpp" }
            prefix.startsWith("ID3") -> { mp3(input, check); "audio/mpeg" }
            else -> {
                input.seek(0)
                if (input.readUnsignedByte() == 255 && input.readUnsignedByte() and 0xe0 == 0xe0) {
                    input.seek(0); mp3(input, check); "audio/mpeg"
                } else unsupported()
            }
        }
    }

    private fun wave(input: RandomAccessFile, check: () -> Unit) {
        input.seek(4)
        if (input.le32() + 8 != input.length()) invalid()
        input.seek(12)
        var alignment = 0
        var encoding = 0
        var sampleRate = 0L
        var data = false
        while (input.filePointer < input.length()) {
            check()
            if (input.length() - input.filePointer < 8) invalid()
            val type = input.ascii(4)
            val size = input.le32()
            val start = input.filePointer
            val end = start + size + size % 2
            if (end > input.length()) invalid()
            if (type == "fmt ") {
                if (size < 16 || alignment != 0) invalid()
                encoding = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
                val channels = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
                val rate = input.le32()
                sampleRate = rate
                val byteRate = input.le32()
                alignment = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
                val bits = java.lang.Short.reverseBytes(input.readShort()).toInt() and 65535
                if (channels == 0 || rate == 0L || alignment == 0) invalid()
                if ((encoding == 1 || encoding == 3) && (bits == 0 || bits % 8 != 0 || channels.toLong() * bits / 8 != alignment.toLong() || rate * alignment != byteRate)) invalid()
            }
            if (type == "data") {
                if (data) unsupported()
                if (alignment == 0 || size == 0L || size % alignment != 0L) invalid()
                if ((encoding == 1 || encoding == 3) && size / alignment > sampleRate * 600) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
                data = true
            }
            input.seek(end)
        }
        if (!data) invalid()
    }

    private fun vorbis(input: RandomAccessFile, check: () -> Unit) {
        var serial: Long? = null
        var sequence = 0L
        var lastEos = false
        while (input.filePointer < input.length()) {
            check()
            val start = input.filePointer
            if (input.length() - start < 27 || input.ascii(4) != "OggS" || input.readUnsignedByte() != 0) invalid()
            val flags = input.readUnsignedByte()
            input.skipBytes(8)
            val currentSerial = input.le32()
            val currentSequence = input.le32()
            val checksum = input.le32()
            val count = input.readUnsignedByte()
            val lacing = ByteArray(count).also(input::readFully)
            val bytes = lacing.sumOf { it.toInt() and 255 }
            if (start + 27 + count + bytes > input.length()) invalid()
            if (serial == null) {
                serial = currentSerial
                if (flags and 2 == 0 || currentSequence != 0L || bytes < 30) invalid()
                val payload = input.filePointer
                if (input.readUnsignedByte() != 1 || input.ascii(6) != "vorbis") unsupported()
                input.seek(payload)
            } else if (serial != currentSerial) unsupported() // No partial decode of chained/multiplexed logical streams.
            if (currentSequence != sequence++ || lastEos) invalid()
            lastEos = flags and 4 != 0
            input.seek(start)
            val page = ByteArray(27 + count + bytes).also(input::readFully)
            for (index in 22..25) page[index] = 0
            var crc = 0
            for (byte in page) {
                crc = crc xor ((byte.toInt() and 255) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
            }
            if ((crc.toLong() and 0xffffffffL) != checksum) invalid()
        }
        if (!lastEos) invalid()
    }

    fun amrFrameSize(header: Int): Int {
        if (header !in 0..255 || (header and 0x83) != 0) invalid()
        val kind = (header ushr 3) and 15
        return if (kind == 15) 1 else amrFrameSizes.getOrNull(kind) ?: invalid()
    }

    private fun amr(input: RandomAccessFile, check: () -> Unit) {
        input.seek(6)
        var frames = 0
        while (input.filePointer < input.length()) {
            check()
            val header = input.readUnsignedByte()
            val size = amrFrameSize(header)
            if (input.filePointer + size - 1 > input.length()) invalid()
            input.skipBytes(size - 1)
            if (++frames > MAX_AMR_FRAMES) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
        }
        if (frames == 0) invalid()
    }

    private fun threeGp(input: RandomAccessFile, check: () -> Unit) {
        ThreeGpAtoms(input, check).validate()
    }

    private fun mp3(input: RandomAccessFile, check: () -> Unit) {
        input.seek(0)
        if (input.ascii(3) == "ID3") {
            val version = input.readUnsignedByte()
            input.skipBytes(1)
            val flags = input.readUnsignedByte()
            if (version !in 2..4) unsupported()
            var size = 0L
            repeat(4) { val next = input.readUnsignedByte(); if (next > 127) invalid(); size = (size shl 7) or next.toLong() }
            val end = 10 + size + if (version == 4 && flags and 16 != 0) 10 else 0
            if (end > input.length()) invalid()
            input.seek(end)
        } else input.seek(0)
        var frames = 0
        val rates = intArrayOf(44100, 48000, 32000)
        val mpeg1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        val mpeg2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        while (input.filePointer < input.length()) {
            check()
            val start = input.filePointer
            if (input.length() - start == 128L && input.ascii(3) == "TAG") { input.seek(input.length()); break }
            input.seek(start)
            if (input.length() - start < 4) invalid()
            val header = input.readInt()
            val version = (header ushr 19) and 3
            val layer = (header ushr 17) and 3
            val bitrate = (header ushr 12) and 15
            val rateIndex = (header ushr 10) and 3
            if (header ushr 21 != 2047 || version == 1 || layer != 1 || bitrate == 15 || rateIndex == 3) invalid()
            if (bitrate == 0) unsupported()
            val rate = rates[rateIndex] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
            val kbps = (if (version == 3) mpeg1 else mpeg2)[bitrate]
            val size = (if (version == 3) 144000 else 72000) * kbps / rate + ((header ushr 9) and 1)
            if (size < 4 || start + size > input.length()) invalid()
            input.seek(start + size)
            frames++
        }
        if (frames == 0) invalid()
    }

    fun image(input: RandomAccessFile, check: () -> Unit) {
        if (input.length() < 12) invalid()
        val signature = ByteArray(8).also(input::readFully)
        if (signature.contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) {
            var first = true
            var data = false
            val buffer = ByteArray(8192)
            while (input.filePointer < input.length()) {
                check()
                if (input.length() - input.filePointer < 12) invalid()
                val size = input.readInt().toLong() and 0xffffffffL
                if (size > input.length() - input.filePointer - 8) invalid()
                val type = ByteArray(4).also(input::readFully)
                val name = type.toString(Charsets.US_ASCII)
                if (first && (name != "IHDR" || size != 13L)) invalid()
                first = false
                val crc = CRC32().also { it.update(type) }
                var remaining = size
                while (remaining > 0) {
                    check()
                    val count = minOf(remaining, buffer.size.toLong()).toInt()
                    input.readFully(buffer, 0, count); crc.update(buffer, 0, count); remaining -= count
                }
                if (crc.value != (input.readInt().toLong() and 0xffffffffL)) invalid()
                if (name == "IDAT" && size > 0) data = true
                if (name == "IEND") {
                    if (size != 0L || !data || input.filePointer != input.length()) invalid()
                    return
                }
            }
            invalid()
        }
        // Other platform-supported image formats still undergo bounds + real bounded decode.
        if (signature[0] == (-1).toByte() && signature[1] == (-40).toByte()) {
            input.seek(input.length() - 2)
            if (input.readUnsignedShort() != 0xffd9) invalid()
        } else if (signature.toString(Charsets.US_ASCII).startsWith("RIFF")) {
            input.seek(4); if (input.le32() + 8 != input.length()) invalid()
        } else if (signature.toString(Charsets.US_ASCII).startsWith("GIF8")) {
            input.seek(input.length() - 1); if (input.readUnsignedByte() != 59) invalid()
        }
    }
}
