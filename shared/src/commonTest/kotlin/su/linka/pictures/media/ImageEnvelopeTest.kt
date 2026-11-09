package su.linka.pictures.media

import kotlinx.coroutines.CancellationException
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.IOException
import su.linka.pictures.application.*
import su.linka.pictures.binary.Crc32
import kotlin.test.*

class ImageEnvelopeTest {
    private val signature = "89504e470d0a1a0a".decodeHex().toByteArray()
    private val ihdr = "00000001000000010806000000".decodeHex().toByteArray()

    private fun chunk(type: String, data: ByteArray = byteArrayOf()): ByteArray {
        val typeBytes = type.encodeToByteArray()
        val crc = Crc32().also { it.update(typeBytes); it.update(data) }
        return Buffer().writeInt(data.size).write(typeBytes).write(data).writeInt(crc.value.toInt()).readByteArray()
    }
    private fun png(vararg chunks: ByteArray) = Buffer().write(signature).apply { chunks.forEach { write(it) } }.readByteArray()
    private fun validPng(data: ByteArray = byteArrayOf(1)) = png(chunk("IHDR", ihdr), chunk("IDAT", data), chunk("IEND"))
    private fun read(bytes: ByteArray, offset: Long, buffer: ByteArray, count: Int) {
        assertTrue(offset >= 0 && offset <= bytes.size && count >= 0 && count <= buffer.size)
        assertTrue(count <= bytes.size - offset)
        bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + count)
    }
    private fun validate(bytes: ByteArray) = validateImageEnvelope(bytes.size.toLong(), { offset, buffer, count ->
        read(bytes, offset, buffer, count)
    }, {})
    private fun invalid(bytes: ByteArray) = assertProblem(ProblemCode.INVALID_DATA, validate(bytes))
    private fun assertProblem(code: ProblemCode, result: OperationResult<*>) = assertEquals(code, assertIs<OperationResult.Failed>(result).problem.code)

    @Test fun crcFixturesIncludeIndependentKnownChunkValues() {
        assertEquals("0000000049454e44ae426082".decodeHex().toByteArray().toList(), chunk("IEND").toList())
        assertEquals(0x1f15c489L, Buffer().write(chunk("IHDR", ihdr)).apply { skip(21) }.readInt().toLong() and 0xffffffffL)
        assertEquals(OperationResult.Completed(Unit), validate(validPng()))
    }

    @Test fun pngChecksAllPayloadBlocksWithOneReusedBoundedBuffer() {
        val bytes = validPng(ByteArray(2 * 8192 + 13) { it.toByte() })
        var reused: ByteArray? = null
        var reads = 0
        var checks = 0
        var maximum = 0
        val result = validateImageEnvelope(bytes.size.toLong(), { offset, buffer, count ->
            reused?.let { assertSame(it, buffer) }
            reused = buffer
            assertEquals(8192, buffer.size)
            maximum = maxOf(maximum, count)
            reads++
            read(bytes, offset, buffer, count)
        }, { checks++ })
        assertEquals(OperationResult.Completed(Unit), result)
        assertEquals(8192, maximum)
        assertTrue(checks >= 2 * reads)
    }

    @Test fun damagedTypePayloadAndStoredCrcAreRejected() {
        val bytes = png(chunk("IHDR", ihdr), chunk("tEXt", byteArrayOf(4, 5)), chunk("IDAT", byteArrayOf(1)), chunk("IEND"))
        for (index in listOf(16, 29, 40, 41)) {
            invalid(bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() })
        }
        val large = validPng(ByteArray(20000) { it.toByte() })
        invalid(large.copyOf().also { it[large.size - 20] = (it[large.size - 20].toInt() xor 1).toByte() })
    }

    @Test fun everyTruncatedPngPrefixAndTrailingByteIsRejected() {
        val bytes = validPng()
        for (size in bytes.indices) invalid(bytes.copyOf(size))
        invalid(bytes + byteArrayOf(0))
    }

    @Test fun firstIhdrLengthAndNonemptyIdatBeforeExactIendAreRequired() {
        invalid(png(chunk("IDAT", byteArrayOf(1)), chunk("IEND")))
        invalid(png(chunk("IHDR", ByteArray(12)), chunk("IDAT", byteArrayOf(1)), chunk("IEND")))
        invalid(png(chunk("IHDR", ihdr), chunk("IEND")))
        invalid(png(chunk("IHDR", ihdr), chunk("IDAT"), chunk("IEND")))
        invalid(png(chunk("IHDR", ihdr), chunk("IDAT", byteArrayOf(1))))
        invalid(png(chunk("IHDR", ihdr), chunk("IDAT", byteArrayOf(1)), chunk("IEND", byteArrayOf(1))))
    }

    @Test fun unsignedChunkLengthAndIncompleteExtentsNeverEscapeReadBounds() {
        for (size in listOf(0xffffffffL, 0x80000000L, 14L)) {
            val bytes = validPng().copyOf()
            Buffer().writeInt(size.toInt()).readByteArray().copyInto(bytes, 8)
            invalid(bytes)
        }
        invalid(signature + ByteArray(12))
    }

    @Test fun jpegUsesOnlyCheapPrefixAndEofReadsEvenAtByteLimit() {
        val size = 64L * 1024 * 1024
        val reads = mutableListOf<Pair<Long, Int>>()
        val result = validateImageEnvelope(size, { offset, buffer, count ->
            reads += offset to count
            when (offset) {
                0L -> { assertEquals(8, count); buffer.fill(0); buffer[0] = -1; buffer[1] = -40 }
                size - 2 -> { assertEquals(2, count); buffer[0] = -1; buffer[1] = -39 }
                else -> fail("JPEG body must not be scanned")
            }
        }, {})
        assertEquals(OperationResult.Completed(Unit), result)
        assertEquals(listOf(0L to 8, size - 2 to 2), reads)
    }

    @Test fun jpegRequiresExactEoiAtEof() {
        val bytes = ByteArray(12).also { it[0] = -1; it[1] = -40; it[10] = -1; it[11] = -39 }
        assertEquals(OperationResult.Completed(Unit), validate(bytes))
        invalid(bytes.copyOf().also { it[11] = 0 })
        invalid(bytes + byteArrayOf(0))
    }

    @Test fun gifVersionsAndAndroidGif8FamilyRequireEofTrailer() {
        for (version in listOf("GIF87a", "GIF89a", "GIF8zz")) {
            val bytes = ByteArray(12).also { version.encodeToByteArray().copyInto(it); it[11] = 0x3b }
            assertEquals(OperationResult.Completed(Unit), validate(bytes))
            invalid(bytes.copyOf().also { it[11] = 0 })
            invalid(bytes + byteArrayOf(0))
        }
    }

    @Test fun riffLengthIsUnsignedLittleEndianAndMatchesActualBytes() {
        val bytes = ByteArray(12).also { "RIFF".encodeToByteArray().copyInto(it); it[4] = 4 }
        assertEquals(OperationResult.Completed(Unit), validate(bytes))
        invalid(bytes.copyOf().also { it[4] = 5 })
        invalid(bytes.copyOf().also { for (i in 4..7) it[i] = -1 })
    }

    @Test fun unknownSignatureDoesNotClaimSupportOrScanTheWholeFile() {
        var reads = 0
        assertEquals(OperationResult.Completed(Unit), validateImageEnvelope(1024, { offset, buffer, count ->
            assertEquals(0L, offset); assertEquals(8, count); reads++
            "unknown!".encodeToByteArray().copyInto(buffer)
        }, {}))
        assertEquals(1, reads)
        // Dummy IDAT is deliberately not decoded here; a valid envelope is not compressed-stream proof.
        assertEquals(OperationResult.Completed(Unit), validate(validPng(byteArrayOf(0, 0, 0))))
    }

    @Test fun totalBoundsRejectBeforeReadingAndReadFailureIsIo() {
        for (size in listOf(Long.MIN_VALUE, -1L, 0L, 11L)) {
            assertProblem(ProblemCode.INVALID_DATA, validateImageEnvelope(size, { _, _, _ -> fail("No read") }, {}))
        }
        for (size in listOf(64L * 1024 * 1024 + 1, Long.MAX_VALUE)) {
            assertProblem(ProblemCode.LIMIT_EXCEEDED, validateImageEnvelope(size, { _, _, _ -> fail("No read") }, {}))
        }
        assertProblem(ProblemCode.IO_FAILURE, validateImageEnvelope(12, { _, _, _ -> throw IOException("Test read failure") }, {}))
    }

    @Test fun cancellationBetweenReadsIsNotConvertedToInvalidOrIo() {
        val bytes = validPng(ByteArray(20000))
        val primary = CancellationException("Test cancellation")
        var reads = 0
        assertSame(primary, assertFailsWith<CancellationException> {
            validateImageEnvelope(bytes.size.toLong(), { offset, buffer, count ->
                read(bytes, offset, buffer, count); reads++
            }, { if (reads == 6) throw primary })
        })
        assertEquals(6, reads)
    }

    @Test fun cancellationDuringFailedReadIsNotReclassifiedAsIo() {
        val primary = CancellationException("Test cancelled read")
        var cancelled = false
        assertSame(primary, assertFailsWith<CancellationException> {
            validateImageEnvelope(12, { _, _, _ ->
                cancelled = true
                throw IOException("Test read failure")
            }, { if (cancelled) throw primary })
        })
    }
}
