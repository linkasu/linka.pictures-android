@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package su.linka.pictures.storage

import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.*
import okio.Path
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal class SnapshotStage(private val runtime: StorageRuntime, private val directory: Path) : SnapshotBuilder {
    private enum class Owner { BUILDER, READER, CLOSED }
    private data class Ownership(val owner: Owner, val pins: List<MediaLease> = emptyList())
    private val ownership = AtomicReference(Ownership(Owner.BUILDER))
    private val entryIds = mutableSetOf<BoardId>()
    private val draftIds = mutableSetOf<DraftId>()
    private val roots = mutableMapOf<AssetId, MediaKind>()
    private val manifest = mutableSetOf<AssetId>()
    private var boardCount = 0
    private var draftCount = 0
    private var retainedCount = 0
    private var assetCount = 0
    private var assetBytes = 0L

    private fun requireBuilder() {
        runtime.available(mutation = true)
        if (ownership.load().owner != Owner.BUILDER) fail(ProblemCode.STALE_REQUEST)
    }

    private inline fun <reified T> write(kind: String, index: Int, value: T) {
        requireBuilder()
        val json = encode(value)
        if (json.encodeToByteArray().size > 8 * 1024 * 1024) fail(ProblemCode.LIMIT_EXCEEDED)
        runtime.fs.write(directory / "$kind-$index.json", mustCreate = true) { writeUtf8(json) }
        requireBuilder()
    }

    private fun attach(pin: MediaLease) {
        while (true) {
            val old = ownership.load()
            if (old.owner != Owner.BUILDER) { pin.close(); fail(ProblemCode.STALE_REQUEST) }
            if (ownership.compareAndSet(old, old.copy(pins = old.pins + pin))) return
        }
    }

    private fun compatible(roles: Map<AssetId, MediaKind>): Map<AssetId, MediaKind> = roles.also {
        if (roles.any { (id, kind) -> roots[id]?.let { it != kind } == true }) fail(ProblemCode.INVALID_DATA)
    }

    internal fun board(value: PublishedBoard) {
        requireBuilder()
        if (value.document.id in entryIds) fail(ProblemCode.CONFLICT)
        val roles = compatible(value.document.assetRoles())
        write("board", boardCount, value)
        entryIds += value.document.id
        roots.putAll(roles)
        boardCount++
    }

    internal fun draft(value: StoredDraft) {
        requireBuilder()
        if (value.draft.id in draftIds) fail(ProblemCode.CONFLICT)
        val roles = compatible(value.draft.assetRoles())
        write("draft", draftCount, value)
        draftIds += value.draft.id
        roots.putAll(roles)
        draftCount++
    }

    internal fun retained(value: RetainedArchive) {
        requireBuilder()
        if (value.id in entryIds) fail(ProblemCode.CONFLICT)
        val roles = compatible(value.assetRoles())
        write("retained", retainedCount, value)
        entryIds += value.id
        roots.putAll(roles)
        retainedCount++
    }

    internal fun asset(value: MediaAsset) {
        requireBuilder()
        if (value.id in manifest) fail(ProblemCode.CONFLICT)
        if (runtime.mediaStore.asset(value.id) != value) fail(ProblemCode.INTEGRITY_FAILURE)
        runtime.mediaStore.checkFile(value)
        if (Long.MAX_VALUE - assetBytes < value.byteCount) fail(ProblemCode.LIMIT_EXCEEDED)
        val pin = runtime.mediaStore.pin(setOf(value.id))
        try {
            write("asset", assetCount, value)
            attach(pin)
        } catch (error: Throwable) { pin.close(); throw error }
        manifest += value.id
        assetCount++
        assetBytes += value.byteCount
    }

    override suspend fun addBoard(value: PublishedBoard): OperationResult<Unit> = runtime.libraryStore.locked(mutation = true) { board(value) }
    override suspend fun addDraft(value: StoredDraft): OperationResult<Unit> = runtime.libraryStore.locked(mutation = true) { draft(value) }
    override suspend fun addRetained(value: RetainedArchive): OperationResult<Unit> = runtime.libraryStore.locked(mutation = true) { retained(value) }
    override suspend fun addAsset(value: MediaAsset): OperationResult<Unit> = runtime.libraryStore.locked(mutation = true, roots = { setOf(value.id) }) { asset(value) }

    internal fun sealLocked(epoch: Long, revision: Long, settings: PortableSettings): SnapshotReader {
        requireBuilder()
        require(epoch >= 0 && revision >= 0)
        if (roots.keys != manifest) fail(ProblemCode.INTEGRITY_FAILURE)
        runtime.mediaStore.checkRoles(roots)
        manifest.forEach { runtime.mediaStore.checkFile(runtime.mediaStore.asset(it)) }
        val lease = runtime.mediaStore.pin(manifest)
        val counts = SnapshotCounts(boardCount, draftCount, retainedCount, assetCount, assetBytes)
        val reader = Reader(epoch, revision, settings, counts, lease)
        while (true) {
            val old = ownership.load()
            if (old.owner != Owner.BUILDER) { lease.close(); fail(ProblemCode.STALE_REQUEST) }
            if (ownership.compareAndSet(old, Ownership(Owner.READER, listOf(lease)))) {
                old.pins.forEach { it.close() }
                return reader
            }
        }
    }

    override suspend fun seal(revision: Long, settings: PortableSettings): OperationResult<SnapshotReader> =
        runtime.libraryStore.locked(mutation = true, roots = { requireBuilder(); manifest.toSet() }) { sealLocked(runtime.epoch(), revision, settings) }

    private fun release(owner: Owner) {
        while (true) {
            val old = ownership.load()
            if (old.owner != owner) return
            if (ownership.compareAndSet(old, Ownership(Owner.CLOSED))) {
                old.pins.forEach { it.close() }
                // This directory was minted by this stage, never supplied by a document.
                runtime.cleanup { runtime.fs.deleteRecursively(directory, mustExist = false) }
                return
            }
        }
    }

    override fun close() { release(Owner.BUILDER) }

    internal fun abandon() {
        val old = ownership.exchange(Ownership(Owner.CLOSED))
        old.pins.forEach { it.close() }
        // Quarantine leaves staged files untouched for authoritative reopen, not rollback cleanup.
    }

    private inner class Reader(
        override val epoch: Long,
        override val revision: Long,
        override val settings: PortableSettings,
        override val counts: SnapshotCounts,
        override val lease: MediaLease,
    ) : SnapshotReader {
        private inline fun <reified T> documents(kind: String, count: Int): Flow<T> = flow {
            for (index in 0 until count) {
                storageActive()
                if (ownership.load().owner != Owner.READER) throw SnapshotReadException(Problem(ProblemCode.STALE_REQUEST))
                val value = try {
                    val file = directory / "$kind-$index.json"
                    val size = runtime.fs.metadata(file).size ?: fail(ProblemCode.INVALID_DATA)
                    if (size > 8 * 1024 * 1024) fail(ProblemCode.LIMIT_EXCEEDED)
                    decode<T>(runtime.fs.read(file) { readUtf8() })
                } catch (error: kotlinx.coroutines.CancellationException) { throw error
                } catch (error: StorageFailure) { throw SnapshotReadException(Problem(error.code))
                } catch (error: IllegalArgumentException) { throw SnapshotReadException(Problem(ProblemCode.INVALID_DATA))
                } catch (error: Exception) { throw SnapshotReadException(Problem(ProblemCode.IO_FAILURE)) }
                storageActive()
                emit(value)
            }
            if (ownership.load().owner != Owner.READER) throw SnapshotReadException(Problem(ProblemCode.STALE_REQUEST))
        }.buffer(0).flowOn(Dispatchers.IO)
        override fun boards(): Flow<PublishedBoard> = documents("board", counts.boards)
        override fun drafts(): Flow<StoredDraft> = documents("draft", counts.drafts)
        override fun retained(): Flow<RetainedArchive> = documents("retained", counts.retained)
        override fun assets(): Flow<MediaAsset> = documents("asset", counts.assets)
        override fun close() { release(Owner.READER) }
    }

    companion object {
        internal fun create(runtime: StorageRuntime): SnapshotStage {
            val path = runtime.root / "staging" / "snapshot-${token()}"
            runtime.fs.createDirectory(path, mustCreate = true)
            return SnapshotStage(runtime, path)
        }
    }
}
