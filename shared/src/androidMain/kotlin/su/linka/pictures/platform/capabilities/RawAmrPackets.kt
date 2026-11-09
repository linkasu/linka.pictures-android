package su.linka.pictures.platform.capabilities

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import su.linka.pictures.application.ProblemCode

/** Borrows the outer reader. Codec packets retain the storage ToC byte and payload, not the magic. */
internal class RawAmrPackets(private val source: RandomAccessFile, private val check: () -> Unit) {
    private val packet = ByteArray(32)
    private var packets = 0
    var sampleTimeUs = 0L
        private set

    init {
        check()
        source.seek(0)
        for (byte in "#!AMR\n".toByteArray(Charsets.US_ASCII)) {
            if (source.read() != byte.toInt()) MediaContainer.invalid()
        }
    }

    fun read(buffer: ByteBuffer): Int {
        check()
        sampleTimeUs = packets.toLong() * 20_000
        if (source.filePointer == source.length()) return -1
        if (source.filePointer > source.length()) MediaContainer.invalid()
        if (packets >= MediaContainer.MAX_AMR_FRAMES) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
        val header = source.readUnsignedByte()
        val size = MediaContainer.amrFrameSize(header)
        if (source.filePointer + size - 1 > source.length() || buffer.remaining() < size) MediaContainer.invalid()
        packet[0] = header.toByte()
        source.readFully(packet, 1, size - 1)
        check()
        buffer.put(packet, 0, size)
        packets++
        return size
    }
}
