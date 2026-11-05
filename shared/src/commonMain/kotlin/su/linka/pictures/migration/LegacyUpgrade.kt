@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package su.linka.pictures.migration

import androidx.sqlite.SQLiteDriver
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.portability.PreparedBoard
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.storage.*

// Root owns source readers, credential callbacks, the single coordinator and the returned runtime.
internal class LegacyUpgrade(
    private val root: Path,
    private val legacySets: Path,
    private val files: FileSystem,
    private val driver: SQLiteDriver,
    private val createFiles: (Path) -> PlatformFilesPort,
    @Suppress("UNUSED_PARAMETER") inspection: MediaInspectionPort,
    private val ids: IdSource,
    private val readSettings: suspend () -> LegacyUpgradeSettings,
    private val prepareCredentials: suspend () -> OperationResult<Unit>,
    private val validateCredentials: suspend () -> OperationResult<Unit>,
    private val readBoard: suspend (LocalFile, String, MediaStore, PlatformFilesPort) -> OperationResult<PreparedBoard>,
) {
    private val writer = Mutex()
    private val admission = AdmissionFiles(root, files, ids)
    private val mutableState = MutableStateFlow(MigrationReport())
    val state: StateFlow<MigrationReport> = mutableState.asStateFlow()
    private var frozen = false

    suspend fun open(resumeInterrupted: Boolean = false): OperationResult<LegacyOpenedLibrary> = storageWorker {
        writer.withLock {
            if (frozen) return@withLock OperationResult.UnconfirmedCommit
            var stage: StorageRuntime? = null
            var record: AdmissionRecord? = null
            var activated = false
            val live = AtomicReference(true)
            try {
                val result = attempt {
                    checkRoots()
                    // Active is authoritative even when the auxiliary journal is stale/corrupt.
                    admission.read("active")?.let { return@attempt openActive(it) }
                    storageActive()
                    checkUnclaimedRoot(resumeInterrupted)
                    val initial = inventory(files, legacySets, readSettings())
                    val previous = admission.read("journal")
                    val journal = if (previous != null) {
                        if (!resumeInterrupted) reject(ProblemCode.INTERRUPTED)
                        if (previous.sourceDigest != initial.digest || previous.total != initial.entries.size || previous.phase == MigrationPhase.COMMITTED) reject(ProblemCode.CONFLICT)
                        checkGeneration(previous.migrationId, requireDatabase = true)
                        previous
                    } else {
                        if (files.exists(root / "libraries") && files.list(root / "libraries").isNotEmpty()) reject(ProblemCode.CONFLICT)
                        val id = ids.next()
                        if (!safeMigrationId(id)) reject(ProblemCode.INVALID_DATA)
                        val fresh = AdmissionRecord(migrationId = id, sourceDigest = initial.digest, phase = MigrationPhase.INVENTORY, total = initial.entries.size)
                        admission.write("journal", fresh) { if (files.exists(root / "journal") || files.exists(root / "active")) reject(ProblemCode.CONFLICT) }
                        files.createDirectories(root / "libraries")
                        files.createDirectory(root / "libraries" / id, mustCreate = true)
                        fresh
                    }
                    record = journal
                    val generation = root / "libraries" / journal.migrationId
                    val runtime = StorageRuntime.open(generation, files, driver).required()
                    stage = runtime
                    val nativeFiles = createFiles(generation)
                    val expected = initial.entries.indices.map { stableId(journal.migrationId, it) }.toSet()
                    val listing = runtime.library.listing.value
                    listing.refreshProblem?.let { throw StorageAbort(OperationResult.Failed(it)) }
                    if (listing.items.any { it.id !in expected } || runtime.library.drafts().required().isNotEmpty()) reject(ProblemCode.CONFLICT)
                    record = journal.copy(phase = MigrationPhase.COPYING_VALIDATING,
                        imported = listing.items.count { it.kind == LibraryEntryKind.BOARD },
                        retained = listing.items.count { it.kind == LibraryEntryKind.RETAINED_ARCHIVE })
                    admission.write("journal", record!!)
                    update(record!!)
                    val caller = currentCoroutineContext()
                    val permit = WritePermit(listing.epoch) {
                        caller.ensureStorageActive()
                        try {
                            migrationGuard(live, journal.migrationId, initial.digest, MigrationPhase.COPYING_VALIDATING)
                            OperationResult.Completed(Unit)
                        } catch (abort: StorageAbort) { abort.result }
                    }
                    for ((index, entry) in initial.entries.withIndex()) {
                        storageActive()
                        val id = stableId(journal.migrationId, index)
                        if (listing.items.any { it.id == id }) continue
                        importEntry(entry, id, initial.settings.boardGrid[entry.path.name] ?: 3, runtime, nativeFiles, permit)
                        val actual = runtime.library.listing.value
                        actual.refreshProblem?.let { throw StorageAbort(OperationResult.Failed(it)) }
                        record = record!!.copy(imported = actual.items.count { it.kind == LibraryEntryKind.BOARD }, retained = actual.items.count { it.kind == LibraryEntryKind.RETAINED_ARCHIVE })
                        admission.write("journal", record!!)
                        update(record!!)
                    }
                    val desired = PortableSettings(boards = initial.entries.mapIndexed { index, entry ->
                        val value = initial.settings.boardGrid[entry.path.name] ?: 3
                        stableId(journal.migrationId, index) to BoardPresentation(value == 2 || value == 3)
                    }.toMap())
                    if (runtime.library.settings().required() != desired) {
                        runtime.library.saveSettings(desired, runtime.library.listing.value.revision, permit).required()
                    }
                    val report = validateStage(runtime, record!!)
                    // A killed/unknown credential callback must never be implicitly retried on startup.
                    record = record!!.copy(phase = MigrationPhase.UNCONFIRMED)
                    admission.write("journal", record!!)
                    prepareCredentials().required()
                    storageActive()
                    validateCredentials().required()
                    record = record!!.copy(phase = MigrationPhase.ACCESS_READY, credentialsInitialized = true)
                    admission.write("journal", record!!)
                    runtime.close()
                    stage = null
                    record = record!!.copy(phase = MigrationPhase.READY_TO_PUBLISH)
                    admission.write("journal", record!!)
                    val committed = record!!.copy(phase = MigrationPhase.COMMITTED)
                    admission.write("active", committed) {
                        migrationGuard(live, journal.migrationId, initial.digest, MigrationPhase.READY_TO_PUBLISH)
                        validateCredentials().required()
                        if (inventory(files, legacySets, readSettings()).digest != initial.digest) reject(ProblemCode.INTEGRITY_FAILURE)
                        storageActive()
                    }
                    activated = true
                    update(committed)
                    live.store(false)
                    // A late cancellation or progress failure cannot revoke confirmed activation.
                    withContext(NonCancellable) {
                        CoroutineScope(Dispatchers.IO + NonCancellable).async {
                            try { admission.write("journal", committed) } catch (_: Exception) { }
                            openActive(committed, report.detailProblems)
                        }.await()
                    }
                }
                when (result) {
                    is OperationResult.Completed -> mutableState.value = result.value.report
                    is OperationResult.Failed -> {
                        mutableState.value = mutableState.value.copy(phase = if (activated) MigrationPhase.COMMITTED else MigrationPhase.BLOCKED, error = result.problem)
                    }
                    OperationResult.UnconfirmedCommit -> {
                        frozen = true
                        mutableState.value = mutableState.value.copy(phase = MigrationPhase.UNCONFIRMED, error = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
                    }
                    OperationResult.Cancelled -> mutableState.value = mutableState.value.copy(phase = MigrationPhase.CANCELLED)
                }
                result
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(phase = MigrationPhase.CANCELLED, error = Problem(ProblemCode.INTERRUPTED))
                throw cancelled
            } finally {
                live.store(false)
                stage?.let { try { it.close() } catch (_: Exception) { frozen = true } }
            }
        }
    }

    private fun checkRoots() {
        require(root.isAbsolute && legacySets.isAbsolute)
        val a = root.normalized().toString().trimEnd('/') + "/"
        val b = legacySets.normalized().toString().trimEnd('/') + "/"
        if (a.startsWith(b) || b.startsWith(a)) reject(ProblemCode.INVALID_DATA)
        val metadata = files.metadataOrNull(root)
        if (metadata != null && (!metadata.isDirectory || metadata.symlinkTarget != null || files.canonicalize(root) != root.normalized())) reject(ProblemCode.INVALID_DATA)
        if (metadata == null) {
            var ancestor = root.parent ?: reject(ProblemCode.INVALID_DATA)
            while (files.metadataOrNull(ancestor) == null) ancestor = ancestor.parent ?: reject(ProblemCode.INVALID_DATA)
            val parent = files.metadata(ancestor)
            if (!parent.isDirectory || parent.symlinkTarget != null || files.canonicalize(ancestor) != ancestor.normalized()) reject(ProblemCode.INVALID_DATA)
            files.createDirectories(root)
            if (files.canonicalize(root) != root.normalized()) reject(ProblemCode.INVALID_DATA)
        }
    }

    private fun checkUnclaimedRoot(resume: Boolean) {
        val journal = admission.read("journal")
        for (entry in files.list(root)) {
            if (entry.name in setOf("active", "journal", "libraries")) continue
            if (!entry.name.startsWith(".admission-") || !safeMigrationId(entry.name.removePrefix(".admission-"))) reject(ProblemCode.CONFLICT)
            val pending = admission.read(entry.name) ?: reject(ProblemCode.CONFLICT)
            if (journal == null || pending.migrationId != journal.migrationId || pending.sourceDigest != journal.sourceDigest || pending.total != journal.total || pending.phase == MigrationPhase.COMMITTED) reject(ProblemCode.CONFLICT)
            if (!resume) reject(ProblemCode.INTERRUPTED)
            // Proven owned leftovers are preserved, never promoted over the authoritative reopened DB.
        }
        files.metadataOrNull(root / "libraries")?.let { if (!it.isDirectory || it.symlinkTarget != null) reject(ProblemCode.INVALID_DATA) }
        val owner = journal?.migrationId
        if (files.exists(root / "libraries") && files.list(root / "libraries").any { it.name != owner }) reject(ProblemCode.CONFLICT)
    }

    private fun checkGeneration(id: String, requireDatabase: Boolean) {
        if (!safeMigrationId(id)) reject(ProblemCode.INVALID_DATA)
        val generation = root / "libraries" / id
        for (path in listOf(root / "libraries", generation)) {
            val metadata = files.metadataOrNull(path) ?: reject(ProblemCode.INTEGRITY_FAILURE)
            if (!metadata.isDirectory || metadata.symlinkTarget != null || files.canonicalize(path) != path.normalized()) reject(ProblemCode.INVALID_DATA)
        }
        if (requireDatabase && !files.exists(generation / "library.sqlite3")) reject(ProblemCode.INTEGRITY_FAILURE)
    }

    private suspend fun openActive(active: AdmissionRecord, details: Map<BoardId, Problem> = emptyMap()): LegacyOpenedLibrary {
        if (active.phase != MigrationPhase.COMMITTED || !active.credentialsInitialized || active.imported + active.retained != active.total) reject(ProblemCode.INVALID_DATA)
        checkGeneration(active.migrationId, requireDatabase = true)
        validateCredentials().required()
        val generation = root / "libraries" / active.migrationId
        val runtime = StorageRuntime.open(generation, files, driver).required()
        try {
            val problems = retainedProblems(runtime) + details
            return LegacyOpenedLibrary(runtime, createFiles(generation), MigrationReport(MigrationPhase.COMMITTED, active.total, active.imported, active.retained, problems))
        } catch (error: Throwable) { runtime.close(); throw error }
    }

    private fun migrationGuard(live: AtomicReference<Boolean>, id: String, digest: String, phase: MigrationPhase) {
        if (!live.load() || files.exists(root / "active")) reject(ProblemCode.STALE_REQUEST)
        val current = admission.read("journal") ?: reject(ProblemCode.STALE_REQUEST)
        if (current.migrationId != id || current.sourceDigest != digest || current.phase != phase) reject(ProblemCode.STALE_REQUEST)
        checkGeneration(id, requireDatabase = true)
    }

    private suspend fun importEntry(entry: LegacyEntry, id: BoardId, grid: Int, runtime: StorageRuntime, nativeFiles: PlatformFilesPort, permit: WritePermit) {
        val temporary = nativeFiles.createTemporaryFile("legacy.linka", "application/zip").required()
        try {
            val hash = HashingSource.sha256(files.source(entry.path))
            var count = 0L
            hash.use { source ->
                nativeFiles.sink(temporary).required().buffer().use { sink ->
                    val buffer = Buffer()
                    while (true) {
                        storageActive()
                        val read = source.read(buffer, 65536)
                        if (read == -1L) break
                        count += read
                        if (count > entry.size) reject(ProblemCode.INTEGRITY_FAILURE)
                        sink.write(buffer, read)
                    }
                }
            }
            if (count != entry.size || hash.hash.hex() != entry.hash) reject(ProblemCode.INTEGRITY_FAILURE)
            when (val decoded = readBoard(temporary, entry.path.name, runtime.media, nativeFiles)) {
                is OperationResult.Completed -> decoded.value.use { prepared ->
                    val behavior = prepared.document.behavior.copy(defaultMode = if (grid == 1 || grid == 3) InteractionMode.COMPOSE else InteractionMode.DIRECT)
                    runtime.library.addBoard(prepared.document.copy(id = id, behavior = behavior), permit).required()
                }
                is OperationResult.Failed -> {
                    val reason = when (decoded.problem.code) {
                        ProblemCode.UNSUPPORTED_VERSION -> RetainedReason.UNSUPPORTED_VERSION
                        ProblemCode.UNSUPPORTED_MEDIA, ProblemCode.UNSUPPORTED_FEATURE, ProblemCode.UNSUPPORTED_MODE -> RetainedReason.UNSUPPORTED_MEDIA
                        ProblemCode.INVALID_DATA, ProblemCode.UNSAFE_ARCHIVE, ProblemCode.INTEGRITY_FAILURE, ProblemCode.MISSING_ASSET, ProblemCode.LIMIT_EXCEEDED -> RetainedReason.INVALID_ARCHIVE
                        else -> throw StorageAbort(decoded)
                    }
                    val archive = runtime.media.prepare(nativeFiles.source(temporary).required(), MediaKind.ARCHIVE, "application/octet-stream").required()
                    archive.lease.use {
                        if (archive.asset.id.value != entry.hash || archive.asset.byteCount != entry.size) reject(ProblemCode.INTEGRITY_FAILURE)
                        runtime.library.retainArchive(RetainedArchive(id, entry.path.name, 1, archive.asset.id, reason), permit).required()
                    }
                    mutableState.value = mutableState.value.copy(detailProblems = mutableState.value.detailProblems + (id to decoded.problem))
                }
                OperationResult.Cancelled -> throw StorageAbort(OperationResult.Cancelled)
                OperationResult.UnconfirmedCommit -> throw StorageAbort(OperationResult.UnconfirmedCommit)
            }
        } finally { try { nativeFiles.discard(temporary) } catch (_: Exception) { } }
    }

    private suspend fun validateStage(runtime: StorageRuntime, record: AdmissionRecord): MigrationReport {
        val snapshot = runtime.library.snapshot().required()
        snapshot.use {
            if (it.counts.boards != record.imported || it.counts.retained != record.retained || it.counts.drafts != 0 || record.imported + record.retained != record.total) reject(ProblemCode.INTEGRITY_FAILURE)
            it.assets().collect { asset ->
                val hash = HashingSource.sha256(runtime.media.source(asset.id, it.lease).required())
                var size = 0L
                hash.use { source ->
                    val buffer = Buffer()
                    while (true) {
                        storageActive()
                        val read = source.read(buffer, 65536)
                        if (read == -1L) break
                        size += read
                        if (size > asset.byteCount) reject(ProblemCode.INTEGRITY_FAILURE)
                        buffer.clear()
                    }
                }
                if (size != asset.byteCount || hash.hash.hex() != asset.id.value) reject(ProblemCode.INTEGRITY_FAILURE)
            }
        }
        return mutableState.value.copy(detailProblems = retainedProblems(runtime) + mutableState.value.detailProblems)
    }

    private suspend fun retainedProblems(runtime: StorageRuntime): Map<BoardId, Problem> {
        val problems = mutableMapOf<BoardId, Problem>()
        runtime.library.snapshot().required().use { snapshot ->
            snapshot.retained().collect { archive ->
                problems[archive.id] = Problem(when (archive.reason) {
                    RetainedReason.INVALID_ARCHIVE -> ProblemCode.INVALID_DATA
                    RetainedReason.UNSUPPORTED_VERSION -> ProblemCode.UNSUPPORTED_VERSION
                    RetainedReason.UNSUPPORTED_MEDIA -> ProblemCode.UNSUPPORTED_MEDIA
                    RetainedReason.IO_FAILURE -> ProblemCode.IO_FAILURE
                })
            }
        }
        return problems
    }

    private fun update(record: AdmissionRecord) {
        mutableState.value = mutableState.value.copy(phase = record.phase, total = record.total, imported = record.imported, retained = record.retained, error = null)
    }

    private fun stableId(id: String, index: Int) = BoardId("legacy-$id-$index")
}
