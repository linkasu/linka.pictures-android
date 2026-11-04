package su.linka.pictures.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SelectionOrigin(
    val boardId: BoardId,
    val boardRevision: Long,
    val pageId: PageId,
    val cardId: CardId,
) {
    init { require(boardRevision > 0) }
}

@Serializable
data class CardSelectionSnapshot(
    val origin: SelectionOrigin,
    val label: String,
    val image: AssetId?,
    val speech: SpeechSource,
)

@Serializable
sealed interface MessagePart {
    val id: MessagePartId

    @Serializable @SerialName("card")
    data class Card(override val id: MessagePartId, val snapshot: CardSelectionSnapshot) : MessagePart

    @Serializable @SerialName("text")
    data class Text(override val id: MessagePartId, val text: String) : MessagePart
}

@Serializable
data class BoardCursor(val boardId: BoardId, val pageId: PageId, val firstCell: Int = 0) {
    init { require(firstCell >= 0) }
}

@Serializable
data class MessageDraft(
    val parts: List<MessagePart> = emptyList(),
    val cursor: BoardCursor? = null,
    val mode: InteractionMode = InteractionMode.COMPOSE,
) {
    init { require(parts.map { it.id }.toSet().size == parts.size) }
}

@Serializable
data class MessageStamp(val epoch: Long, val version: Long) {
    init { require(epoch >= 0 && version >= 0) }
}

@Serializable
data class StoredMessage(val epoch: Long, val version: Long, val draft: MessageDraft) {
    init { require(epoch >= 0 && version >= 0) }
    val stamp: MessageStamp get() = MessageStamp(epoch, version)
}
