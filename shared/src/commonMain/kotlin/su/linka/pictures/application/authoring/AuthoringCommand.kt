package su.linka.pictures.application.authoring

import su.linka.pictures.application.ports.AssistantCapability
import su.linka.pictures.domain.*

data class AuthoringCommand(
    val id: CommandId,
    val ownerId: String,
    val capability: AssistantCapability?,
    val action: AuthoringAction,
)

sealed interface AuthoringAction {
    data class NewBoard(val name: String = "Новая доска") : AuthoringAction
    data class EditBoard(val boardId: BoardId) : AuthoringAction
    data class ResumeDraft(val draftId: DraftId) : AuthoringAction
    data object Resume : AuthoringAction
    data class Details(val name: String, val description: String?, val behavior: BoardBehavior) : AuthoringAction
    data class AddPage(val rows: Int = 3, val columns: Int = 3) : AuthoringAction
    data class MovePage(val pageId: PageId, val index: Int) : AuthoringAction
    data class RemovePage(val pageId: PageId) : AuthoringAction
    data class ResizeGrid(val pageId: PageId, val rows: Int, val columns: Int) : AuthoringAction
    data class OpenGrid(val pageId: PageId) : AuthoringAction
    data class ChangeGrid(val rowsText: String, val columnsText: String) : AuthoringAction
    data object SaveGrid : AuthoringAction
    data object CancelGrid : AuthoringAction
    data class SetCell(val pageId: PageId, val position: Int, val kind: CellKind) : AuthoringAction
    data class MoveCell(val fromPage: PageId, val from: Int, val toPage: PageId, val to: Int) : AuthoringAction
    data class OpenCard(val pageId: PageId, val position: Int) : AuthoringAction
    data class ChangeCardKind(val kind: CellKind) : AuthoringAction
    data class ChangeCard(
        val label: String,
        val speechText: String,
        val speechChoice: SpeechChoice,
        val useLabelForSpeech: Boolean,
    ) : AuthoringAction
    data object SaveCard : AuthoringAction
    data object CancelCard : AuthoringAction
    data object PickImage : AuthoringAction
    data class ImageFromText(val text: String) : AuthoringAction
    data object RemoveImage : AuthoringAction
    data object PickAudio : AuthoringAction
    data object Record : AuthoringAction
    data object FinishRecording : AuthoringAction
    data object CancelMedia : AuthoringAction
    data object Synthesize : AuthoringAction
    data object Preview : AuthoringAction
    data object StopPreview : AuthoringAction
    data object RetrySave : AuthoringAction
    data object Apply : AuthoringAction
    data object Background : AuthoringAction
    data object Revoke : AuthoringAction
    data object Close : AuthoringAction
    data object ForceDiscard : AuthoringAction
    data object DeleteDraft : AuthoringAction
}
