package su.linka.pictures.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class SetManifest(
    val version: String = "1.0",
    var columns: Int = 4,
    var rows: Int = 3,
    var withoutSpace: Boolean = false,
    val cards: MutableList<Card> = mutableListOf()
) {
    @Transient
    var configFileName: String = "config.json"
    
    @Transient
    var archiveFileName: String? = null

    val name: String
        get() = archiveFileName ?: configFileName

    val cacheKey: String
        get() = name.substringBeforeLast('.', name)

    fun getDefaultBitmap(): String? {
        for (card in cards) {
            val path = card.imagePath
            if (path != null) {
                return path
            }
        }
        return null
    }

    override fun toString(): String = name
}

