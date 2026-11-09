@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import su.linka.pictures.platform.audio.decoder.*

class IosAudioDecoderMappingTest {
    @Test
    fun earlyFailureWithoutFormatPreservesStatus() {
        for (status in listOf(LINKA_AUDIO_UNSUPPORTED, LINKA_AUDIO_INVALID, LINKA_AUDIO_LIMIT, LINKA_AUDIO_IO, LINKA_AUDIO_CANCELLED)) {
            val result = IosAudioDecoder.mapDecoded(status, 0, 0)
            assertEquals(status, result.status)
            assertEquals("", result.mediaType)
        }
    }

    @Test
    fun successfulKnownFormatsKeepDurationAndMime() {
        for ((format, mime) in listOf(
            LINKA_AUDIO_OGG to "audio/ogg",
            LINKA_AUDIO_MP3 to "audio/mpeg",
            LINKA_AUDIO_AMR to "audio/amr",
            LINKA_AUDIO_3GP to "audio/3gpp",
            LINKA_AUDIO_MP4 to "audio/mp4",
            LINKA_AUDIO_WAV to "audio/wav",
        )) {
            val result = IosAudioDecoder.mapDecoded(LINKA_AUDIO_OK, format, 220)
            assertEquals(LINKA_AUDIO_OK, result.status)
            assertEquals(220L, result.duration)
            assertEquals(mime, result.mediaType)
        }
    }

    @Test
    fun successfulUnknownFormatIsInvalidNotWave() {
        for (format in listOf(0, Int.MAX_VALUE)) {
            val result = IosAudioDecoder.mapDecoded(LINKA_AUDIO_OK, format, 220)
            assertEquals(LINKA_AUDIO_INVALID, result.status)
            assertEquals("", result.mediaType)
        }
    }
}
