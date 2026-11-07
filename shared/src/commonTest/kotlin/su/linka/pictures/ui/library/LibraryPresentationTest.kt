package su.linka.pictures.ui.library

import kotlin.test.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

class LibraryPresentationTest {
    @Test fun adaptiveTilesKeepReadableMinimumAndGrowWithFonts() {
        assertEquals(160f, libraryTileMinWidth(1f))
        assertEquals(160f, libraryTileMinWidth(0.5f))
        assertEquals(320f, libraryTileMinWidth(2f))
        assertEquals(24f, LibraryIcons.Add.viewportWidth)
        assertEquals(24f, LibraryIcons.More.viewportHeight)
        assertTrue(LibraryIcons.Back.autoMirror)
    }

    @Test fun firstImageIsCoverAndRecordingIsNeverAnImageDemand() {
        val first = AssetId("a".repeat(64))
        val second = AssetId("b".repeat(64))
        val recording = AssetId("c".repeat(64))
        val board = BoardDocument(BoardId("board"), "Доска", listOf(
            BoardPage(PageId("page"), 1, 3, listOf(
                BoardCell(0, CellKind.CONTENT, CardDocument(CardId("sound"), speech = SpeechSource.Recording(recording))),
                BoardCell(1, CellKind.CONTENT, CardDocument(CardId("first"), image = first)),
                BoardCell(2, CellKind.CONTENT, CardDocument(CardId("second"), image = second)),
            )),
        ))
        assertEquals(first, libraryCoverImage(board))
        assertNull(libraryCoverImage(board.copy(pages = listOf(board.pages.first().copy(cells = listOf(board.pages.first().cells.first()))))))
    }

    @Test fun unreadableAndUnknownCountsNeverBecomeAnEmptyBoard() {
        val unknown = LibraryEntrySummary(BoardId("board"), "Доска", 1, LibraryEntryKind.BOARD, null)
        assertTrue(libraryEntryDescription(unknown).contains("неизвестно"))
        val retained = unknown.copy(kind = LibraryEntryKind.RETAINED_ARCHIVE, problem = Problem(ProblemCode.NEWER_SCHEMA))
        assertTrue(libraryEntryDescription(retained).contains("нечитаемый"))
        assertTrue(libraryEntryDescription(retained).contains("NEWER_SCHEMA"))
        assertFalse(libraryEntryDescription(retained).contains("Карточек: 0"))
    }

    @Test fun authorizationReadinessDoesNotTreatNullCapabilityOrUnprotectedAsLocked() {
        assertFalse(needsLibraryAuthorization(OperationResult.Completed(Unit)))
        assertFalse(needsLibraryAuthorization(null))
        assertTrue(needsLibraryAuthorization(OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))))
        assertFalse(needsLibraryAuthorization(OperationResult.Failed(Problem(ProblemCode.CONFLICT))))
    }

    @Test fun failedDecisionsStayCapturedWithoutRefreshOrAutomaticReplay() {
        assertFalse(libraryDecisionFinished(OperationResult.Failed(Problem(ProblemCode.CONFLICT))))
        assertFalse(libraryDecisionFinished(OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))))
        assertFalse(libraryDecisionFinished(OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))))
        assertFalse(libraryDecisionFinished(OperationResult.Cancelled))
        assertTrue(libraryDecisionFinished(OperationResult.Completed(Unit)))
        assertTrue(libraryDecisionFinished(OperationResult.UnconfirmedCommit))
    }
}
