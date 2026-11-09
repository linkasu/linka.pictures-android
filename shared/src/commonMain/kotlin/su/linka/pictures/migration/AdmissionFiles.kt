package su.linka.pictures.migration

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.IdSource
import su.linka.pictures.storage.StorageAbort
import su.linka.pictures.storage.storageActive

internal class AdmissionFiles(private val root: Path, private val files: FileSystem, private val ids: IdSource) {
    private val json = Json { encodeDefaults = true }

    fun read(name: String): AdmissionRecord? = readBytes(root / name)?.let {
        try { json.decodeFromString<AdmissionRecord>(it.decodeToString(throwOnInvalidSequence = true)) }
        catch (_: Exception) { reject(ProblemCode.INVALID_DATA) }
    }

    private fun readBytes(path: Path): ByteArray? {
        val metadata = files.metadataOrNull(path) ?: return null
        if (!metadata.isRegularFile || metadata.symlinkTarget != null || (metadata.size ?: Long.MAX_VALUE) > 4096) reject(ProblemCode.INVALID_DATA)
        return files.source(path).buffer().use {
            if (it.request(4097)) reject(ProblemCode.INVALID_DATA)
            it.readByteArray()
        }
    }

    suspend fun write(name: String, record: AdmissionRecord, guard: suspend () -> Unit = {}) {
        val target = root / name
        val before = readBytes(target)
        val value = json.encodeToString(record).encodeToByteArray()
        val token = ids.next()
        if (!safeMigrationId(token)) reject(ProblemCode.INVALID_DATA)
        val stage = root / ".admission-$token"
        var owned = false
        var publishing = false
        try {
            storageActive()
            files.openReadWrite(stage, mustCreate = true).use { handle ->
                owned = true
                handle.sink().buffer().use { it.write(value) }
                handle.flush()
            }
            storageActive()
            guard()
            publishing = true
            files.atomicMove(stage, target)
        } catch (error: Exception) {
            if (!publishing) throw error
            // The suspect write is never its own evidence; perform one independent exact read.
            val observed = try { OperationResult.Completed(readBytes(target)) } catch (_: Exception) { null }
            when {
                observed?.value?.contentEquals(value) == true -> Unit
                observed != null && same(observed.value, before) -> throw error
                else -> throw StorageAbort(OperationResult.UnconfirmedCommit)
            }
        } finally {
            if (owned) try { files.delete(stage, mustExist = false) } catch (_: Exception) { }
        }
    }

    private fun same(a: ByteArray?, b: ByteArray?) = if (a == null) b == null else b != null && a.contentEquals(b)
}

internal fun reject(code: ProblemCode): Nothing = throw StorageAbort(OperationResult.Failed(Problem(code)))
