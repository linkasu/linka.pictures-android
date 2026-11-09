package su.linka.pictures.domain

import kotlinx.serialization.Serializable

/** Strict schema1/backup1.0 wire model. Never written by the current producer. */
@Serializable
internal data class LegacyPortableSettings(
    val textScale: Float = 1f,
    val highContrast: Boolean = false,
    val languageTag: String = "ru-RU",
    val boards: Map<BoardId, LegacyBoardPresentation> = emptyMap(),
) {
    fun current() = PortableSettings(textScale, highContrast, languageTag,
        boards.mapValues { BoardPresentation(it.value.showPageButtons) })
}

@Serializable
internal data class LegacyBoardPresentation(val showPageButtons: Boolean = true)
