package su.linka.pictures.platform.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ProblemCode

class PcmWaveFileTest {
    @Test fun streamedSamplesFinalizeExactHeaderAndActualSampleCount() {
        OwnedAudioTestFiles().use { files ->
            val file = (files.createTemporaryFile("record.wav", "audio/wav") as OperationResult.Completed).value
            PcmWaveFile(file).use {
                it.write(shortArrayOf(100, -100), 2); it.write(shortArrayOf(200, -200), 2)
                assertEquals(4L, it.samples); it.finish()
            }
            val bytes = File(file.path).readBytes()
            assertEquals(52, bytes.size)
            assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals(44, ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            assertEquals(8, ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            assertEquals(100.toShort(), ByteBuffer.wrap(bytes, 44, 2).order(ByteOrder.LITTLE_ENDIAN).short)
        }
    }
    @Test fun emptyRecordingCannotBeFinalizedAndExistingFileIsNotOverwritten() {
        OwnedAudioTestFiles().use { files ->
            val file = (files.createTemporaryFile("record.wav", "audio/wav") as OperationResult.Completed).value
            PcmWaveFile(file).use {
                try { it.finish(); fail("Empty recording must fail") } catch (failure: NativeAudioFailure) {
                    assertEquals(ProblemCode.INVALID_DATA, (failure.result as OperationResult.Failed).problem.code)
                }
            }
            val before = File(file.path).readBytes()
            try { PcmWaveFile(file).close(); fail("Nonempty temp must not be overwritten") } catch (failure: NativeAudioFailure) {
                assertEquals(ProblemCode.CONFLICT, (failure.result as OperationResult.Failed).problem.code)
            }
            assertArrayEquals(before, File(file.path).readBytes())
        }
    }
}
