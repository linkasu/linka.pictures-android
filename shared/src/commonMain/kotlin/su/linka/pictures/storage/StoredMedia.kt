package su.linka.pictures.storage

import kotlin.random.Random
import kotlinx.coroutines.sync.withLock
import okio.*
import okio.ByteString.Companion.toByteString
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal fun token(): String = Random.nextBytes(16).toByteString().hex()

internal fun mediaByteCount(kind: MediaKind, current: Long, added: Long): Long {
    val limit = when (kind) {
        MediaKind.IMAGE, MediaKind.AUDIO -> 64L * 1024 * 1024
        MediaKind.ARCHIVE -> 2L * 1024 * 1024 * 1024
    }
    if (current < 0 || added < 0 || current > limit || added > limit - current) fail(ProblemCode.LIMIT_EXCEEDED)
    return current + added
}

internal class StoredMedia(private val runtime: StorageRuntime) : MediaStore {
    private val pins = StoragePins()
    private val fs get() = runtime.fs
    private val db get() = runtime.db
    internal fun path(id: AssetId): Path = runtime.root / "media" / id.value.take(2) / id.value

    internal fun pin(ids: Set<AssetId>): MediaLease = pins.pin(ids)
    internal fun stopRetains() = pins.stopRetains()
    private fun pinned(id: AssetId): Boolean = pins.pinned(id)
    private fun checkLease(id: AssetId, lease: MediaLease) {
        if (!runtime.accepting) fail(ProblemCode.STALE_REQUEST)
        if (!pins.allows(id, lease)) fail(ProblemCode.STALE_REQUEST)
    }

    internal fun asset(id: AssetId): MediaAsset {
        val row = db.rows("SELECT kind,media_type,byte_count FROM assets WHERE id=?", id.value).singleOrNull()
            ?: fail(ProblemCode.MISSING_ASSET)
        return MediaAsset(id, MediaKind.valueOf(row[0]!!), row[1]!!, row[2]!!.toLong())
    }

    internal fun checkRoles(roles: Map<AssetId, MediaKind>) {
        roles.forEach { (id, kind) -> if (asset(id).kind != kind) fail(ProblemCode.INTEGRITY_FAILURE) }
    }

    internal fun checkFile(asset: MediaAsset) = checkFile(asset.id, asset.byteCount)

    private fun checkFile(id: AssetId, bytes: Long) {
        val path = path(id)
        if (fs.metadataOrNull(path.parent!!)?.symlinkTarget != null) fail(ProblemCode.INTEGRITY_FAILURE)
        val metadata = fs.metadataOrNull(path) ?: fail(ProblemCode.MISSING_ASSET)
        if (!metadata.isRegularFile || metadata.symlinkTarget != null || metadata.size != bytes) fail(ProblemCode.INTEGRITY_FAILURE)
    }

    internal fun verify(asset: MediaAsset, checkCancellation: () -> Unit = {}) = verifyBytes(asset.id, asset.byteCount, checkCancellation)

    private fun verifyBytes(id: AssetId, bytes: Long, checkCancellation: () -> Unit = {}) {
        checkFile(id, bytes)
        val path = path(id)
        val hashing = HashingSource.sha256(fs.source(path))
        hashing.use { source ->
            val buffer = Buffer()
            var count = 0L
            while (true) {
                checkCancellation()
                val read = source.read(buffer, 8192)
                if (read == -1L) break
                count += read
                if (count > bytes) fail(ProblemCode.INTEGRITY_FAILURE)
                buffer.clear()
            }
            if (count != bytes || hashing.hash.hex() != id.value) fail(ProblemCode.INTEGRITY_FAILURE)
        }
    }

    internal suspend fun <T> protecting(mutation: Boolean, roots: () -> Set<AssetId>, block: suspend () -> T): T {
        val (assets, lease) = runtime.writer.withLock {
            runtime.available(mutation)
            storageActive()
            val ids = roots()
            val assets = ids.map(::asset)
            assets to pin(ids)
        }
        try {
            val context = kotlinx.coroutines.currentCoroutineContext()
            assets.forEach { verify(it) { context.ensureStorageActive() } }
            return block()
        } finally { lease.close() }
    }

