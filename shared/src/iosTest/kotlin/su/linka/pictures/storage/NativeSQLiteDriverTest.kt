package su.linka.pictures.storage

import androidx.sqlite.driver.NativeSQLiteDriver
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.WritePermit
import su.linka.pictures.domain.*
import su.linka.pictures.testing.testSQLiteDriver
import kotlin.test.*

class NativeSQLiteDriverTest {
    @Test fun systemEngineSupportsTheRequiredSqlAndReadOnlyObservers() {
        val driver = assertIs<NativeSQLiteDriver>(testSQLiteDriver())
        driver.open(":memory:").use { db ->
            val version = db.rows("SELECT sqlite_version()").single().single()!!
            val parts = version.split('.').map { it.toInt() }
            assertEquals(3, parts.size, version)
            // query_only (3.8.0) is newer than our WAL, FK and ALTER ADD COLUMN syntax.
            assertTrue(parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2] >= 3_008_000, version)
            db.exec("CREATE TABLE probe(value INTEGER)")
            db.exec("PRAGMA query_only=ON")
            assertEquals(1, db.number("PRAGMA query_only"))
            assertFails { db.exec("INSERT INTO probe VALUES(1)") }
            assertEquals(0, db.number("SELECT COUNT(*) FROM probe"))
        }
    }

    @Test fun schemaOneAndTwoKeepDataAcrossWriteRollbackAndReopen() = runTest {
        val fs = FileSystem.SYSTEM
        val parent = fs.canonicalize(FileSystem.SYSTEM_TEMPORARY_DIRECTORY) / "native-sqlite-${token()}"
        fs.createDirectory(parent, mustCreate = true)
        try {
            for (schema in listOf(1, 2)) {
                val root = parent / "schema-$schema"
                if (schema == 1) createSchema1(root)
                val opened = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(root, fs, testSQLiteDriver())).value
                try {
                    assertEquals(if (schema == 1) 4L else 0L, opened.library.listing.value.epoch)
                    if (schema == 2) {
                        val board = BoardDocument(BoardId("native-board"), "original", listOf(BoardPage(PageId("page"), 1, 1, emptyList())))
                        assertIs<OperationResult.Completed<*>>(opened.library.addBoard(board,
                            WritePermit(opened.library.listing.value.epoch) { OperationResult.Completed(Unit) }))
                    }
                    val entry = opened.library.listing.value.items.single()
                    assertIs<OperationResult.Completed<*>>(opened.library.renameBoard(entry.id, "native write", entry.revision,
                        WritePermit(opened.library.listing.value.epoch) { OperationResult.Completed(Unit) }))
                } finally { opened.close() }
                testSQLiteDriver().open((root / "library.sqlite3").toString()).use { db ->
                    assertEquals(2, db.readSchema())
                    db.exec("BEGIN IMMEDIATE")
                    db.exec("UPDATE entries SET name='must roll back'")
                    db.exec("ROLLBACK")
                    assertEquals("native write", db.rows("SELECT name FROM entries").single().single())
                }
                val reopened = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(root, fs, testSQLiteDriver())).value
                try { assertEquals("native write", reopened.library.listing.value.items.single().name) }
                finally { reopened.close() }
            }
        } finally { fs.deleteRecursively(parent) }
    }
}
