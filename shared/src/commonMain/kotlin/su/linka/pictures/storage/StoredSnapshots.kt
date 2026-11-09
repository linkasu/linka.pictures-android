@file:OptIn(kotlin.time.ExperimentalTime::class)

package su.linka.pictures.storage

import androidx.sqlite.SQLiteConnection
import kotlin.time.Clock
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.withLock
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal class StoredSnapshots(private val runtime: StorageRuntime) {
    private val db get() = runtime.db
    private val library get() = runtime.libraryStore

    suspend fun builder(): OperationResult<SnapshotBuilder> = library.locked(mutation = true) { SnapshotStage.create(runtime) }

    suspend fun snapshot(): OperationResult<SnapshotReader> = runtime.operation {
        val context = currentCoroutineContext()
        val reader = runtime.writer.withLock { runtime.available(); capture { context.ensureStorageActive() } }
        try {
            storageActive()
            reader.assets().collect { runtime.mediaStore.verify(it) }
            runtime.writer.withLock { runtime.available(); storageActive() }
            reader
        } catch (error: Throwable) { reader.close(); throw error }
    }

    // shortcut: consistent metadata capture queues writers across O(metadata) staging IO;
    // cancellation is checked per document/asset, not a nonblocking/read-pool snapshot.
    private fun capture(checkActive: () -> Unit): SnapshotReader {
        checkActive()
        val stage = SnapshotStage.create(runtime)
        try {
            var order = -1L
            while (true) {
                checkActive()
                val row = db.rows("SELECT order_index,kind,revision,payload_json FROM entries WHERE order_index>? ORDER BY order_index LIMIT 1", order).singleOrNull() ?: break
                order = row[0]!!.toLong()
                if (row[1] == "BOARD") stage.board(PublishedBoard(decode(row[3]!!), row[2]!!.toLong()))
                else stage.retained(decode(row[3]!!))
                checkActive()
            }
            var previous = ""
            while (true) {
                checkActive()
                val row = db.rows("SELECT id,version,payload_json FROM drafts WHERE id>? ORDER BY id LIMIT 1", previous).singleOrNull() ?: break
                previous = row[0]!!
                stage.draft(StoredDraft(row[1]!!.toLong(), decode(row[2]!!)))
                checkActive()
            }
            previous = ""
            while (true) {
                checkActive()
                val row = db.rows("SELECT DISTINCT asset_id FROM asset_refs WHERE owner_kind IN ('BOARD','DRAFT','RETAINED') AND asset_id>? ORDER BY asset_id LIMIT 1", previous).singleOrNull() ?: break
                previous = row[0]!!
                stage.asset(runtime.mediaStore.asset(AssetId(previous)))
                checkActive()
            }
            checkActive()
            return stage.sealLocked(runtime.epoch(), runtime.revision(), library.readSettings(), library.readOrganization())
        } finally { stage.close() }
    }

    internal fun recoveryInfo(): RecoveryInfo? {
        val row = db.rows("SELECT id,created_at,library_revision FROM recovery WHERE singleton=1").singleOrNull() ?: return null
        return RecoveryInfo(
            RecoveryId(row[0]!!), row[1]!!.toLong(), row[2]!!.toLong(),
            db.number("SELECT count(*) FROM recovery_entries WHERE kind='BOARD'").toInt(),
            db.number("SELECT count(*) FROM recovery_drafts").toInt(),
            db.number("SELECT coalesce(sum(byte_count),0) FROM assets WHERE id IN (SELECT asset_id FROM asset_refs WHERE owner_kind IN ('RECOVERY_BOARD','RECOVERY_DRAFT','RECOVERY_RETAINED'))"),
            decode<LibraryOrganization>(db.rows("SELECT organization_json FROM recovery WHERE singleton=1").single().single()!!).folders.size,
        )
    }

    suspend fun recovery(): OperationResult<RecoveryInfo?> = library.locked { recoveryInfo() }

    private fun expected(revision: Long, message: MessageStamp, permit: WritePermit) {
        library.epoch(permit)
        if (runtime.revision() != revision || (runtime.messages as StoredMessages).read().stamp != message) fail(ProblemCode.CONFLICT)
    }

    private fun captureRecovery(id: RecoveryId) {
        db.exec("INSERT INTO recovery SELECT 1,?,?,revision,settings_json,organization_json FROM library_meta WHERE singleton=1", id.value, Clock.System.now().toEpochMilliseconds())
        db.exec("INSERT INTO recovery_entries SELECT * FROM entries")
        db.exec("INSERT INTO recovery_drafts SELECT * FROM drafts")
        db.exec("INSERT INTO asset_refs SELECT 'RECOVERY_BOARD',owner_id,asset_id FROM asset_refs WHERE owner_kind='BOARD'")
        db.exec("INSERT INTO asset_refs SELECT 'RECOVERY_DRAFT',owner_id,asset_id FROM asset_refs WHERE owner_kind='DRAFT'")
        db.exec("INSERT INTO asset_refs SELECT 'RECOVERY_RETAINED',owner_id,asset_id FROM asset_refs WHERE owner_kind='RETAINED'")
    }

    private fun newEpoch(settings: PortableSettings, organization: LibraryOrganization) {
        val epoch = runtime.next(runtime.epoch())
        db.exec("UPDATE library_meta SET epoch=?,settings_json=?,organization_json=? WHERE singleton=1", epoch, encode(settings), encode(organization))
        db.exec("UPDATE message SET epoch=?,version=0,payload_json=? WHERE singleton=1", epoch, encode(MessageDraft()))
        db.exec("DELETE FROM asset_refs WHERE owner_kind='MESSAGE'")
    }

    suspend fun replace(value: SnapshotReader, revision: Long, message: MessageStamp, permit: WritePermit): OperationResult<RecoveryInfo> = runtime.operation(mutation = true) {
        // Materialize only metadata into another owned DB using the SAME frozen schema.
        // ATTACH + INSERT SELECT then keeps filesystem/Flow callbacks outside the live SQL transaction.
        val stage = SnapshotStage.create(runtime)
        val path = runtime.root / "staging" / "replacement-${token()}.sqlite3"
        var reader: SnapshotReader? = null
        try {
            value.boards().collect { runtime.writer.withLock { stage.board(it) } }
            value.drafts().collect { runtime.writer.withLock { stage.draft(it) } }
            value.retained().collect { runtime.writer.withLock { stage.retained(it) } }
            value.assets().collect { runtime.writer.withLock { stage.asset(it) } }
            val sealed = runtime.writer.withLock { stage.sealLocked(runtime.epoch(), value.revision, value.settings, value.organization) }
            reader = sealed
            if (sealed.counts != value.counts) fail(ProblemCode.INTEGRITY_FAILURE)
            sealed.assets().collect { runtime.mediaStore.verify(it) }
            runtime.available(mutation = true)
            runtime.driver.open(path.toString()).use { staged ->
                // Derived, unpublished metadata only; each insert finishes before the next file read.
                staged.exec("PRAGMA journal_mode=MEMORY")
                staged.exec("PRAGMA synchronous=OFF")
                schemaStatements.forEach { staged.exec(it) }
                sealed.assets().collect { asset -> staged.exec("INSERT INTO assets VALUES(?,?,?,?)", asset.id.value, asset.kind.name, asset.mediaType, asset.byteCount) }
                var index = 0L
                sealed.boards().collect { board ->
                    staged.exec("INSERT INTO entries VALUES(?,?,?,?,?,?)", board.document.id.value, board.document.name, "BOARD", board.revision, index++, encode(board.document))
                    stageRefs(staged, "BOARD", board.document.id.value, board.document.assetIds())
                }
                sealed.retained().collect { archive ->
                    staged.exec("INSERT INTO entries VALUES(?,?,?,?,?,?)", archive.id.value, archive.name, "RETAINED_ARCHIVE", archive.revision, index++, encode(archive))
                    stageRefs(staged, "RETAINED", archive.id.value, setOf(archive.archive))
                }
                sealed.drafts().collect { draft ->
                    staged.exec("INSERT INTO drafts VALUES(?,?,?,?,?)", draft.draft.id.value, draft.draft.boardId?.value, draft.draft.baseRevision, draft.version, encode(draft.draft))
                    stageRefs(staged, "DRAFT", draft.draft.id.value, draft.draft.assetIds())
                }
            }
            runtime.writer.withLock {
                runtime.available(mutation = true)
                expected(revision, message, permit)
                if (recoveryInfo() != null) fail(ProblemCode.CONFLICT)
                sealed.lease.assets.forEach { runtime.mediaStore.checkFile(runtime.mediaStore.asset(it)) }
                val id = RecoveryId(token())
                db.exec("ATTACH DATABASE ? AS replacement", path.toString())
                try {
                    library.commit(permit) {
                        captureRecovery(id)
                        db.exec("DELETE FROM entries")
                        db.exec("DELETE FROM drafts")
                        db.exec("DELETE FROM asset_refs WHERE owner_kind IN ('BOARD','DRAFT','RETAINED')")
                        db.exec("INSERT INTO entries SELECT * FROM replacement.entries")
                        db.exec("INSERT INTO drafts SELECT * FROM replacement.drafts")
                        db.exec("INSERT INTO asset_refs SELECT * FROM replacement.asset_refs")
                        newEpoch(sealed.settings, sealed.organization)
                        recoveryInfo()!!
                    }
                } finally { if (!runtime.quarantined) runCatching { db.exec("DETACH DATABASE replacement") } }
            }
        } finally {
            if (!runtime.quarantined) {
                reader?.close()
                stage.close()
                runCatching { runtime.fs.delete(path, mustExist = false) }
                runCatching { runtime.fs.delete(path.parent!! / "${path.name}-journal", mustExist = false) }
            } else runtime.holdUncertain { stage.abandon() }
        }
    }

    private fun stageRefs(connection: SQLiteConnection, kind: String, id: String, assets: Set<AssetId>) {
        assets.forEach { connection.exec("INSERT INTO asset_refs VALUES(?,?,?)", kind, id, it.value) }
    }

    suspend fun restore(id: RecoveryId, revision: Long, message: MessageStamp, permit: WritePermit): OperationResult<RecoveryInfo> = library.locked(mutation = true, roots = {
        expected(revision, message, permit)
        db.rows("SELECT DISTINCT asset_id FROM asset_refs").map { AssetId(it.single()!!) }.toSet()
    }) {
        expected(revision, message, permit)
        val recovery = recoveryInfo() ?: fail(ProblemCode.NOT_FOUND)
        if (recovery.id != id) fail(ProblemCode.CONFLICT)
        runtime.mediaStore.validatePersistentRoots(hashFiles = false)
        val settings = decode<PortableSettings>(db.rows("SELECT settings_json FROM recovery WHERE singleton=1").single().single()!!)
        val organization = decode<LibraryOrganization>(db.rows("SELECT organization_json FROM recovery WHERE singleton=1").single().single()!!)
        val newId = RecoveryId(token())
        library.commit(permit) {
            // Negative order indices reserve the old rows while each pair is swapped.
            // Only two metadata rows are resident; no aggregate library or scratch schema.
            db.exec("UPDATE entries SET order_index=-order_index-1")
            db.exec("UPDATE recovery_entries SET order_index=-order_index-1")
            swapRows("entries", "recovery_entries", true)
            swapRows("drafts", "recovery_drafts", false)
            for (kind in listOf("BOARD", "DRAFT", "RETAINED")) {
                db.exec("UPDATE asset_refs SET owner_kind=? WHERE owner_kind=?", "SWAP_$kind", kind)
                db.exec("UPDATE asset_refs SET owner_kind=? WHERE owner_kind=?", kind, "RECOVERY_$kind")
                db.exec("UPDATE asset_refs SET owner_kind=? WHERE owner_kind=?", "RECOVERY_$kind", "SWAP_$kind")
            }
            db.exec("UPDATE recovery SET id=?,created_at=?,library_revision=?,settings_json=(SELECT settings_json FROM library_meta WHERE singleton=1),organization_json=(SELECT organization_json FROM library_meta WHERE singleton=1) WHERE singleton=1", newId.value, Clock.System.now().toEpochMilliseconds(), revision)
            newEpoch(settings, organization)
            recoveryInfo()!!
        }
    }

    private fun swapRows(current: String, recovery: String, ordered: Boolean) {
        var previous = ""
        while (true) {
            val id = db.rows("SELECT id FROM (SELECT id FROM $current UNION SELECT id FROM $recovery) WHERE id>? ORDER BY id LIMIT 1", previous).singleOrNull()?.single() ?: break
            previous = id
            val left = db.rows("SELECT * FROM $current WHERE id=?", id).singleOrNull()
            val right = db.rows("SELECT * FROM $recovery WHERE id=?", id).singleOrNull()
            db.exec("DELETE FROM $current WHERE id=?", id)
            db.exec("DELETE FROM $recovery WHERE id=?", id)
            fun insert(table: String, row: List<String?>?) {
                if (row == null) return
                val values = row.toMutableList()
                if (ordered) values[4] = (-values[4]!!.toLong() - 1).toString()
                db.exec("INSERT INTO $table VALUES(${values.joinToString(",") { "?" }})", *values.toTypedArray())
            }
            insert(current, right)
            insert(recovery, left)
        }
    }

    suspend fun delete(id: RecoveryId, permit: WritePermit): OperationResult<Unit> = library.locked(mutation = true) {
        library.epoch(permit)
        if (recoveryInfo()?.id != id) fail(ProblemCode.CONFLICT)
        library.commit(permit) {
            db.exec("DELETE FROM recovery")
            db.exec("DELETE FROM recovery_entries")
            db.exec("DELETE FROM recovery_drafts")
            db.exec("DELETE FROM asset_refs WHERE owner_kind IN ('RECOVERY_BOARD','RECOVERY_DRAFT','RECOVERY_RETAINED')")
        }
    }
}