    internal fun validatePersistentRoots(hashFiles: Boolean = true) {
        val meta = db.rows("SELECT singleton,epoch,revision,settings_json FROM library_meta").singleOrNull() ?: fail(ProblemCode.INVALID_DATA)
        if (meta[0] != "1" || meta[1]!!.toLong() < 0 || meta[2]!!.toLong() < 0) fail(ProblemCode.INVALID_DATA)
        decode<PortableSettings>(meta[3]!!)
        var expectedRefs = 0L
        fun check(kind: String, id: String, roles: Map<AssetId, MediaKind>) {
            val roots = roles.keys
            val stored = db.rows("SELECT asset_id FROM asset_refs WHERE owner_kind=? AND owner_id=?", kind, id).map { AssetId(it.single()!!) }.toSet()
            if (stored != roots) fail(ProblemCode.INTEGRITY_FAILURE)
            checkRoles(roles)
            roots.forEach { if (hashFiles) verify(asset(it)) else checkFile(asset(it)) }
            expectedRefs += roots.size
        }
        val hasRecovery = db.number("SELECT count(*) FROM recovery")
        if (hasRecovery > 1) fail(ProblemCode.INVALID_DATA)
        if (hasRecovery == 1L) {
            val recovery = db.rows("SELECT singleton,library_revision,settings_json FROM recovery").single()
            if (recovery[0] != "1" || recovery[1]!!.toLong() < 0) fail(ProblemCode.INVALID_DATA)
            decode<PortableSettings>(recovery[2]!!)
        }
        for (recovery in listOf(false, true)) {
            val entries = if (recovery) "recovery_entries" else "entries"
            val drafts = if (recovery) "recovery_drafts" else "drafts"
            val prefix = if (recovery) "RECOVERY_" else ""
            if (recovery && hasRecovery == 0L && (db.number("SELECT count(*) FROM recovery_entries") != 0L || db.number("SELECT count(*) FROM recovery_drafts") != 0L)) fail(ProblemCode.INTEGRITY_FAILURE)
            var previous = ""
            while (true) {
                val row = db.rows("SELECT id,name,kind,revision,order_index,payload_json FROM $entries WHERE id>? ORDER BY id LIMIT 1", previous).singleOrNull() ?: break
                previous = row[0]!!
                if (row[3]!!.toLong() <= 0 || row[4]!!.toLong() < 0) fail(ProblemCode.INVALID_DATA)
                when (row[2]) {
                    "BOARD" -> {
                        val document = decode<BoardDocument>(row[5]!!)
                        if (document.id.value != previous || document.name != row[1]) fail(ProblemCode.INTEGRITY_FAILURE)
                        check("${prefix}BOARD", previous, document.assetRoles())
                    }
                    "RETAINED_ARCHIVE" -> {
                        val archive = decode<RetainedArchive>(row[5]!!)
                        if (archive.id.value != previous || archive.name != row[1] || archive.revision != row[3]!!.toLong()) fail(ProblemCode.INTEGRITY_FAILURE)
                        check("${prefix}RETAINED", previous, archive.assetRoles())
                    }
                    else -> fail(ProblemCode.INVALID_DATA)
                }
            }
            previous = ""
            while (true) {
                val row = db.rows("SELECT id,board_id,base_revision,version,payload_json FROM $drafts WHERE id>? ORDER BY id LIMIT 1", previous).singleOrNull() ?: break
                previous = row[0]!!
                val draft = decode<EditorDraft>(row[4]!!)
                if (draft.id.value != previous || draft.boardId?.value != row[1] || draft.baseRevision != row[2]?.toLong() || row[3]!!.toLong() <= 0) fail(ProblemCode.INTEGRITY_FAILURE)
                check("${prefix}DRAFT", previous, draft.assetRoles())
            }
        }
        val message = db.rows("SELECT singleton,epoch,version,payload_json FROM message").singleOrNull() ?: fail(ProblemCode.INVALID_DATA)
        if (message[0] != "1" || message[1] != meta[1] || message[2]!!.toLong() < 0) fail(ProblemCode.INTEGRITY_FAILURE)
        check("MESSAGE", "1", decode<MessageDraft>(message[3]!!).assetRoles())
        if (db.number("SELECT count(*) FROM asset_refs") != expectedRefs) fail(ProblemCode.INTEGRITY_FAILURE)
    }

