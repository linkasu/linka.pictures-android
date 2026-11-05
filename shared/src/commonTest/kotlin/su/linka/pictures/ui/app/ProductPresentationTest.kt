package su.linka.pictures.ui.app

import su.linka.pictures.application.*
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.application.portability.TransferPhase
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.library.libraryEntryDescription
import su.linka.pictures.ui.access.commandsAvailable
import su.linka.pictures.ui.settings.voiceStatusLabel
import su.linka.pictures.ui.portability.transferPhaseLabel
import kotlin.test.*

class ProductPresentationTest {
    @Test fun unavailableOrUnknownCredentialsCloseProtectedUiWithoutAssumingOsSupport() {
        assertFalse(AssistantState.Initializing.commandsAvailable())
        assertFalse(AssistantState.Unavailable(Problem(ProblemCode.INTEGRITY_FAILURE)).commandsAvailable())
        assertTrue(AssistantState.Unprotected(OwnerVerificationAvailability.UNAVAILABLE).commandsAvailable())
        assertTrue(AssistantState.Protected(true, OwnerVerificationAvailability.UNAVAILABLE).commandsAvailable())
        assertEquals(VoiceStatus.entries.size, VoiceStatus.entries.map(::voiceStatusLabel).toSet().size)
    }
    @Test fun unknownCountsAreNotPresentedAsZeroAndRetainedArchivesAreExplicit() {
        val unknown = LibraryEntrySummary(BoardId("board"), "Доска", 1, LibraryEntryKind.BOARD, null)
        assertTrue(libraryEntryDescription(unknown).contains("неизвестно"))
        assertFalse(libraryEntryDescription(unknown).contains("0"))
        assertTrue(libraryEntryDescription(unknown.copy(cardCount = 0)).contains("0"))
        assertTrue(libraryEntryDescription(unknown.copy(kind = LibraryEntryKind.RETAINED_ARCHIVE)).contains("нечитаемый"))
    }
    @Test fun cancelledFailedAndUnconfirmedHaveDistinctActionableFeedback() {
        assertNotEquals(productFeedback(OperationResult.Cancelled), productFeedback(OperationResult.UnconfirmedCommit))
        assertTrue(productFeedback(OperationResult.UnconfirmedCommit).contains("Не повторяйте"))
        assertTrue(productFeedback(OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))).contains("Обновите"))
        assertEquals(TransferPhase.entries.size, TransferPhase.entries.map(::transferPhaseLabel).toSet().size)
    }
    @Test fun requestedThumbnailsContainImagesButNeverRecordings() {
        val image = AssetId("a".repeat(64)); val recording = AssetId("b".repeat(64))
        val board = BoardDocument(BoardId("board"), "Доска", listOf(BoardPage(PageId("page"), 1, 1,
            listOf(BoardCell(0, CellKind.CONTENT, CardDocument(CardId("card"), "Подпись", image, SpeechSource.Recording(recording)))))))
        assertEquals(setOf(image), board.pages.first().imageIds())
        val draft = EditorDraft(DraftId("draft"), null, null, board)
        assertTrue(draft.imageIds().isEmpty())
        assertEquals(setOf(image), draft.copy(form = EditorForm.Card(PageId("page"), 0, CardId("card"), image = image)).imageIds())
        assertEquals(setOf(image), draft.copy(form = EditorForm.Grid(PageId("page"), "1", "1")).imageIds())
        val otherImage = AssetId("c".repeat(64))
        val otherPage = BoardPage(PageId("other-page"), 1, 1, listOf(BoardCell(0, CellKind.CONTENT, CardDocument(CardId("other-card"), image = otherImage))))
        val state = CommunicationState(message = MessageDraft(cursor = BoardCursor(board.id, PageId("page"))),
            board = PublishedBoard(board.copy(pages = board.pages + otherPage), 1))
        assertEquals(setOf(image), state.pageImageIds())
        assertTrue(state.copy(message = MessageDraft(cursor = BoardCursor(BoardId("foreign"), PageId("page")))).pageImageIds().isEmpty())
    }
}
