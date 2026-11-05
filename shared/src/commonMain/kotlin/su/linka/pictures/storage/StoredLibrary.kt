package su.linka.pictures.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal class StoredLibrary(internal val runtime: StorageRuntime) : LibraryStore {
    private data class Committed<T>(val value: T, val epoch: Long, val revision: Long)
    internal val db get() = runtime.db
    private val mutableListing = MutableStateFlow(LibraryListing(0, 0, emptyList()))
    override val listing: StateFlow<LibraryListing> = mutableListing
    private val snapshots = StoredSnapshots(runtime)

    internal fun refresh(epoch: Long = runtime.epoch(), revision: Long = runtime.revision()) {
        val items = buildList {
            var previous = -1L
            while (true) {
                val row = db.rows("SELECT id,name,kind,revision,payload_json,order_index FROM entries WHERE order_index>? ORDER BY order_index LIMIT 1", previous).singleOrNull() ?: break
                previous = row[5]!!.toLong()
                val kind = LibraryEntryKind.valueOf(row[2]!!)
                val count = if (kind == LibraryEntryKind.BOARD) decode<BoardDocument>(row[4]!!).pages.sumOf { page ->
                    page.cells.count { it.kind == CellKind.CONTENT }
                } else null
                add(LibraryEntrySummary(BoardId(row[0]!!), row[1]!!, row[3]!!.toLong(), kind, count))
            }
        }
        mutableListing.value = LibraryListing(epoch, revision, items)
    }

    private fun refreshAfterCommit(epoch: Long, revision: Long) {
        if (runtime.quarantined) { unconfirmed(); return }
        try { refresh(epoch, revision) } catch (error: Exception) {
            if (runtime.quarantined) unconfirmed() else {
                mutableListing.value = mutableListing.value.copy(epoch = epoch, revision = revision, refreshProblem = Problem(ProblemCode.IO_FAILURE))
            }
        }
    }

    internal fun unconfirmed() {
        mutableListing.value = mutableListing.value.copy(refreshProblem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
    }

    internal suspend fun <T> locked(mutation: Boolean = false, roots: () -> Set<AssetId> = { emptySet() }, block: suspend () -> T): OperationResult<T> = runtime.operation(mutation) {
        runtime.mediaStore.protecting(mutation, roots) {
            runtime.writer.withLock { runtime.available(mutation); storageActive(); block() }
        }
    }
    internal fun epoch(permit: WritePermit) { if (permit.epoch != runtime.epoch()) fail(ProblemCode.STALE_REQUEST) }
    internal fun refs(kind: String, id: String, assets: Set<AssetId>) {
        db.exec("DELETE FROM asset_refs WHERE owner_kind=? AND owner_id=?", kind, id)
        assets.forEach { db.exec("INSERT INTO asset_refs VALUES(?,?,?)", kind, id, it.value) }
    }

    internal suspend fun <T> commit(permit: WritePermit, roles: Map<AssetId, MediaKind> = emptyMap(), block: () -> T): T {
        epoch(permit)
        runtime.mediaStore.checkRoles(roles)
        roles.keys.forEach { runtime.mediaStore.checkFile(runtime.mediaStore.asset(it)) }
        val revision = runtime.next(runtime.revision())
        val result = runtime.transaction(permit) {
            val value = block()
            db.exec("UPDATE library_meta SET revision=? WHERE singleton=1", revision)
            Committed(value, runtime.epoch(), revision)
        }
        refreshAfterCommit(result.epoch, result.revision)
        return result.value
    }

    internal fun readBoard(id: BoardId): PublishedBoard {
        val row = db.rows("SELECT revision,payload_json,kind FROM entries WHERE id=?", id.value).singleOrNull() ?: fail(ProblemCode.NOT_FOUND)
        if (row[2] != "BOARD") fail(ProblemCode.INVALID_DATA)
        val document = decode<BoardDocument>(row[1]!!)
        if (document.id != id) fail(ProblemCode.INTEGRITY_FAILURE)
        runtime.mediaStore.checkRoles(document.assetRoles())
        return PublishedBoard(document, row[0]!!.toLong())
    }

    internal fun readDraft(id: DraftId): StoredDraft {
        val row = db.rows("SELECT version,payload_json FROM drafts WHERE id=?", id.value).singleOrNull() ?: fail(ProblemCode.NOT_FOUND)
        val draft = decode<EditorDraft>(row[1]!!)
        if (draft.id != id) fail(ProblemCode.INTEGRITY_FAILURE)
        runtime.mediaStore.checkRoles(draft.assetRoles())
        return StoredDraft(row[0]!!.toLong(), draft)
    }

    override suspend fun board(id: BoardId): OperationResult<LeasedBoard> {
        lateinit var board: PublishedBoard
        var epoch = 0L
        return locked(roots = { board = readBoard(id); epoch = runtime.epoch(); board.document.assetIds() }) {
            LeasedBoard(epoch, board, runtime.mediaStore.pin(board.document.assetIds()))
        }
    }

    override suspend fun draft(id: DraftId): OperationResult<LeasedDraft> {
        lateinit var draft: StoredDraft
        var epoch = 0L
        return locked(roots = { draft = readDraft(id); epoch = runtime.epoch(); draft.draft.assetIds() }) {
            LeasedDraft(epoch, draft, runtime.mediaStore.pin(draft.draft.assetIds()))
        }
    }

    override suspend fun drafts(): OperationResult<List<DraftSummary>> = locked {
        buildList {
            var previous = ""
            while (true) {
                val row = db.rows("SELECT id,version,payload_json FROM drafts WHERE id>? ORDER BY id LIMIT 1", previous).singleOrNull() ?: break
                previous = row[0]!!
                val draft = decode<EditorDraft>(row[2]!!)
                add(DraftSummary(draft.id, draft.boardId, draft.document.name, row[1]!!.toLong(), draft.baseRevision, draft.form != null))
            }
        }
    }

    override suspend fun saveDraft(value: EditorDraft, expectedVersion: Long?, permit: WritePermit): OperationResult<StoredDraft> = locked(mutation = true, roots = { epoch(permit); value.assetIds() }) {
        epoch(permit)
        val existing = db.rows("SELECT version FROM drafts WHERE id=?", value.id.value).singleOrNull()?.single()?.toLong()
        if (existing != expectedVersion) fail(ProblemCode.CONFLICT)
        val stored = StoredDraft(runtime.next(existing ?: 0), value)
        commit(permit, value.assetRoles()) {
            db.exec("INSERT OR REPLACE INTO drafts VALUES(?,?,?,?,?)", value.id.value, value.boardId?.value, value.baseRevision, stored.version, encode(value))
            refs("DRAFT", value.id.value, value.assetIds())
            stored
        }
    }

    override suspend fun deleteDraft(id: DraftId, expectedVersion: Long, permit: WritePermit): OperationResult<Unit> = locked(mutation = true) {
        epoch(permit)
        if (readDraft(id).version != expectedVersion) fail(ProblemCode.CONFLICT)
        commit(permit) {
            db.exec("DELETE FROM drafts WHERE id=?", id.value)
            refs("DRAFT", id.value, emptySet())
        }
    }

    internal fun putBoard(document: BoardDocument, revision: Long, order: Long) {
        db.exec("INSERT OR REPLACE INTO entries VALUES(?,?,?,?,?,?)", document.id.value, document.name, "BOARD", revision, order, encode(document))
        refs("BOARD", document.id.value, document.assetIds())
    }

    internal fun order(id: BoardId): Long = db.rows("SELECT order_index FROM entries WHERE id=?", id.value).singleOrNull()?.single()?.toLong()
        ?: runtime.next(db.number("SELECT coalesce(max(order_index),-1) FROM entries"))

    override suspend fun publishDraft(id: DraftId, expectedDraftVersion: Long, expectedBoardRevision: Long?, permit: WritePermit): OperationResult<PublishedBoard> = locked(mutation = true, roots = { epoch(permit); readDraft(id).draft.assetIds() }) {
        epoch(permit)
        val stored = readDraft(id)
        val value = stored.draft
        if (stored.version != expectedDraftVersion || value.baseRevision != expectedBoardRevision) fail(ProblemCode.CONFLICT)
        if (value.form != null) fail(ProblemCode.UNFINISHED_FORM)
        val previous = db.rows("SELECT revision,kind FROM entries WHERE id=?", value.document.id.value).singleOrNull()
        if (previous?.get(0)?.toLong() != expectedBoardRevision || (previous != null && previous[1] != "BOARD")) fail(ProblemCode.CONFLICT)
        val board = PublishedBoard(value.document, runtime.next(expectedBoardRevision ?: 0))
        commit(permit, value.assetRoles()) {
            putBoard(board.document, board.revision, order(board.document.id))
            db.exec("DELETE FROM drafts WHERE id=?", id.value)
            refs("DRAFT", id.value, emptySet())
            board
        }
    }

    override suspend fun addBoard(value: BoardDocument, permit: WritePermit): OperationResult<PublishedBoard> = locked(mutation = true, roots = { epoch(permit); value.assetIds() }) {
        epoch(permit)
        if (db.number("SELECT count(*) FROM entries WHERE id=?", value.id.value) != 0L) fail(ProblemCode.CONFLICT)
        commit(permit, value.assetRoles()) {
            putBoard(value, 1, order(value.id))
            PublishedBoard(value, 1)
        }
    }

    override suspend fun replaceBoard(value: BoardDocument, expectedRevision: Long, permit: WritePermit): OperationResult<PublishedBoard> = locked(mutation = true, roots = { epoch(permit); value.assetIds() }) {
        epoch(permit)
        if (readBoard(value.id).revision != expectedRevision) fail(ProblemCode.CONFLICT)
        val board = PublishedBoard(value, runtime.next(expectedRevision))
        commit(permit, value.assetRoles()) { putBoard(value, board.revision, order(value.id)); board }
    }

    override suspend fun renameBoard(id: BoardId, name: String, expectedRevision: Long, permit: WritePermit): OperationResult<PublishedBoard> = locked(mutation = true, roots = { epoch(permit); readBoard(id).document.assetIds() }) {
        epoch(permit)
        val old = readBoard(id)
        if (old.revision != expectedRevision) fail(ProblemCode.CONFLICT)
        val board = PublishedBoard(old.document.copy(name = name), runtime.next(expectedRevision))
        commit(permit, board.document.assetRoles()) { putBoard(board.document, board.revision, order(id)); board }
    }

    override suspend fun deleteBoard(id: BoardId, expectedRevision: Long, permit: WritePermit): OperationResult<Unit> = locked(mutation = true) {
        epoch(permit)
        val row = db.rows("SELECT revision,kind FROM entries WHERE id=?", id.value).singleOrNull() ?: fail(ProblemCode.NOT_FOUND)
        if (row[0]!!.toLong() != expectedRevision) fail(ProblemCode.CONFLICT)
        commit(permit) {
            db.exec("DELETE FROM entries WHERE id=?", id.value)
            refs(if (row[1] == "BOARD") "BOARD" else "RETAINED", id.value, emptySet())
        }
    }

    override suspend fun retainArchive(value: RetainedArchive, permit: WritePermit): OperationResult<Unit> = locked(mutation = true, roots = { epoch(permit); setOf(value.archive) }) {
        epoch(permit)
        if (db.number("SELECT count(*) FROM entries WHERE id=?", value.id.value) != 0L) fail(ProblemCode.CONFLICT)
        commit(permit, value.assetRoles()) {
            db.exec("INSERT INTO entries VALUES(?,?,?,?,?,?)", value.id.value, value.name, "RETAINED_ARCHIVE", value.revision, order(value.id), encode(value))
            refs("RETAINED", value.id.value, setOf(value.archive))
        }
    }

    override suspend fun settings(): OperationResult<PortableSettings> = locked { readSettings() }
    internal fun readSettings(): PortableSettings = decode(db.rows("SELECT settings_json FROM library_meta WHERE singleton=1").single().single()!!)

    override suspend fun saveSettings(value: PortableSettings, expectedLibraryRevision: Long, permit: WritePermit): OperationResult<Long> = locked(mutation = true) {
        epoch(permit)
        if (runtime.revision() != expectedLibraryRevision) fail(ProblemCode.CONFLICT)
        commit(permit) {
            db.exec("UPDATE library_meta SET settings_json=? WHERE singleton=1", encode(value))
            runtime.next(expectedLibraryRevision)
        }
    }

    override suspend fun snapshot(): OperationResult<SnapshotReader> = snapshots.snapshot()
    override suspend fun createSnapshotBuilder(): OperationResult<SnapshotBuilder> = snapshots.builder()
    override suspend fun replaceLibrary(value: SnapshotReader, expectedLibraryRevision: Long, expectedMessage: MessageStamp, permit: WritePermit): OperationResult<RecoveryInfo> =
        snapshots.replace(value, expectedLibraryRevision, expectedMessage, permit)
    override suspend fun recovery(): OperationResult<RecoveryInfo?> = snapshots.recovery()
    override suspend fun returnToRecovery(id: RecoveryId, expectedLibraryRevision: Long, expectedMessage: MessageStamp, permit: WritePermit): OperationResult<RecoveryInfo> =
        snapshots.restore(id, expectedLibraryRevision, expectedMessage, permit)
    override suspend fun deleteRecovery(id: RecoveryId, permit: WritePermit): OperationResult<Unit> = snapshots.delete(id, permit)
}
