package su.linka.pictures.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class SpeechChoice { TTS, RECORDING, NONE }

@Serializable
sealed interface EditorForm {
    @Serializable @SerialName("card")
    data class Card(
        val pageId: PageId,
        val slotIndex: Int,
        val cardId: CardId,
        val label: String = "",
        val speechText: String = "",
        val speechChoice: SpeechChoice = SpeechChoice.TTS,
        val useLabelForSpeech: Boolean = true,
        val image: AssetId? = null,
        val recording: AssetId? = null,
        val audioText: String? = null,
        val audioVoice: String? = null,
        val extensionsJson: String = "{}",
    ) : EditorForm {
        init { require(slotIndex >= 0) }
    }

    @Serializable @SerialName("grid")
    data class Grid(val pageId: PageId, val rowsText: String, val columnsText: String) : EditorForm
}

@Serializable
data class EditorDraft(
    val id: DraftId,
    val boardId: BoardId?,
    val baseRevision: Long?,
    val document: BoardDocument,
    val form: EditorForm? = null,
) {
    init {
        require((boardId == null) == (baseRevision == null))
        require(boardId == null || boardId == document.id)
        require(baseRevision == null || baseRevision > 0)
    }
}

@Serializable
data class StoredDraft(val version: Long, val draft: EditorDraft) {
    init { require(version > 0) }
}
