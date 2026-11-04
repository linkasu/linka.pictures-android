package su.linka.pictures.domain

import kotlinx.serialization.Serializable

@Serializable data class BoardPresentation(val showPageButtons: Boolean = true)

@Serializable
data class PortableSettings(
    val textScale: Float = 1f,
    val highContrast: Boolean = false,
    val languageTag: String = "ru-RU",
    val boards: Map<BoardId, BoardPresentation> = emptyMap(),
) {
    init {
        require(textScale.isFinite() && textScale in 0.5f..3f)
        require(languageTag.isNotBlank() && languageTag.length <= 128)
    }
}
