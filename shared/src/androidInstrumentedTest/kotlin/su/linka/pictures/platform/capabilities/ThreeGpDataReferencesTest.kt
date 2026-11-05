package su.linka.pictures.platform.capabilities

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.*
import su.linka.pictures.platform.audio.OwnedAudioTestFiles

class ThreeGpDataReferencesTest {
    private fun int(value: Int) = ByteBuffer.allocate(4).putInt(value).array()
    private fun long(value: Long) = ByteBuffer.allocate(8).putLong(value).array()
    private fun atom(type: String, payload: ByteArray, extended: Boolean = false, toEnd: Boolean = false): ByteArray {
        val header = if (extended) 16 else 8
        return int(if (toEnd) 0 else if (extended) 1 else header + payload.size) + type.toByteArray(Charsets.US_ASCII) +
            (if (extended) long((header + payload.size).toLong()) else byteArrayOf()) + payload
    }
    private fun join(values: List<ByteArray>) = ByteArrayOutputStream().also { stream -> values.forEach(stream::write) }.toByteArray()
    private fun url(flags: Int = 1, location: ByteArray = byteArrayOf(), version: Int = 0, extended: Boolean = false, toEnd: Boolean = false) =
        atom("url ", int((version shl 24) or flags) + location, extended, toEnd)
    private fun dref(entries: List<ByteArray>, count: Int = entries.size, fullBox: Int = 0) = atom("dref", int(fullBox) + int(count) + join(entries))
    private fun hierarchy(reference: ByteArray): ByteArray = atom("trak", atom("mdia", atom("minf", atom("dinf", reference))))
    private fun ftyp(extended: Boolean = false) = atom("ftyp", "3gp4".toByteArray() + int(0), extended)
    private fun container(movie: ByteArray) = ftyp() + movie + atom("mdat", byteArrayOf(1))
    private fun synthetic(reference: ByteArray) = container(atom("moov", hierarchy(reference)))
    private fun parse(bytes: ByteArray, check: () -> Unit = {}) {
        OwnedAudioTestFiles().use { files ->
            val file = files.file(bytes)
            RandomAccessFile(file.path, "r").use { assertEquals("audio/3gpp", MediaContainer.audio(it, check)) }
            assertArrayEquals(bytes, File(file.path).readBytes())
        }
    }
    private fun rejected(expected: ProblemCode, bytes: ByteArray) {
        try { parse(bytes); fail("Expected container refusal") } catch (failure: InvalidMedia) { assertEquals(expected, failure.code) }
    }
    // Only rewrites own tiny test containers. The genuine fixture has 32-bit boxes; mdat stays before moov.
    private fun replace(bytes: ByteArray, path: List<String>, replacement: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        var replaced = 0
        while (offset < bytes.size) {
            val size = ByteBuffer.wrap(bytes, offset, 4).int
            check(size >= 8 && size <= bytes.size - offset)
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (type == path.first()) {
                replaced++
                output.write(if (path.size == 1) replacement else atom(type, replace(bytes.copyOfRange(offset + 8, offset + size), path.drop(1), replacement)))
            } else output.write(bytes, offset, size)
            offset += size
        }
        check(replaced == 1)
        return output.toByteArray()
    }
    private val refPath = listOf("moov", "trak", "mdia", "minf", "dinf", "dref")
    private suspend fun inspected(bytes: ByteArray, expected: ProblemCode?) {
        OwnedAudioTestFiles().use { files ->
            val file = files.file(bytes, "wrong/hint")
            val before = LegacyCodecFixtures.sha256(bytes)
            val result = AndroidMediaInspectionPort().audio(file)
            assertEquals(before, LegacyCodecFixtures.sha256(File(file.path).readBytes()))
            if (expected == null) {
                assertTrue("Actual PCM decoder must succeed: $result", result is OperationResult.Completed)
                val info = (result as OperationResult.Completed).value
                assertEquals("audio/3gpp", info.mediaType); assertEquals(220L, info.durationMillis)
            } else {
                assertTrue(result is OperationResult.Failed)
                assertEquals(expected, (result as OperationResult.Failed).problem.code)
            }
        }
    }

