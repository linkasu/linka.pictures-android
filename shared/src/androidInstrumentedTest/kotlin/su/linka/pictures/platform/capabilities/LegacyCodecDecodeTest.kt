package su.linka.pictures.platform.capabilities

import android.util.Log
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.LocalFile
import su.linka.pictures.platform.audio.OwnedAudioTestFiles

class LegacyCodecDecodeTest {
    private suspend fun decoded(
        bytes: ByteArray,
        expectedSha: String,
        canonical: String,
        extension: String = "wav",
        hint: String = "audio/wav",
        exactMillis: Long? = null,
    ) {
        assertTrue(bytes.isNotEmpty() && bytes.size <= 65536)
        assertEquals(expectedSha, LegacyCodecFixtures.sha256(bytes))
        OwnedAudioTestFiles().use { files ->
            val source = File(files.root, "${UUID.randomUUID()}.$extension")
            check(source.createNewFile())
            source.writeBytes(bytes)
            assertEquals(expectedSha, LegacyCodecFixtures.sha256(source.readBytes()))
            val file = LocalFile(source.path, source.name, hint)
            val result = try {
                AndroidMediaInspectionPort().audio(file)
            } finally {
                assertEquals("Inspection must not change source bytes", expectedSha, LegacyCodecFixtures.sha256(source.readBytes()))
            }
            assertTrue("Actual Android PCM decode required for $canonical: $result", result is OperationResult.Completed)
            val info = (result as OperationResult.Completed).value
            assertEquals(canonical, info.mediaType)
            assertTrue("Positive frame-derived duration must be <=1s", info.durationMillis in 1..1000)
            if (exactMillis != null) assertEquals(exactMillis, info.durationMillis)
            Log.i("P06CodecFixture", "type=$canonical durationMillis=${info.durationMillis} fixtureSHA=$expectedSha")
        }
    }

    @Test fun trueVorbisDecodedDespiteWaveNameAndHint() = runBlocking {
        decoded(LegacyCodecFixtures.vorbis(), LegacyCodecFixtures.VORBIS_SHA256, "audio/ogg")
    }
    @Test fun trueMp3DecodedDespiteWaveNameAndHint() = runBlocking {
        decoded(LegacyCodecFixtures.mp3(), LegacyCodecFixtures.MP3_SHA256, "audio/mpeg")
    }
    @Test fun rawAmrNbDecodedDespiteWaveNameAndHint() = runBlocking {
        decoded(LegacyCodecFixtures.amr(), LegacyCodecFixtures.AMR_SHA256, "audio/amr")
    }
    @Test fun amrNbIn3gpDecodedDespiteWaveNameAndHint() = runBlocking {
        decoded(LegacyCodecFixtures.threeGp(), LegacyCodecFixtures.THREE_GP_SHA256, "audio/3gpp")
    }
    @Test fun pcmWaveUnderOggNameAndHintStillDecodesAsWave() = runBlocking {
        decoded(TinyMediaFixtures.wave(), LegacyCodecFixtures.WAVE_SHA256, "audio/wav", "ogg", "audio/ogg", 100)
    }
}
