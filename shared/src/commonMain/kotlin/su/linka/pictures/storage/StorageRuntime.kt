@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package su.linka.pictures.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal class StorageAbort(val result: OperationResult<Nothing>) : Exception()
internal fun <T> OperationResult<T>.required(): T = when (this) {
    is OperationResult.Completed -> value
    is OperationResult.Failed -> throw StorageAbort(this)
    OperationResult.Cancelled -> throw StorageAbort(OperationResult.Cancelled)
    OperationResult.UnconfirmedCommit -> throw StorageAbort(OperationResult.UnconfirmedCommit)
}

class StorageRuntime private constructor(
    internal val root: Path,
    internal val fs: FileSystem,
    db: SQLiteConnection,
    internal val driver: SQLiteDriver,
) {
    internal val writer = Mutex()
    private var connection: SQLiteConnection? = db
    private var reopenWriter = false
    private val retired = mutableListOf<SQLiteConnection>()
    private val quarantine = AtomicReference(false)
    private val uncertainOwners = AtomicReference<List<() -> Unit>>(emptyList())
    internal val quarantined: Boolean get() = quarantine.load()
    internal val db: SQLiteConnection get() {
        connection?.let { return it }
        if (!reopenWriter || quarantined) throw StorageAbort(OperationResult.UnconfirmedCommit)
        if (!fs.exists(root / "library.sqlite3")) fail(ProblemCode.IO_FAILURE)
        val fresh = driver.open((root / "library.sqlite3").toString())
        try {
            fresh.exec("PRAGMA foreign_keys=ON")
            fresh.exec("PRAGMA synchronous=FULL")
        } catch (error: Throwable) {
            try { fresh.close() } catch (closeError: Throwable) { retired += fresh }
            throw error
        }
        connection = fresh
        reopenWriter = false
        return fresh
    }
    private data class Activity(val closing: Boolean = false, val count: Int = 0)
    private val activity = AtomicReference(Activity())
    private val drained = CompletableDeferred<Unit>()
    internal val accepting: Boolean get() = !activity.load().closing
    internal val mediaStore = StoredMedia(this)
    internal val libraryStore = StoredLibrary(this)
    val library: LibraryStore get() = libraryStore
    val messages: MessageStore = StoredMessages(this)
    val media: MediaStore get() = mediaStore

    internal fun available(mutation: Boolean = false) {
        if (quarantined) {
            if (mutation) throw StorageAbort(OperationResult.UnconfirmedCommit)
            throw StorageAbort(OperationResult.Failed(Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)))
        }
    }

    private fun accept(): Boolean {
        while (true) {
            val old = activity.load()
            if (old.closing) return false
            if (activity.compareAndSet(old, old.copy(count = old.count + 1))) return true
        }
    }

    private fun finish() {
        while (true) {
            val old = activity.load()
            val next = old.copy(count = old.count - 1)
            if (activity.compareAndSet(old, next)) {
                if (next.closing && next.count == 0) drained.complete(Unit)
                return
            }
        }
    }

    internal fun cleanup(block: () -> Unit) {
        if (quarantined || !accept()) return
        storageCleanup({ if (!quarantined) block() }, ::finish)
    }

    internal suspend fun <T> operation(mutation: Boolean = false, cleanup: () -> Unit = {}, block: suspend () -> T): OperationResult<T> {
        if (!accept()) return OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
        return try {
            storageWorker {
                try { attempt { available(mutation); storageActive(); block() } } finally { cleanup() }
            }
        } finally { finish() }
    }

    suspend fun close() {
        while (true) {
            val old = activity.load()
            if (old.closing) break
            if (activity.compareAndSet(old, old.copy(closing = true))) {
                if (old.count == 0) drained.complete(Unit)
                break
            }
        }
        mediaStore.stopRetains()
        // Once closing is accepted, cancellation must not strand DB handles.
        storageWorker {
            drained.await()
            writer.withLock {
                val handles = listOfNotNull(connection) + retired
                connection = null
                retired.clear()
                var failure: Throwable? = null
                for (handle in handles) {
                    try { handle.close() } catch (error: Throwable) { retired += handle; failure = error }
                }
                // A later explicit close may retry failed handles, but never a mutation or reconciliation.
                failure?.let { throw it }
                uncertainOwners.exchange(emptyList()).forEach { it() }
            }
        }
    }

    internal fun epoch(): Long = db.number("SELECT epoch FROM library_meta WHERE singleton=1")
    internal fun revision(): Long = db.number("SELECT revision FROM library_meta WHERE singleton=1")
    internal fun next(value: Long): Long = if (value == Long.MAX_VALUE) fail(ProblemCode.LIMIT_EXCEEDED) else value + 1

    internal suspend fun <T> transaction(
        permit: WritePermit?,
        block: () -> T,
    ): T {
        available(mutation = true)
        storageActive()
        val original = db
        val old = original.fingerprint()
        var committing = false
        var new: String? = null
        var value: T? = null
        try {
            original.exec("BEGIN IMMEDIATE")
            if (permit != null) {
                if (permit.epoch != epoch()) fail(ProblemCode.STALE_REQUEST)
                permit.check().required()
            }
            value = block()
            new = original.fingerprint()
            storageActive()
            permit?.check?.invoke()?.required()
            storageActive()
            committing = true
            original.exec("COMMIT")
        } catch (error: Throwable) {
            val rolledBack = runCatching { original.exec("ROLLBACK") }.isSuccess
            if (!committing && rolledBack) throw error
            // Never inspect pending state on the suspect writer, including after a failed rollback.
            when (reconcile(original, old, new)) {
                true -> Unit
                false -> throw error
                null -> throw StorageAbort(OperationResult.UnconfirmedCommit)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun reconcile(original: SQLiteConnection, old: String, new: String?): Boolean? {
        connection = null
        try { original.close() } catch (error: Throwable) {
            retired += original
            enterQuarantine()
            return null
        }
        var observer: SQLiteConnection? = null
        try {
            if (!fs.exists(root / "library.sqlite3")) { enterQuarantine(); return null }
            observer = driver.open((root / "library.sqlite3").toString())
            observer.exec("PRAGMA busy_timeout=1000")
            observer.exec("PRAGMA query_only=ON")
            observer.exec("BEGIN")
            val actual = observer.fingerprint()
            val committed = when (actual) {
                new -> true
                old -> false
                else -> { enterQuarantine(); return null }
            }
            // Reconciliation stays read-only; the next normal DB access opens a fresh writer.
            // Observer cleanup cannot revoke an already observed exact durable proof.
            reopenWriter = true
            return committed
        } catch (error: Throwable) {
            enterQuarantine()
            return null
        } finally {
            observer?.let { handle -> try { handle.close() } catch (error: Throwable) { retired += handle } }
        }
    }

    private fun enterQuarantine() {
        quarantine.store(true)
        libraryStore.unconfirmed()
    }

    internal fun holdUncertain(releasePins: () -> Unit) {
        while (true) {
            val old = uncertainOwners.load()
            if (uncertainOwners.compareAndSet(old, old + releasePins)) return
        }
    }

    companion object {
        suspend fun open(root: Path, fileSystem: FileSystem, driver: SQLiteDriver): OperationResult<StorageRuntime> = storageWorker { attempt {
            storageActive()
            require(root.isAbsolute)
            val existing = fileSystem.metadataOrNull(root)
            if (existing != null && (!existing.isDirectory || existing.symlinkTarget != null)) fail(ProblemCode.INVALID_DATA)
            fileSystem.createDirectories(root)
            // Never claim an unrelated/legacy directory as a fresh storage root.
            val names = setOf("library.sqlite3", "library.sqlite3-wal", "library.sqlite3-shm", "library.sqlite3-journal", "media", "staging")
            val originalNames = fileSystem.list(root).map { it.name }.toSet()
            for (name in names) {
                if (fileSystem.metadataOrNull(root / name)?.symlinkTarget != null) fail(ProblemCode.INVALID_DATA)
            }
            if (!fileSystem.exists(root / "library.sqlite3") && fileSystem.list(root).isNotEmpty()) fail(ProblemCode.INVALID_DATA)
            fun emptySchemaRoot() {
                if (originalNames.any { it !in names }) fail(ProblemCode.INVALID_DATA)
                for (name in listOf("media", "staging")) {
                    val metadata = fileSystem.metadataOrNull(root / name) ?: continue
                    if (!metadata.isDirectory || metadata.symlinkTarget != null || fileSystem.list(root / name).isNotEmpty()) fail(ProblemCode.INVALID_DATA)
                }
            }
            val accepted = if (fileSystem.exists(root / "library.sqlite3")) {
                InitialSchemaProbe.read(root, fileSystem, driver) { probe, version ->
                    if (version == 0L) emptySchemaRoot()
                    // Invariant validation borrows the connection; InitialSchemaProbe owns its close.
                    else StorageRuntime(root, fileSystem, probe, driver).mediaStore.validatePersistentRoots()
                }
            } else 0L
            val db = driver.open((root / "library.sqlite3").toString())
            var runtime: StorageRuntime? = null
            try {
                val version = db.readSchema()
                if (version != accepted) fail(ProblemCode.CONFLICT)
                if (version == 0L) emptySchemaRoot()
                db.exec("PRAGMA foreign_keys=ON")
                db.exec("PRAGMA journal_mode=WAL")
                db.exec("PRAGMA synchronous=FULL")
                val opened = StorageRuntime(root, fileSystem, db, driver)
                runtime = opened
                if (version == 0L) {
                    opened.transaction(null) {
                        schemaStatements.forEach { db.exec(it) }
                        db.exec("INSERT INTO library_meta VALUES(1,0,0,?)", encode(PortableSettings()))
                        db.exec("INSERT INTO message VALUES(1,0,0,?)", encode(MessageDraft()))
                        db.exec("PRAGMA user_version=1")
                    }
                }
                fileSystem.createDirectories(root / "media")
                fileSystem.createDirectories(root / "staging")
                opened.also { runtime ->
                    runtime.mediaStore.validatePersistentRoots(hashFiles = accepted == 0L)
                    runtime.libraryStore.refresh()
                    runtime.cleanupStaging()
                }
            } catch (error: Throwable) {
                runCatching { if (runtime != null) runtime.close() else db.close() }
                throw error
            }
        } }
    }

    private fun cleanupStaging() {
        val temporary = Regex("(?:media-[0-9a-f]{32}\\.tmp|replacement-[0-9a-f]{32}\\.sqlite3(?:-journal)?)")
        val snapshots = Regex("snapshot-[0-9a-f]{32}")
        val document = Regex("(?:board|draft|retained|asset)-[0-9]+\\.json")
        val gcIntent = Regex("gc-([0-9a-f]{64})\\.json")
        for (path in runCatching { fs.list(root / "staging") }.getOrDefault(emptyList())) runCatching {
            val metadata = fs.metadata(path)
            if (metadata.symlinkTarget != null) return@runCatching
            if (metadata.isRegularFile && temporary.matches(path.name)) fs.delete(path)
            val match = gcIntent.matchEntire(path.name)
            if (metadata.isRegularFile && match != null && (metadata.size ?: Long.MAX_VALUE) <= 2048) {
                val id = AssetId(match.groupValues[1])
                if (decode<MediaAsset>(fs.read(path) { readUtf8() }).id == id && db.number("SELECT count(*) FROM assets WHERE id=?", id.value) == 0L && !fs.exists(mediaStore.path(id))) fs.delete(path)
            }
            if (metadata.isDirectory && snapshots.matches(path.name)) {
                val files = fs.list(path)
                if (files.all { document.matches(it.name) && fs.metadata(it).isRegularFile && fs.metadata(it).symlinkTarget == null }) {
                    files.forEach { fs.delete(it) }
                    fs.delete(path)
                }
            }
        }
    }
}

internal suspend fun <T> attempt(block: suspend () -> T): OperationResult<T> = try {
    OperationResult.Completed(block())
} catch (error: CancellationException) {
    throw error
} catch (error: StorageAbort) {
    error.result
} catch (error: StorageFailure) {
    OperationResult.Failed(Problem(error.code))
} catch (error: SnapshotReadException) {
    OperationResult.Failed(error.problem)
} catch (error: IllegalArgumentException) {
    OperationResult.Failed(Problem(ProblemCode.INVALID_DATA))
} catch (error: Exception) {
    OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
}
