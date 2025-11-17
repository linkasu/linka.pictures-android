package su.linka.pictures.model

import kotlinx.serialization.Serializable

@Serializable
data class Card(
    val id: Int,
    var imagePath: String? = null,
    var title: String? = null,
    var audioPath: String? = null,
    var cardType: Int = 0
) {
    constructor(id: Int, cardType: Int) : this(id, null, null, null, cardType)
}

