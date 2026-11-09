package su.linka.pictures.media

import kotlin.test.*
import kotlinx.coroutines.CancellationException
import su.linka.pictures.application.*

class GifThumbnailBoundsTest {
    private fun gif(frames: Int = 1): ByteArray {
        val header = "GIF89a".encodeToByteArray() + byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, -1, -1, -1)
        val frame = byteArrayOf(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0)
        return header + ByteArray(frames * frame.size) { frame[it % frame.size] } + byteArrayOf(0x3b)
    }
    private fun check(bytes: ByteArray, width: Int = 1, height: Int = 1) = validateGifThumbnail(bytes, width, height) {}
    private fun OperationResult<*>.problem(code: ProblemCode) = assertEquals(code, assertIs<OperationResult.Failed>(this).problem.code)

    @Test fun validFirstFrameAndAnimationLeaveSourceUnchanged() {
        for (frames in listOf(1, 30, 4096)) {
            val bytes = gif(frames)
            val original = bytes.copyOf()
            assertIs<OperationResult.Completed<*>>(check(bytes))
            assertContentEquals(original, bytes)
        }
        val legacy = gif().also { it[4] = '7'.code.toByte() }
        assertIs<OperationResult.Completed<*>>(check(legacy))
    }

    @Test fun outOfCanvasAndOversizedLogicalScreensFailBeforeNativeAllocation() {
        val outside = gif().also { it[20] = 1 }
        check(outside).problem(ProblemCode.INVALID_DATA)
        check(gif(), width = 2).problem(ProblemCode.INVALID_DATA)
        check(gif().also { it[6] = 0 }).problem(ProblemCode.INVALID_DATA)
        val huge = gif().also { it[6] = -1; it[7] = 0x7f }
        check(huge).problem(ProblemCode.LIMIT_EXCEEDED)
        check(gif(4097)).problem(ProblemCode.LIMIT_EXCEEDED)
    }

    @Test fun metadataBombIsBoundedEvenWithoutExtraFrames() {
        val bytes = gif()
        val comment = byteArrayOf(0x21, 0xfe.toByte(), 0)
        val bomb = bytes.copyOfRange(0, 19) + ByteArray(16_384 * 3) { comment[it % 3] } + bytes.copyOfRange(19, bytes.size)
        check(bomb).problem(ProblemCode.LIMIT_EXCEEDED)
    }

    @Test fun invalidCodeSizeTruncationTrailingBytesAndMissingFramesAreRejected() {
        val original = gif()
        check(original.copyOf().also { it[29] = 12 }).problem(ProblemCode.INVALID_DATA)
        for (count in listOf(0, 6, 13, 20, 31, original.size - 1)) check(original.copyOf(count)).problem(ProblemCode.INVALID_DATA)
        check(original + 0).problem(ProblemCode.INVALID_DATA)
        check(gif(0)).problem(ProblemCode.INVALID_DATA)
    }

    @Test fun cancellationIsNotMappedToMalformedData() {
        assertFailsWith<CancellationException> { validateGifThumbnail(gif(), 1, 1) { throw CancellationException("owned check") } }
    }

    @Test fun everySingleBitMutationIsBoundedAndDoesNotMutateItsInput() {
        val original = gif(2)
        for (index in original.indices) for (bit in 0..7) {
            val bytes = original.copyOf().also { it[index] = (it[index].toInt() xor (1 shl bit)).toByte() }
            val before = bytes.copyOf()
            val result = check(bytes)
            assertTrue(result is OperationResult.Completed || result is OperationResult.Failed)
            assertContentEquals(before, bytes)
        }
    }
}
