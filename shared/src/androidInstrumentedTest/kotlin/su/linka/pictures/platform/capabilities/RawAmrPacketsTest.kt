package su.linka.pictures.platform.capabilities

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.platform.audio.OwnedAudioTestFiles

class RawAmrPacketsTest {
    private val magic = "#!AMR\n".toByteArray(Charsets.US_ASCII)
    private fun source(bytes: ByteArray, block: (RandomAccessFile) -> Unit) {
        OwnedAudioTestFiles().use { files ->
            val file = files.file(bytes)
            RandomAccessFile(file.path, "r").use(block)
            assertEquals(LegacyCodecFixtures.sha256(bytes), LegacyCodecFixtures.sha256(java.io.File(file.path).readBytes()))
        }
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Malformed AMR must fail") } catch (failure: InvalidMedia) { assertEquals(ProblemCode.INVALID_DATA, failure.code) }
    }

    @Test fun pinnedRawAmrPacketsPreserveExactBytesTimestampsEofAndBorrowedReader() {
        val bytes = LegacyCodecFixtures.amr()
        assertEquals(LegacyCodecFixtures.AMR_SHA256, LegacyCodecFixtures.sha256(bytes))
        source(bytes) { input ->
            assertEquals("audio/amr", MediaContainer.audio(input) { })
            val reader = RawAmrPackets(input) { }
            val buffer = ByteBuffer.allocate(32)
            val reconstructed = ByteArrayOutputStream()
            var index = 0
            while (true) {
                buffer.clear()
                val size = reader.read(buffer)
                if (size == -1) break
                assertEquals(index.toLong() * 20_000, reader.sampleTimeUs)
                assertEquals(32, size)
                reconstructed.write(buffer.array(), 0, size)
                index++
            }
            assertEquals(11, index)
            assertArrayEquals(bytes.copyOfRange(6, bytes.size), reconstructed.toByteArray())
            assertEquals(220_000L, reader.sampleTimeUs)
            assertEquals(-1, reader.read(buffer))
            assertEquals(0, buffer.position())
            assertEquals(bytes.size.toLong(), input.filePointer)
            assertTrue(input.fd.valid())
            input.seek(0); assertEquals('#'.code, input.read())
        }
    }

    @Test fun allNbModesSidNoDataAndQualityBitKeepTheirOriginalTocAndPayload() {
        val modes = (0..8).toList() + 15
        val sizes = listOf(13, 14, 16, 18, 20, 21, 27, 32, 6, 1) // Independent AMR-NB storage specification assertions.
        val packets = modes.zip(sizes).map { (mode, size) ->
            ByteArray(size) { index -> if (index == 0) ((mode shl 3) or 4).toByte() else (mode + index).toByte() }
        } + byteArrayOf(0, *ByteArray(12) { it.toByte() }) // Q=0 is preserved, not upgraded or replaced by silence.
        val bytes = magic + packets.fold(byteArrayOf()) { combined, packet -> combined + packet }
        source(bytes) { input ->
            assertEquals("audio/amr", MediaContainer.audio(input) { })
            val reader = RawAmrPackets(input) { }
            for ((index, expected) in packets.withIndex()) {
                val buffer = ByteBuffer.wrap(ByteArray(40) { 0x55.toByte() })
                buffer.position(3); buffer.limit(35)
                assertEquals(expected.size, MediaContainer.amrFrameSize(expected[0].toInt() and 255))
                assertEquals(expected.size, reader.read(buffer))
                assertEquals(index.toLong() * 20_000, reader.sampleTimeUs)
                assertEquals(3 + expected.size, buffer.position())
                assertArrayEquals(expected, buffer.array().copyOfRange(3, 3 + expected.size))
                assertArrayEquals(ByteArray(3) { 0x55.toByte() }, buffer.array().copyOfRange(0, 3))
            }
            assertEquals(-1, reader.read(ByteBuffer.allocate(32)))
        }
    }

    @Test fun wrongMagicReservedModesPaddingAndTruncatedPayloadFailWithoutPartialWrite() {
        for (bytes in listOf("#!AMR-WB\n".toByteArray(), magic.copyOf(4))) {
            source(bytes) { input -> invalid { RawAmrPackets(input) { } } }
        }
        val headers = (9..14).map { (it shl 3) or 4 } + listOf(0x05, 0x06, 0x84)
        for (header in headers) {
            invalid { MediaContainer.amrFrameSize(header) }
            source(magic + header.toByte() + ByteArray(32)) { input ->
                invalid { MediaContainer.audio(input) { } }
                val reader = RawAmrPackets(input) { }
                val buffer = ByteBuffer.allocate(32)
                invalid { reader.read(buffer) }
                assertEquals(0, buffer.position())
            }
        }
        source(magic + byteArrayOf(0x3c, 1, 2)) { input ->
            val reader = RawAmrPackets(input) { }
            val buffer = ByteBuffer.allocate(32)
            invalid { reader.read(buffer) }
            assertEquals(0, buffer.position())
        }
    }

    @Test fun insufficientCodecBufferAndEmptyStreamDoNotInventOrCutPackets() {
        source(magic + byteArrayOf(0x3c) + ByteArray(31)) { input ->
            val reader = RawAmrPackets(input) { }
            val buffer = ByteBuffer.allocate(31)
            invalid { reader.read(buffer) }
            assertEquals(0, buffer.position())
        }
        source(magic) { input ->
            val reader = RawAmrPackets(input) { }
            assertEquals(-1, reader.read(ByteBuffer.allocate(32)))
            assertEquals(0L, reader.sampleTimeUs)
        }
    }

    @Test fun cancellationChecksBeforeAndAfterReadPropagateWithoutClosingSourceOrPublishingPacket() {
        for (cancelAt in listOf(2, 3)) {
            source(magic + byteArrayOf(0x3c) + ByteArray(31)) { input ->
                var checks = 0
                val reader = RawAmrPackets(input) { if (++checks == cancelAt) throw CancellationException("test cancellation") }
                val buffer = ByteBuffer.allocate(32)
                try { reader.read(buffer); fail("Cancellation must propagate") } catch (_: CancellationException) { }
                assertEquals(0, buffer.position())
                assertTrue(input.fd.valid())
                if (cancelAt == 2) assertEquals(6L, input.filePointer)
                else assertEquals(38L, input.filePointer)
            }
        }
    }

    @Test fun thirtyThousandFramesAreAllowedButNextPacketFailsWithoutBeingConsumed() {
        for (extra in listOf(0, 1)) {
            source(magic + ByteArray(30_000 + extra) { 0x7c.toByte() }) { input ->
                val reader = RawAmrPackets(input) { }
                val buffer = ByteBuffer.allocate(1)
                repeat(30_000) { buffer.clear(); assertEquals(1, reader.read(buffer)) }
                buffer.clear()
                if (extra == 0) assertEquals(-1, reader.read(buffer))
                else {
                    try { reader.read(buffer); fail("Duration budget must hold") } catch (failure: InvalidMedia) {
                        assertEquals(ProblemCode.LIMIT_EXCEEDED, failure.code)
                    }
                    assertEquals(30_006L, input.filePointer)
                    assertEquals(0, buffer.position())
                }
                assertEquals(600_000_000L, reader.sampleTimeUs)
            }
        }
    }
}
