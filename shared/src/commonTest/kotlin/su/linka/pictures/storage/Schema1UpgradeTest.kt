package su.linka.pictures.storage

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import su.linka.pictures.application.OperationResult
import su.linka.pictures.domain.*
import kotlin.test.*

/** Frozen shipped schema, deliberately independent of the current schema producer. */
internal val shippedSchema1 = listOf(
    "CREATE TABLE library_meta(singleton INTEGER PRIMARY KEY CHECK(singleton=1), epoch INTEGER NOT NULL CHECK(epoch>=0), revision INTEGER NOT NULL CHECK(revision>=0), settings_json TEXT NOT NULL)",
    "CREATE TABLE entries(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('BOARD','RETAINED_ARCHIVE')), revision INTEGER NOT NULL CHECK(revision>0), order_index INTEGER NOT NULL UNIQUE, payload_json TEXT NOT NULL)",
    "CREATE TABLE drafts(id TEXT PRIMARY KEY NOT NULL, board_id TEXT, base_revision INTEGER, version INTEGER NOT NULL CHECK(version>0), payload_json TEXT NOT NULL)",
    "CREATE TABLE message(singleton INTEGER PRIMARY KEY CHECK(singleton=1), epoch INTEGER NOT NULL CHECK(epoch>=0), version INTEGER NOT NULL CHECK(version>=0), payload_json TEXT NOT NULL)",
    "CREATE TABLE assets(id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, media_type TEXT NOT NULL, byte_count INTEGER NOT NULL CHECK(byte_count>=0))",
    "CREATE TABLE asset_refs(owner_kind TEXT NOT NULL, owner_id TEXT NOT NULL, asset_id TEXT NOT NULL REFERENCES assets(id), PRIMARY KEY(owner_kind,owner_id,asset_id))",
    "CREATE TABLE recovery(singleton INTEGER PRIMARY KEY CHECK(singleton=1), id TEXT NOT NULL UNIQUE, created_at INTEGER NOT NULL, library_revision INTEGER NOT NULL, settings_json TEXT NOT NULL)",
    "CREATE TABLE recovery_entries(id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL, order_index INTEGER NOT NULL UNIQUE, payload_json TEXT NOT NULL)",
    "CREATE TABLE recovery_drafts(id TEXT PRIMARY KEY NOT NULL, board_id TEXT, base_revision INTEGER, version INTEGER NOT NULL, payload_json TEXT NOT NULL)",
)

internal fun createSchema1(root: Path) {
    FileSystem.SYSTEM.createDirectory(root, mustCreate = true)
    val document = BoardDocument(BoardId("existing"), "Мой набор", listOf(BoardPage(PageId("page"), 3, 3,
        listOf(BoardCell(0, CellKind.CONTENT, CardDocument(CardId("card"), "Помоги", speech = SpeechSource.None))))))
    val draft = EditorDraft(DraftId("draft"), document.id, 3, document,
        EditorForm.Card(PageId("page"), 1, CardId("unfinished"), label = "Последний ввод"))
    val settings = """{"schema":1,"value":{"textScale":1.25,"highContrast":true,"languageTag":"ru-RU","boards":{"existing":{"showPageButtons":false}}}}"""
    BundledSQLiteDriver().open((root / "library.sqlite3").toString()).use { db ->
        shippedSchema1.forEach { db.exec(it) }
        db.exec("INSERT INTO library_meta VALUES(1,4,9,?)", settings)
        db.exec("INSERT INTO entries VALUES(?,?,?,?,?,?)", "existing", document.name, "BOARD", 3, 0, encode(document))
        db.exec("INSERT INTO drafts VALUES(?,?,?,?,?)", "draft", "existing", 3, 7, encode(draft))
        db.exec("INSERT INTO message VALUES(1,4,11,?)", encode(MessageDraft(listOf(MessagePart.Text(MessagePartId("part"), "Хочу помощи")))))
        db.exec("INSERT INTO recovery VALUES(1,'recovery',123,8,?)", settings)
        db.exec("INSERT INTO recovery_entries SELECT * FROM entries")
        db.exec("INSERT INTO recovery_drafts SELECT * FROM drafts")
        db.exec("PRAGMA user_version=1")
    }
}

class Schema1UpgradeTest {
    @Test fun shippedLibraryKeepsBoardOpenFormMessageAndRecovery() = runTest {
        val fs = FileSystem.SYSTEM
        val parent = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "linka-schema1-${token()}"
        fs.createDirectory(parent, mustCreate = true)
        try {
            val root = parent / "library"
            createSchema1(root)
            val runtime = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(root, fs, BundledSQLiteDriver())).value
            try {
                assertEquals(4, runtime.library.listing.value.epoch)
                assertEquals(9, runtime.library.listing.value.revision)
                assertEquals("Мой набор", runtime.library.listing.value.items.single().name)
                val draft = assertIs<OperationResult.Completed<su.linka.pictures.application.ports.LeasedDraft>>(runtime.library.draft(DraftId("draft"))).value
                try {
                    assertEquals(7, draft.draft.version)
                    assertEquals("Последний ввод", (draft.draft.draft.form as EditorForm.Card).label)
                } finally { draft.lease.close() }
                assertEquals(11, assertIs<OperationResult.Completed<StoredMessage>>(runtime.messages.message()).value.version)
                assertEquals("recovery", assertIs<OperationResult.Completed<su.linka.pictures.application.ports.RecoveryInfo?>>(runtime.library.recovery()).value!!.id.value)
                assertEquals(1.25f, assertIs<OperationResult.Completed<PortableSettings>>(runtime.library.settings()).value.textScale)
            } finally { runtime.close() }
        } finally { fs.deleteRecursively(parent) }
    }
}
