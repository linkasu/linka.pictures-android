package su.linka.pictures.domain

import kotlinx.serialization.Serializable
import su.linka.pictures.text.isNativeText

@Serializable enum class InteractionMode { COMPOSE, DIRECT }
@Serializable enum class InputStyle { CARDS, LITERAL }
@Serializable enum class CellKind { CONTENT, SPACE, EMPTY }

@Serializable
data class BoardBehavior(
    val defaultMode: InteractionMode = InteractionMode.COMPOSE,
    val inputStyle: InputStyle = InputStyle.CARDS,
)

@Serializable
data class CardDocument(
    val id: CardId,
    val label: String = "",
    val image: AssetId? = null,
    val speech: SpeechSource = SpeechSource.None,
    // Opaque passive interchange fields; only Portability interprets this JSON.
    val extensionsJson: String = "{}",
)

@Serializable
data class BoardCell(
    val position: Int,
    val kind: CellKind,
    val card: CardDocument? = null,
    val extensionsJson: String = "{}",
) {
    init {
        require(position >= 0)
        require((kind == CellKind.CONTENT) == (card != null))
    }
}

@Serializable
data class BoardPage(
    val id: PageId,
    val rows: Int,
    val columns: Int,
    val cells: List<BoardCell>,
    val extensionsJson: String = "{}",
) {
    init {
        require(rows in 1..64 && columns in 1..64)
        require(cells.size <= rows * columns)
        require(cells.all { it.position < rows * columns })
        require(cells.map { it.position }.toSet().size == cells.size)
    }

    val capacity: Int get() = rows * columns
}

@Serializable
data class BoardDocument(
    val id: BoardId,
    val name: String,
    val pages: List<BoardPage>,
    val behavior: BoardBehavior = BoardBehavior(),
    val description: String? = null,
    val extensionsJson: String = "{}",
) {
    init {
        require(isNativeText(name))
        require(pages.isNotEmpty() && pages.size <= 256)
        require(pages.map { it.id }.toSet().size == pages.size)
        val cards = pages.flatMap { it.cells }.mapNotNull { it.card?.id }
        require(cards.toSet().size == cards.size)
    }
}

@Serializable
data class PublishedBoard(val document: BoardDocument, val revision: Long) {
    init { require(revision > 0) }
}
