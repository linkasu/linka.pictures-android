package su.linka.pictures.domain

import kotlinx.serialization.Serializable

@Serializable enum class PageNavigation { NONE, BOTTOM, SIDE }

@Serializable
data class BoardPresentation(
    val navigation: PageNavigation = PageNavigation.BOTTOM,
    val dragToCompose: Boolean = false,
) {
    constructor(showPageButtons: Boolean) : this(if (showPageButtons) PageNavigation.BOTTOM else PageNavigation.NONE)
    val showPageButtons: Boolean get() = navigation != PageNavigation.NONE
}

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
