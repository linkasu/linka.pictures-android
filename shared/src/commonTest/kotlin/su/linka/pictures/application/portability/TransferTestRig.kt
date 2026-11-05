@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package su.linka.pictures.application.portability

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.*
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.integration.CoreBackupFiles
import su.linka.pictures.integration.SyntheticCoreInspection
import su.linka.pictures.portability.backup.BackupCodec
import su.linka.pictures.portability.linka.LinkaCodec
import su.linka.pictures.storage.StorageRuntime
import kotlin.test.*
import kotlin.uuid.Uuid

internal fun <T> OperationResult<T>.ok(): T = assertIs<OperationResult.Completed<T>>(this).value
internal fun OperationResult<*>.problem(code: ProblemCode) = assertEquals(code, assertIs<OperationResult.Failed>(this).problem.code)

// Actual SQLite, FS and both codecs; picker, destination and inspection are contract fakes, NOT native proof.
internal class TransferTestRig private constructor(val root: Path, val runtime: StorageRuntime, val privateFiles: CoreBackupFiles) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val capability = AssistantCapability("test-only", "owner")
    var permitted = true
    var guards = 0
    var checks = 0
    var writes = 0
    var closes = 0
    var input = byteArrayOf()
    var external = byteArrayOf()
    var choose: suspend () -> Unit = {}
    var pick: suspend () -> Unit = {}
    var chunk: suspend () -> Unit = {}
    var writeResult: OperationResult<Unit>? = null
    val gate = object : AssistantGate {
        override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): OperationResult<Unit> {
            checks++
            return if (permitted && capability === this@TransferTestRig.capability && ownerId == "owner") OperationResult.Completed(Unit)
                else OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
        }
        override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard {
            guards++
            return { check(action, capability, ownerId) }
        }
    }
    val files = object : PlatformFilesPort by privateFiles {
        override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> {
            pick()
            val file = privateFiles.createTemporaryFile("picked", "application/zip").ok()
            privateFiles.sink(file).ok().buffer().use { it.write(input) }
            return OperationResult.Completed(file)
        }
        override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> {
            assertTrue(suggestedName == "board.linka" || suggestedName == "library.linkabackup")
            assertEquals("application/zip", mediaType)
            choose()
            return OperationResult.Completed(object : ExportDestination {
                override val ownerId = ownerId
                override suspend fun write(file: LocalFile, guard: CommitGuard): OperationResult<Unit> {
                    writes++
                    writeResult?.let { return it }
                    val data = Buffer()
                    privateFiles.source(file).ok().use { source ->
                        while (true) {
                            val allowed = guard()
                            if (allowed !is OperationResult.Completed) return allowed
                            val n = source.read(data, 32)
                            if (n == -1L) break
                            external += data.readByteArray()
                            chunk()
                        }
                    }
                    return OperationResult.Completed(Unit)
                }
                override fun close() { closes++ }
            })
        }
    }
    val inspection = SyntheticCoreInspection(FileSystem.SYSTEM, root)
    val ids = IdSource { Uuid.random().toString() }
    val linka = LinkaCodec(runtime.media, files, inspection, ids)
    val backup = BackupCodec(runtime.media, files, inspection, runtime.library)
    fun coordinator(library: LibraryStore = runtime.library) = TransferCoordinator(library, runtime.messages, linka, backup, files, gate, ids, scope)
    fun permit() = WritePermit(runtime.library.listing.value.epoch) { OperationResult.Completed(Unit) }
    fun board(id: String = "board", name: String = "same name") = BoardDocument(BoardId(id), name,
        listOf(BoardPage(PageId("page"), 1, 1, listOf(BoardCell(0, CellKind.CONTENT, CardDocument(CardId("card"), "hello"))))))
    suspend fun add(id: String = "board") = runtime.library.addBoard(board(id), permit()).ok()
    suspend fun linkaInput() {
        val board = runtime.library.board(BoardId("board")).ok()
        try {
            val file = linka.write(board.board, board.lease).ok()
            try { input = files.source(file).ok().buffer().use { it.readByteArray() } }
            finally { files.discard(file) }
        } finally { board.lease.close() }
    }
    suspend fun backupInput() {
        val snapshot = runtime.library.snapshot().ok()
        try {
            val file = backup.write(snapshot).ok()
            try { input = files.source(file).ok().buffer().use { it.readByteArray() } }
            finally { files.discard(file) }
        } finally { snapshot.close() }
    }
    suspend fun clean() {
        scope.cancel()
        scope.coroutineContext[Job]!!.join()
        runtime.close()
        privateFiles.close()
        FileSystem.SYSTEM.deleteRecursively(root)
    }
    companion object {
        suspend fun fresh(): TransferTestRig {
            val fs = FileSystem.SYSTEM
            val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "linka-p12-${Uuid.random()}"
            fs.createDirectory(root, mustCreate = true)
            fs.createDirectory(root / "files", mustCreate = true)
            return TransferTestRig(root, StorageRuntime.open(root / "storage", fs, BundledSQLiteDriver()).ok(), CoreBackupFiles(fs, root / "files"))
        }
    }
}
