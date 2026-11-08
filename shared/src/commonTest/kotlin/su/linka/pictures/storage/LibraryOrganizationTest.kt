package su.linka.pictures.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import kotlin.test.*

class LibraryOrganizationTest {
    private fun <T> OperationResult<T>.ok() = assertIs<OperationResult.Completed<T>>(this).value
    private fun permit(epoch: Long = 0) = WritePermit(epoch) { OperationResult.Completed(Unit) }
    private val folder = LibraryFolder(FolderId("folder"), "Общение")
    private val child = LibraryFolder(FolderId("child"), "Дома", folder.id)
    private val board = BoardDocument(BoardId("board"), "Мой набор", listOf(BoardPage(PageId("page"), 1, 1, emptyList())))

    @Test fun placementPersistsAndNonemptyFolderCannotDisappear() = runTest {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "linka-folders-${token()}"
        fs.createDirectory(root, mustCreate = true)
        var runtime = StorageRuntime.open(root, fs, BundledSQLiteDriver()).ok()
        try {
            runtime.library.addBoard(board, permit()).ok()
            val value = LibraryOrganization(listOf(folder, child), mapOf(board.id to child.id))
            runtime.library.saveOrganization(value, 1, permit()).ok()
            val shown = runtime.library.listing.value
            assertEquals(value, shown.organization)
            assertIs<OperationResult.Failed>(runtime.library.saveOrganization(LibraryOrganization(), shown.revision, permit()))
            assertEquals(shown, runtime.library.listing.value)
            assertIs<OperationResult.Failed>(runtime.library.saveOrganization(value, 1, permit()))
            runtime.close()
            runtime = StorageRuntime.open(root, fs, BundledSQLiteDriver()).ok()
            assertEquals(value, runtime.library.listing.value.organization)
            runtime.library.renameBoard(board.id, "Другое имя", 1, permit()).ok()
            assertEquals(value, runtime.library.listing.value.organization)
            runtime.library.deleteBoard(board.id, 2, permit()).ok()
            assertTrue(runtime.library.listing.value.organization.placements.isEmpty())
            runtime.library.saveOrganization(LibraryOrganization(listOf(folder)), runtime.library.listing.value.revision, permit()).ok()
            runtime.library.saveOrganization(LibraryOrganization(), runtime.library.listing.value.revision, permit()).ok()
        } finally { runtime.close(); fs.deleteRecursively(root) }
    }

    @Test fun snapshotReplacementAndRecoverySwapKeepTheirOwnTrees() = runTest {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "linka-folder-snapshot-${token()}"
        fs.createDirectory(root, mustCreate = true)
        val source = StorageRuntime.open(root / "source", fs, BundledSQLiteDriver()).ok()
        val target = StorageRuntime.open(root / "target", fs, BundledSQLiteDriver()).ok()
        try {
            source.library.addBoard(board, permit()).ok()
            val original = LibraryOrganization(listOf(folder, child), mapOf(board.id to child.id))
            source.library.saveOrganization(original, 1, permit()).ok()
            val reader = source.library.snapshot().ok()
            try {
                assertEquals(original, reader.organization)
                val previous = LibraryOrganization(listOf(LibraryFolder(FolderId("previous"), "Старое")))
                target.library.saveOrganization(previous, 0, permit()).ok()
                val recovery = target.library.replaceLibrary(reader, 1, MessageStamp(0, 0), permit()).ok()
                assertEquals(original, target.library.listing.value.organization)
                assertEquals(1, recovery.folderCount)
                target.library.returnToRecovery(recovery.id, 2, MessageStamp(1, 0), permit(1)).ok()
                assertEquals(previous, target.library.listing.value.organization)
                assertEquals(original, reader.organization)
            } finally { reader.close() }
        } finally { source.close(); target.close(); fs.deleteRecursively(root) }
    }

    @Test fun cyclesUnknownParentsAndOversizedTreesAreRejected() {
        assertFailsWith<IllegalArgumentException> { LibraryOrganization(listOf(folder.copy(parentId = child.id), child)) }
        assertFailsWith<IllegalArgumentException> { LibraryOrganization(listOf(child)) }
        assertFailsWith<IllegalArgumentException> { LibraryOrganization(listOf(folder, folder)) }
        assertFailsWith<IllegalArgumentException> { LibraryOrganization(listOf(folder), mapOf(board.id to child.id)) }
        assertFailsWith<IllegalArgumentException> { LibraryOrganization(List(4097) { LibraryFolder(FolderId("f$it"), "Папка") }) }
        assertFailsWith<IllegalArgumentException> { LibraryOrganization(List(33) { LibraryFolder(FolderId("f$it"), "Папка", if (it == 0) null else FolderId("f${it-1}")) }) }
    }
}
