package su.linka.pictures.media

import kotlinx.coroutines.CancellationException
import okio.IOException
import su.linka.pictures.application.*
import su.linka.pictures.binary.Crc32

// Structural supplement only: unknown signatures still require native bounds and pixel decode.
internal fun validateImageEnvelope(
    byteCount: Long,
    readAt: (Long, ByteArray, Int) -> Unit,
    check: () -> Unit,
): OperationResult<Unit> = try {
    check()
    if (byteCount > 64L * 1024 * 1024) throw ImageEnvelopeFailure(ProblemCode.LIMIT_EXCEEDED)
    if (byteCount < 12) invalidEnvelope()
    val buffer = ByteArray(8192)
    fun read(offset: Long, count: Int) {
        check()
        readAt(offset, buffer, count)
        check()
    }
    fun unsigned32(offset: Int = 0): Long {
        var value = 0L
        for (index in offset until offset + 4) value = (value shl 8) or (buffer[index].toLong() and 255)
        return value
    }
    read(0, 8)
    when {
        unsigned32() == 0x89504e47L && unsigned32(4) == 0x0d0a1a0aL -> {
            var position = 8L
            var first = true
            var data = false
            var ended = false
            while (position < byteCount) {
                check()
                if (byteCount - position < 12) invalidEnvelope()
                read(position, 8)
                val size = unsigned32()
                val type = unsigned32(4)
                if (size > byteCount - position - 12) invalidEnvelope()
                if (first && (type != 0x49484452L || size != 13L)) invalidEnvelope()
                first = false
                val crc = Crc32().also { it.update(buffer, 4, 4) }
                position += 8
                var remaining = size
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size.toLong()).toInt()
                    read(position, count)
                    crc.update(buffer, 0, count)
                    position += count
                    remaining -= count
                }
                read(position, 4)
                if (crc.value != unsigned32()) invalidEnvelope()
                position += 4
                if (type == 0x49444154L && size > 0) data = true
                if (type == 0x49454e44L) {
                    if (size != 0L || !data || position != byteCount) invalidEnvelope()
                    ended = true
                    break
                }
            }
            if (!ended) invalidEnvelope()
        }
        buffer[0] == (-1).toByte() && buffer[1] == (-40).toByte() -> {
            read(byteCount - 2, 2)
            if (buffer[0] != (-1).toByte() || buffer[1] != (-39).toByte()) invalidEnvelope()
        }
        unsigned32() == 0x52494646L -> {
            val littleEndianSize = (buffer[4].toLong() and 255) or ((buffer[5].toLong() and 255) shl 8) or
                ((buffer[6].toLong() and 255) shl 16) or ((buffer[7].toLong() and 255) shl 24)
            if (littleEndianSize + 8 != byteCount) invalidEnvelope()
        }
        unsigned32() == 0x47494638L -> {
            // Match Android's GIF8 family check; version validity remains the native decoder's job.
            read(byteCount - 1, 1)
            if (buffer[0] != 0x3b.toByte()) invalidEnvelope()
        }
    }
    check()
    OperationResult.Completed(Unit)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: ImageEnvelopeFailure) {
    check()
    OperationResult.Failed(Problem(failure.code))
} catch (_: IOException) {
    check()
    OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
}

private class ImageEnvelopeFailure(val code: ProblemCode) : Exception()
private fun invalidEnvelope(): Nothing = throw ImageEnvelopeFailure(ProblemCode.INVALID_DATA)
