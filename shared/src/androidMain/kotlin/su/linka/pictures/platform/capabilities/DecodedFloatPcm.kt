package su.linka.pictures.platform.capabilities

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A shared-memory view of this output window, not a copy or a scan of unrelated buffer contents. */
internal fun validateFloatPcm(buffer: ByteBuffer, offset: Int, size: Int, check: () -> Unit) {
    if (offset < 0 || size < 0 || size % 4 != 0 || offset.toLong() + size > buffer.limit()) MediaContainer.invalid()
    val view = buffer.duplicate().order(ByteOrder.nativeOrder())
    view.limit(offset + size)
    view.position(offset)
    var samples = 0
    while (view.hasRemaining()) {
        if ((samples++ and 0x3fff) == 0) check()
        val sample = view.float
        if (sample.isNaN() || sample.isInfinite()) MediaContainer.invalid()
    }
}
