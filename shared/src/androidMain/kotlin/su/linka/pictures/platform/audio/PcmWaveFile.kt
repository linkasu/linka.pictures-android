package su.linka.pictures.platform.audio

import java.io.RandomAccessFile
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.ports.TemporaryFile

/** Streaming PCM16/mono/16kHz writer; only an owned empty temporary may be passed here. */
internal class PcmWaveFile(file: TemporaryFile) : AutoCloseable {
    private val output = RandomAccessFile(file.path, "rw")
    private val bytes = ByteArray(4096)
    var samples = 0L
        private set
    init {
        try {
            if (output.length() != 0L) throw NativeAudioFailure(ProblemCode.CONFLICT)
            output.write(ByteArray(44))
        } catch (failure: Exception) { try { output.close() } catch (_: Exception) { }; throw failure }
    }
    fun write(values: ShortArray, count: Int) {
        require(count in 1..values.size && count * 2 <= bytes.size)
        if (samples + count > 16_000L * 600 || 44 + (samples + count) * 2 > 64L * 1024 * 1024) throw NativeAudioFailure(ProblemCode.LIMIT_EXCEEDED)
        for (index in 0 until count) {
            bytes[index * 2] = values[index].toByte()
            bytes[index * 2 + 1] = (values[index].toInt() ushr 8).toByte()
        }
        output.write(bytes, 0, count * 2)
        samples += count
    }
    fun finish() {
        if (samples == 0L) throw NativeAudioFailure(ProblemCode.INVALID_DATA)
        output.seek(0)
        output.writeBytes("RIFF"); le32((36 + samples * 2).toInt()); output.writeBytes("WAVEfmt ")
        le32(16); le16(1); le16(1); le32(16000); le32(32000); le16(2); le16(16)
        output.writeBytes("data"); le32((samples * 2).toInt())
        output.fd.sync()
    }
    private fun le32(value: Int) { output.writeInt(Integer.reverseBytes(value)) }
    private fun le16(value: Int) { output.writeShort(java.lang.Short.reverseBytes(value.toShort()).toInt()) }
    override fun close() { output.close() }
}