    @Test fun genuineSelfContained3gpRetainsExactShaAndRealPcmDuration() = runBlocking {
        val genuine = LegacyCodecFixtures.threeGp()
        assertEquals(LegacyCodecFixtures.THREE_GP_SHA256, LegacyCodecFixtures.sha256(genuine))
        parse(genuine); inspected(genuine, null)
    }
    @Test fun externalUrlUrnAliasUnknownAndLocalFlagWithLocationAreRejectedBeforePlatformDecode() = runBlocking {
        val entries = listOf(
            url(0, "file:///p06-external-must-not-open.amr\u0000".toByteArray()),
            url(0), url(1, byteArrayOf(0)), url(2),
            atom("urn ", int(0) + "name\u0000outside.amr\u0000".toByteArray()),
            atom("alis", int(0) + byteArrayOf(1, 2)), atom("zzzz", int(1)),
        )
        for (entry in entries) {
            val bytes = replace(LegacyCodecFixtures.threeGp(), refPath, dref(listOf(entry)))
            rejected(ProblemCode.UNSUPPORTED_MEDIA, bytes)
            // Original embedded audio/sample tables remain playable; the policy must refuse before MediaExtractor.
            inspected(bytes, ProblemCode.UNSUPPORTED_MEDIA)
        }
    }
    @Test fun directReferenceMovieIsUnsupportedEvenWithEmbeddedAudio() = runBlocking {
        val genuine = LegacyCodecFixtures.threeGp()
        val offset = genuine.indexOfSlice("moov".toByteArray())
        check(offset >= 4)
        val size = ByteBuffer.wrap(genuine, offset - 4, 4).int
        val payload = genuine.copyOfRange(offset + 4, offset - 4 + size) + atom("rmra", byteArrayOf())
        val bytes = replace(genuine, listOf("moov"), atom("moov", payload))
        rejected(ProblemCode.UNSUPPORTED_MEDIA, bytes); inspected(bytes, ProblemCode.UNSUPPORTED_MEDIA)
    }
    @Test fun drefAndUrlLookingBytesInOpaqueMdatNeverBecomeExternalReferences() = runBlocking {
        val genuine = LegacyCodecFixtures.threeGp()
        val offset = genuine.indexOfSlice("mdat".toByteArray())
        check(offset >= 4)
        val size = ByteBuffer.wrap(genuine, offset - 4, 4).int
        val payload = genuine.copyOfRange(offset + 4, offset - 4 + size) + dref(listOf(url(0, "outside\u0000".toByteArray())))
        val bytes = replace(genuine, listOf("mdat"), atom("mdat", payload))
        parse(bytes); inspected(bytes, null)
    }
    @Test fun extended64AndSizeZeroRespectSourceAndParentBoundaries() {
        parse(ftyp(true) + atom("moov", hierarchy(dref(listOf(url(extended = true)))), extended = true) + atom("mdat", byteArrayOf(1), toEnd = true))
        parse(ftyp() + atom("mdat", byteArrayOf(1)) + atom("moov", hierarchy(dref(listOf(url(toEnd = true)))), toEnd = true))
    }
    @Test fun malformedExtentVersionCountAndTruncationAreInvalidNotPartiallyAccepted() {
        for (bytes in listOf(
            int(1) + "ftyp".toByteArray() + int(0),
            int(-1) + "ftyp".toByteArray() + ByteArray(8),
            int(1) + "ftyp".toByteArray() + long(Long.MAX_VALUE) + ByteArray(8),
            int(1) + "ftyp".toByteArray() + long(-1) + ByteArray(8),
            int(1) + "ftyp".toByteArray() + long(0) + ByteArray(8),
            container(atom("moov", int(100) + "dinf".toByteArray())),
            container(atom("moov", byteArrayOf(1))),
            synthetic(atom("dref", int(0))), synthetic(dref(listOf(url()), 0)),
            synthetic(dref(listOf(url()), 2)), synthetic(dref(listOf(url(), url()), 1)),
            synthetic(dref(listOf(url()), fullBox = 0x01000000)),
            synthetic(dref(listOf(url()), fullBox = 1)),
            synthetic(dref(listOf(atom("url ", byteArrayOf())))), synthetic(dref(listOf(url(version = 1)))),
        )) rejected(ProblemCode.INVALID_DATA, bytes)
    }
    @Test fun atomDepthAndEntryBudgetsAreInclusiveAndBounded() {
        val free = atom("free", byteArrayOf())
        parse(container(atom("moov", join(List(4093) { free })))) // ftyp+moov+mdat+4093=4096.
        rejected(ProblemCode.LIMIT_EXCEEDED, container(atom("moov", join(List(4094) { free }))))
        rejected(ProblemCode.LIMIT_EXCEEDED, synthetic(dref(listOf(url()), 4097)))
        var nesting = byteArrayOf()
        repeat(7) { nesting = atom("dinf", nesting) }
        parse(container(atom("moov", nesting)))
        rejected(ProblemCode.LIMIT_EXCEEDED, container(atom("moov", atom("dinf", nesting))))
    }
    @Test fun source64MiBAndMoov8MiBBoundsRejectBeforeDecoderWithoutLargeArrays() {
        OwnedAudioTestFiles().use { files ->
            val file = files.file(ftyp())
            RandomAccessFile(file.path, "rw").use { output ->
                output.setLength(MediaContainer.MAX_BYTES + 1)
            }
            RandomAccessFile(file.path, "r").use { input ->
                try { MediaContainer.audio(input) { }; fail("Source budget") } catch (failure: InvalidMedia) { assertEquals(ProblemCode.LIMIT_EXCEEDED, failure.code) }
            }
        }
        OwnedAudioTestFiles().use { files ->
            val prefix = ftyp()
            val file = files.file(prefix)
            val movieSize = 8L * 1024 * 1024 + 1
            RandomAccessFile(file.path, "rw").use { output ->
                output.seek(prefix.size.toLong()); output.writeInt(movieSize.toInt()); output.writeBytes("moov")
                output.seek(prefix.size + movieSize); output.write(atom("mdat", byteArrayOf(1)))
            }
            RandomAccessFile(file.path, "r").use { input ->
                try { MediaContainer.audio(input) { }; fail("Movie budget") } catch (failure: InvalidMedia) { assertEquals(ProblemCode.LIMIT_EXCEEDED, failure.code) }
            }
        }
    }
    @Test fun cancellationAtNestedAtomsPropagatesAndNeverChangesOrClosesSource() {
        for (cancelAt in listOf(1, 5)) OwnedAudioTestFiles().use { files ->
            val bytes = LegacyCodecFixtures.threeGp()
            val file = files.file(bytes)
            RandomAccessFile(file.path, "r").use { input ->
                var checks = 0
                try { MediaContainer.audio(input) { if (++checks == cancelAt) throw CancellationException("test cancellation") }; fail("Cancellation") }
                catch (_: CancellationException) { }
                assertTrue(input.fd.valid())
            }
            assertArrayEquals(bytes, File(file.path).readBytes())
        }
    }
    private fun ByteArray.indexOfSlice(value: ByteArray): Int = (0..size - value.size).firstOrNull { offset -> value.indices.all { this[offset + it] == value[it] } } ?: -1
}
