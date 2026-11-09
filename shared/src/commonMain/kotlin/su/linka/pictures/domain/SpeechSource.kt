package su.linka.pictures.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface SpeechSource {
    @Serializable @SerialName("recording")
    data class Recording(
        val assetId: AssetId,
        val audioText: String? = null,
        val audioVoice: String? = null,
    ) : SpeechSource

    @Serializable @SerialName("tts")
    data class Tts(val text: String, val languageTag: String? = null) : SpeechSource

    @Serializable @SerialName("none")
    data object None : SpeechSource
}
