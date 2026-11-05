package su.linka.pictures.access

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.CommitGuard
import su.linka.pictures.application.ports.IdSource
import su.linka.pictures.text.isNativeText

// Android binding supplies its private noBackupFilesDir child, never a library/user-selected root.
internal class FileCredentialStore(
    private val root: Path,
    private val files: FileSystem,
    private val ids: IdSource,
) : CredentialStore {
    private val writer = Mutex()
    private val target = root / "credential.json"

    override suspend fun read(): OperationResult<ByteArray?> = withContext(Dispatchers.IO) {
        writer.withLock {
            try {
                if (!isNativeText(root.toString())) return@withLock failed(ProblemCode.INVALID_DATA)
                OperationResult.Completed(readRaw())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: InvalidRecord) { failed(ProblemCode.INTEGRITY_FAILURE) }
            catch (_: Exception) { failed(ProblemCode.IO_FAILURE) }
        }
    }

    override suspend fun write(value: ByteArray, guard: CommitGuard): OperationResult<Unit> = withContext(Dispatchers.IO) {
        writer.withLock {
            if (!isNativeText(root.toString()) || value.isEmpty() || value.size > 8192) return@withLock failed(ProblemCode.INVALID_DATA)
            val before = try { readRaw() }
            catch (_: InvalidRecord) { return@withLock failed(ProblemCode.INTEGRITY_FAILURE) }
            catch (_: Exception) { return@withLock failed(ProblemCode.IO_FAILURE) }
            val context = currentCoroutineContext()
            var temporary: Path? = null
            var owned = false
            var publishing = false
            try {
                context.ensureActive()
                when (val allowed = guard()) { is OperationResult.Completed -> Unit; else -> return@withLock allowed }
                val id = ids.next()
                if (id.isEmpty() || id.length > 128 || id.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '-' && it != '_' })
                    return@withLock failed(ProblemCode.INVALID_DATA)
                val staging = root / ".stage-$id"
                temporary = staging
                files.createDirectories(root)
                files.openReadWrite(staging, mustCreate = true).use { handle ->
                    owned = true
                    handle.sink().buffer().use { it.write(value) }
                    handle.flush()
                }
                context.ensureActive()
                when (val allowed = guard()) { is OperationResult.Completed -> Unit; else -> return@withLock allowed }
                publishing = true
                files.atomicMove(staging, target)
                OperationResult.Completed(Unit)
            } catch (failure: Exception) {
                if (publishing) {
                    val observed = try { OperationResult.Completed(readRaw()) } catch (_: Exception) { null }
                    when {
                        observed?.value?.contentEquals(value) == true -> OperationResult.Completed(Unit)
                        observed != null && same(observed.value, before) -> {
                            if (failure is CancellationException) throw failure
                            failed(ProblemCode.IO_FAILURE)
                        }
                        else -> OperationResult.UnconfirmedCommit
                    }
                } else {
                    if (failure is CancellationException) throw failure
                    failed(ProblemCode.IO_FAILURE)
                }
            } finally {
                if (owned && temporary != null) try { files.delete(temporary, mustExist = false) } catch (_: Exception) { }
            }
        }
    }

    private fun readRaw(): ByteArray? {
        val metadata = files.metadataOrNull(target) ?: return null
        if (!metadata.isRegularFile || metadata.symlinkTarget != null || (metadata.size ?: 8193L) > 8192) throw InvalidRecord()
        return files.source(target).buffer().use { source ->
            if (source.request(8193)) throw InvalidRecord()
            source.readByteArray()
        }
    }

    private class InvalidRecord : Exception()
    private fun same(left: ByteArray?, right: ByteArray?): Boolean = if (left == null) right == null else right != null && left.contentEquals(right)
    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))
}