    override suspend fun prepare(source: Source, kind: MediaKind, mediaType: String): OperationResult<PreparedMedia> {
        // The source is consumed even if the runtime rejects an operation after close.
        var consumed = false
        return try { runtime.operation(mutation = true, cleanup = {
            if (!consumed) { consumed = true; runCatching { source.close() } }
        }) {
            val temp = runtime.root / "staging" / "media-${token()}.tmp"
            try {
                val hashing = HashingSource.sha256(source)
                var count = 0L
                fs.openReadWrite(temp, mustCreate = true).use { handle ->
                    handle.sink().buffer().use { sink ->
                        val buffer = Buffer()
                        while (true) {
                            storageActive()
                            val read = hashing.read(buffer, 8192)
                            if (read == -1L) break
                            count = mediaByteCount(kind, count, read)
                            sink.write(buffer, read)
                        }
                        sink.flush()
                    }
                    handle.flush()
                }
                consumed = true
                source.close()
                val asset = MediaAsset(AssetId(hashing.hash.hex()), kind, mediaType, count)
                val (hold, exists) = runtime.writer.withLock {
                    runtime.available(mutation = true)
                    val exists = fs.exists(path(asset.id))
                    pin(setOf(asset.id)) to exists
                }
                try {
                    if (exists) verify(asset)
                    storageActive()
                    runtime.writer.withLock {
                        runtime.available(mutation = true)
                        storageActive()
                        val target = path(asset.id)
                        fs.createDirectories(target.parent!!)
                        if (fs.metadataOrNull(target.parent!!)?.symlinkTarget != null) fail(ProblemCode.INTEGRITY_FAILURE)
                        val existing = db.rows("SELECT id FROM assets WHERE id=?", asset.id.value).isNotEmpty()
                        if (existing && asset(asset.id) != asset) fail(ProblemCode.CONFLICT)
                        var moving = false
                        var prepared: PreparedMedia? = null
                        try {
                            if (fs.exists(target)) checkFile(asset) else {
                                if (existing) fail(ProblemCode.MISSING_ASSET)
                                moving = true
                                fs.atomicMove(temp, target)
                            }
                            // The file and metadata are invisible to GC until this lock also installs the lease.
                            prepared = PreparedMedia(asset, pin(setOf(asset.id)))
                            if (!existing) runtime.transaction(null) {
                                db.exec("INSERT INTO assets VALUES(?,?,?,?)", asset.id.value, kind.name, mediaType, count)
                            }
                            prepared
                        } catch (error: Throwable) {
                            if (runtime.quarantined) runtime.holdUncertain { prepared?.lease?.close() }
                            else prepared?.lease?.close()
                            if (moving && !runtime.quarantined) runCatching {
                                if (db.number("SELECT count(*) FROM assets WHERE id=?", asset.id.value) == 0L && fs.exists(target)) {
                                    checkFile(asset)
                                    fs.delete(target)
                                }
                            }
                            throw error
                        }
                    }
                } finally { hold.close() }
            } finally {
                if (!runtime.quarantined) runCatching { fs.delete(temp, mustExist = false) }
            }
        } } finally {
            if (!consumed) storageWorker { consumed = true; runCatching { source.close() } }
        }
    }

    override suspend fun acquire(assets: Set<AssetId>): OperationResult<MediaLease> = runtime.operation {
        protecting(false, { assets }) { runtime.writer.withLock { runtime.available(); pin(assets) } }
    }

    override suspend fun metadata(id: AssetId): OperationResult<MediaAsset> = runtime.operation {
        var value: MediaAsset? = null
        protecting(false, { value = asset(id); setOf(id) }) { runtime.writer.withLock { runtime.available(); value!! } }
    }

    override fun file(id: AssetId, lease: MediaLease): OperationResult<LocalFile> = try {
        checkLease(id, lease)
        OperationResult.Completed(LocalFile(path(id).toString()))
    } catch (error: StorageFailure) { OperationResult.Failed(Problem(error.code)) }

