package su.linka.pictures.platform.capabilities

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.ProblemCode

class DecodedFloatPcmTest {
    private fun pcm(vararg values: Float): ByteBuffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.nativeOrder()).also { buffer ->
        values.forEach { buffer.putFloat(it) }
        buffer.flip()
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Invalid float PCM must fail") } catch (failure: InvalidMedia) { assertEquals(ProblemCode.INVALID_DATA, failure.code) }
    }
    @Test fun finiteNativeEndianSamplesIncludingSilenceAndExtremesAreValid() {
        val buffer = pcm(0f, -0f, Float.MIN_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, 1f, -1f)
        var checked = false
        validateFloatPcm(buffer, 0, buffer.limit()) { checked = true }
        assertTrue(checked)
        assertEquals(0, buffer.position())
    }
    @Test fun nanAndBothInfinitiesAreRejectedBeforeCountingFrames() {
        for (sample in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val buffer = pcm(0f, sample, 1f)
            invalid { validateFloatPcm(buffer, 0, buffer.limit()) { } }
            assertEquals(0, buffer.position())
        }
    }
    @Test fun onlyDeclaredWindowIsScannedAndOriginalPositionLimitAndByteOrderRemainUntouched() {
        val buffer = pcm(Float.NaN, 0f, 1f, Float.NEGATIVE_INFINITY)
        buffer.position(4); buffer.limit(12)
        buffer.order(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        val order = buffer.order()
        validateFloatPcm(buffer, 4, 8) { }
        assertEquals(4, buffer.position()); assertEquals(12, buffer.limit()); assertEquals(order, buffer.order())
        invalid { validateFloatPcm(buffer, 4, 12) { } }
        invalid { validateFloatPcm(buffer, -1, 4) { } }
        invalid { validateFloatPcm(buffer, 4, 3) { } }
    }
    @Test fun scanRetainsCancellationAndDeadlineCheckpointsWithoutWholeBufferCopy() {
        val buffer = pcm(0f, 1f)
        try {
            validateFloatPcm(buffer, 0, buffer.limit()) { throw CancellationException("test cancellation") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(0, buffer.position())
    }
}
