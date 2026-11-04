package su.linka.pictures

import kotlinx.serialization.Serializable

@Serializable
data class BoardDocument(
    val version: String = "1.0",
    val columns: Int = 4,
    val rows: Int = 3,
    val withoutSpace: Boolean = false,
    val cards: List<Card> = emptyList()
)
