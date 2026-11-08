package su.linka.pictures.ui.library

import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import kotlin.test.*

class LibraryFoldersTest {
    private val a = LibraryFolder(FolderId("a"), "Общение")
    private val b = LibraryFolder(FolderId("b"), "Дома", a.id)
    private val board = BoardId("board")
    private val listing = LibraryListing(4, 8, listOf(LibraryEntrySummary(board, "Мой набор", 1, LibraryEntryKind.BOARD, 0)),
        organization = LibraryOrganization(listOf(a, b), mapOf(board to b.id)))

    @Test fun foldersHavePathsAndMovesNeverCopyBoardIds() {
        assertEquals(listOf(a, b), folderPath(listing.organization, b.id))
        assertTrue(folderPath(listing.organization, null).isEmpty())
        val moved = applyFolderDecision(FolderDecision(listing, FolderOperation.MOVE_ENTRY, entryId = board), "", a.id)
        assertEquals(mapOf(board to a.id), moved.placements)
        assertEquals(listing.organization.folders, moved.folders)
        val root = applyFolderDecision(FolderDecision(listing, FolderOperation.MOVE_ENTRY, entryId = board), "", null)
        assertTrue(root.placements.isEmpty())
    }

    @Test fun nestedCreationRenameAndInvalidDeletionPreserveStructure() {
        val created = applyFolderDecision(FolderDecision(listing, FolderOperation.CREATE, FolderId("new"), parentId = b.id), "Новая", null)
        assertEquals(b.id, created.folders.last().parentId)
        val renamed = applyFolderDecision(FolderDecision(listing, FolderOperation.RENAME, b.id), "Улица", null)
        assertEquals("Улица", renamed.folders.last().name)
        assertEquals(listing.organization.placements, renamed.placements)
        assertFailsWith<IllegalArgumentException> { applyFolderDecision(FolderDecision(listing, FolderOperation.DELETE, a.id), "", null) }
        assertFailsWith<IllegalArgumentException> { applyFolderDecision(FolderDecision(listing, FolderOperation.MOVE_FOLDER, a.id), "", b.id) }
    }
}
