package su.linka.pictures.platform.capabilities

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.*
import su.linka.pictures.platform.audio.OwnedAudioTestFiles

class AndroidMediaInspectionPortTest {
    @Test fun actualPcmWaveDecodesDespiteWrongExtensionAndMimeHint() = runBlocking {
        OwnedAudioTestFiles().use { files ->
            val file = files.file(TinyMediaFixtures.wave(), "audio/mpeg")
            val before = File(file.path).readBytes()
            val result = AndroidMediaInspectionPort().audio(file)
            assertTrue("Real PCM decoder must succeed, not metadata-only or an assumed codec", result is OperationResult.Completed)
            val info = (result as OperationResult.Completed).value
            assertEquals("audio/wav", info.mediaType)
            assertEquals(100L, info.durationMillis)
            assertArrayEquals(before, File(file.path).readBytes())
        }
    }
    @Test fun pngIsActuallyDecodedAndDimensionsAreNotTakenFromMimeHint() = runBlocking {
        OwnedAudioTestFiles().use { files ->
            val result = AndroidMediaInspectionPort().image(files.file(TinyMediaFixtures.png(), "image/jpeg"))
            assertTrue(result is OperationResult.Completed)
            val info = (result as OperationResult.Completed).value
            assertEquals(1, info.width); assertEquals(1, info.height); assertEquals("image/png", info.mediaType)
        }
    }
    @Test fun truncatedContainersAndMagicOnlyNeverCountAsDecodedAudio() = runBlocking {
        OwnedAudioTestFiles().use { files ->
            val inspector = AndroidMediaInspectionPort()
            for (bytes in listOf(TinyMediaFixtures.wave().dropLast(1).toByteArray(), "OggS\u0000".toByteArray() + ByteArray(40), "#!AMR\n".toByteArray() + byteArrayOf(0x3c), "not audio at all".toByteArray())) {
                assertTrue(inspector.audio(files.file(bytes)) is OperationResult.Failed)
            }
            assertTrue(inspector.image(files.file(TinyMediaFixtures.png().dropLast(1).toByteArray())) is OperationResult.Failed)
        }
    }
    @Test fun invalidPngCrcAndWaveHeaderHaveExplicitFailure() = runBlocking {
        OwnedAudioTestFiles().use { files ->
            val png = TinyMediaFixtures.png().also { it[29] = (it[29].toInt() xor 1).toByte() }
            val wave = TinyMediaFixtures.wave().also { it[32] = 0; it[33] = 0 }
            assertEquals(ProblemCode.INVALID_DATA, (AndroidMediaInspectionPort().image(files.file(png)) as OperationResult.Failed).problem.code)
            assertEquals(ProblemCode.INVALID_DATA, (AndroidMediaInspectionPort().audio(files.file(wave)) as OperationResult.Failed).problem.code)
        }
    }
    @Test fun sourceAndImageBoundsAreRejectedBeforeLargeDecodeAllocation() = runBlocking {
        OwnedAudioTestFiles().use { files ->
            val large = files.file(byteArrayOf())
            RandomAccessFile(large.path, "rw").use { it.setLength(MediaContainer.MAX_BYTES + 1) }
            assertEquals(ProblemCode.LIMIT_EXCEEDED, (AndroidMediaInspectionPort().audio(large) as OperationResult.Failed).problem.code)
            assertEquals(ProblemCode.LIMIT_EXCEEDED, (AndroidMediaInspectionPort().image(files.file(TinyMediaFixtures.png(8193))) as OperationResult.Failed).problem.code)
            assertEquals(ProblemCode.LIMIT_EXCEEDED, (AndroidMediaInspectionPort().image(files.file(TinyMediaFixtures.png(4096, 3907))) as OperationResult.Failed).problem.code)
        }
    }
    @Test fun amrWidebandAndNon3gpFtypAreNotGuessedAsSupportedLegacyAudio() = runBlocking {
        OwnedAudioTestFiles().use { files ->
            val wb = "#!AMR-WB\n".toByteArray() + ByteArray(20)
            val mp4 = byteArrayOf(0, 0, 0, 16) + "ftypisom".toByteArray() + ByteArray(4)
            for (bytes in listOf(wb, mp4)) assertEquals(ProblemCode.UNSUPPORTED_MEDIA, (AndroidMediaInspectionPort().audio(files.file(bytes)) as OperationResult.Failed).problem.code)
        }
    }
    @Test fun cancelledInspectionDoesNotBecomeFailedOrChangeSource() = runBlocking {
        OwnedAudioTestFiles().use { files ->
            val file = files.file(TinyMediaFixtures.wave())
            val job = launch(start = CoroutineStart.LAZY) { AndroidMediaInspectionPort().audio(file); error("Cancelled inspection returned") }
            job.cancel(); job.join()
            assertTrue(job.isCancelled)
            assertTrue(File(file.path).exists())
        }
    }
    @Test fun truncatedMp3AmrAndIsoBoxesAreRejectedBeforeDecoderCanAcceptAPrefix() = runBlocking {
        OwnedAudioTestFiles().use { files ->
            val mp3 = byteArrayOf(-1, -5, -112, 0) + ByteArray(16)
            val amr = "#!AMR\n".toByteArray() + byteArrayOf(0x3c) + ByteArray(5)
            val iso = byteArrayOf(0, 0, 0, 16) + "ftyp3gp4".toByteArray() + ByteArray(4) + byteArrayOf(0, 0, 0, 100) + "mdat".toByteArray()
            check(mp3.size < 417 && amr.size == 12 && iso.size == 24)
            for (bytes in listOf(mp3, amr, iso)) assertEquals(ProblemCode.INVALID_DATA, (AndroidMediaInspectionPort().audio(files.file(bytes)) as OperationResult.Failed).problem.code)
        }
    }
    @Test fun declaredLongWaveCannotBypassTenMinuteBudget() = runBlocking {
        OwnedAudioTestFiles().use { files ->
            val file = files.file(TinyMediaFixtures.wave())
            val dataBytes = (16000 * 600 + 1) * 2
            RandomAccessFile(file.path, "rw").use {
                it.setLength(44L + dataBytes)
                it.seek(4); it.writeInt(Integer.reverseBytes(36 + dataBytes))
                it.seek(40); it.writeInt(Integer.reverseBytes(dataBytes))
            }
            assertEquals(ProblemCode.LIMIT_EXCEEDED, (AndroidMediaInspectionPort().audio(file) as OperationResult.Failed).problem.code)
        }
    }
}