    override fun source(id: AssetId, lease: MediaLease): OperationResult<Source> = try {
        checkLease(id, lease)
        OperationResult.Completed(fs.source(path(id)))
    } catch (error: StorageFailure) { OperationResult.Failed(Problem(error.code))
    } catch (error: Exception) { OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) }

    override suspend fun collectGarbage(): OperationResult<GarbageCollectionReport> = runtime.operation(mutation = true) {
        var removed = 0
        var bytes = 0L
        var failed = 0
        var previous = ""
        while (true) {
            var last = false
            val candidate = runtime.writer.withLock {
                runtime.available(mutation = true)
                val row = db.rows("SELECT id FROM assets WHERE id>? ORDER BY id LIMIT 1", previous).singleOrNull()
                if (row == null) { last = true; null } else {
                    val id = AssetId(row.single()!!)
                    previous = id.value
                    if (pinned(id) || db.number("SELECT count(*) FROM asset_refs WHERE asset_id=?", id.value) != 0L) null
                    else asset(id) to pin(setOf(id))
                }
            }
            if (last) break
            if (candidate == null) continue
            val (asset, hold) = candidate
            val id = asset.id
            val intent = runtime.root / "staging" / "gc-${id.value}.json"
            try {
                val exists = fs.exists(path(id))
                if (exists) {
                    val context = kotlinx.coroutines.currentCoroutineContext()
                    verify(asset) { context.ensureStorageActive() }
                } else {
                    val metadata = fs.metadataOrNull(intent)
                    if (metadata == null || !metadata.isRegularFile || metadata.symlinkTarget != null || (metadata.size ?: Long.MAX_VALUE) > 2048 || decode<MediaAsset>(fs.read(intent) { readUtf8() }) != asset) fail(ProblemCode.MISSING_ASSET)
                }
                runtime.writer.withLock {
                    runtime.available(mutation = true)
                    hold.close()
                    // Recheck after hashing: a publication/acquire may now own the asset.
                    if (pinned(id) || db.number("SELECT count(*) FROM asset_refs WHERE asset_id=?", id.value) != 0L) return@withLock
                    if (exists) {
                        checkFile(asset)
                        // A durable owned intent makes deletion retryable after a process interruption.
                        fs.openReadWrite(intent).use { handle ->
                            handle.resize(0)
                            handle.sink().buffer().use { it.writeUtf8(encode(asset)) }
                            handle.flush()
                        }
                        try { fs.delete(path(id), mustExist = true) } catch (error: Exception) {
                            if (fs.exists(path(id))) throw error
                        }
                    }
                    runtime.transaction(null) {
                        db.exec("DELETE FROM assets WHERE id=?", id.value)
                    }
                    removed++
                    bytes += asset.byteCount
                    runCatching { fs.delete(intent, mustExist = false) }
                }
            } catch (error: StorageAbort) { throw error
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (error: Exception) { failed++
            } finally { hold.close() }
        }
        // A crash/unknown prepare may leave finalized bytes without a committed metadata row.
        // Only exact hash paths in our owned media namespace are candidates, never arbitrary files.
        for (directory in fs.list(runtime.root / "media")) {
            if (!Regex("[0-9a-f]{2}").matches(directory.name)) continue
            val directoryMetadata = fs.metadata(directory)
            if (!directoryMetadata.isDirectory || directoryMetadata.symlinkTarget != null) continue
            for (file in fs.list(directory)) {
                if (!Regex("[0-9a-f]{64}").matches(file.name) || !file.name.startsWith(directory.name)) continue
                val id = AssetId(file.name)
                var hold: MediaLease? = null
                try {
                    val size = runtime.writer.withLock {
                        runtime.available(mutation = true)
                        if (pinned(id) || db.number("SELECT count(*) FROM assets WHERE id=?", id.value) != 0L) return@withLock null
                        if (db.number("SELECT count(*) FROM asset_refs WHERE asset_id=?", id.value) != 0L) fail(ProblemCode.INTEGRITY_FAILURE)
                        val metadata = fs.metadataOrNull(file) ?: return@withLock null
                        val size = metadata.size ?: fail(ProblemCode.INTEGRITY_FAILURE)
                        checkFile(id, size)
                        hold = pin(setOf(id))
                        size
                    } ?: continue
                    val context = kotlinx.coroutines.currentCoroutineContext()
                    verifyBytes(id, size) { context.ensureStorageActive() }
                    runtime.writer.withLock {
                        runtime.available(mutation = true)
                        hold?.close()
                        if (pinned(id) || db.number("SELECT count(*) FROM assets WHERE id=?", id.value) != 0L) return@withLock
                        checkFile(id, size)
                        try { fs.delete(file, mustExist = true) } catch (error: Exception) { if (fs.exists(file)) throw error }
                        removed++
                        bytes += size
                    }
                } catch (error: StorageAbort) { throw error
                } catch (error: kotlinx.coroutines.CancellationException) { throw error
                } catch (error: Exception) { failed++
                } finally { hold?.close() }
            }
        }
        runtime.writer.withLock { runtime.available(mutation = true) }
        GarbageCollectionReport(removed, bytes, failed)
    }
}
