@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package su.linka.pictures.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import kotlin.uuid.Uuid
import kotlinx.coroutines.currentCoroutineContext
import okio.*
import su.linka.pictures.application.ProblemCode

internal object InitialSchemaProbe {
    private val suffixes = listOf("", "-wal", "-journal", "-shm")
    private data class FileState(val size: Long, val modified: Long?, val hash: String)

    // shortcut: startup streams metadata DB/sidecars repeatedly; use a flags-capable
    // read-only binding only after measured startup cost, preserving the same source invariants.
    suspend fun read(root: Path, fs: FileSystem, driver: SQLiteDriver, validate: (SQLiteConnection, Long) -> Unit): Long {
        val context = currentCoroutineContext()
        val directory = root / "probe-${Uuid.random()}"
        val originalNames = fs.list(root).map { it.name }.toSet()

        fun state(initial: Boolean = false): Map<String, FileState?> = suffixes.associateWith { suffix ->
            context.ensureStorageActive()
            val path = root / "library.sqlite3$suffix"
            val before = fs.metadataOrNull(path) ?: return@associateWith null
            val size = before.size
            if (!before.isRegularFile || before.symlinkTarget != null || size == null || size < 0) fail(if (initial) ProblemCode.INVALID_DATA else ProblemCode.CONFLICT)
            val source = HashingSource.sha256(fs.source(path))
            source.use {
                val buffer = Buffer()
                var count = 0L
                while (true) {
                    context.ensureStorageActive()
                    val read = it.read(buffer, 8192)
                    if (read == -1L) break
                    if (read > size - count) fail(ProblemCode.CONFLICT)
                    count += read
                    buffer.clear()
                }
                if (count != size) fail(ProblemCode.CONFLICT)
            }
            val after = fs.metadataOrNull(path) ?: fail(ProblemCode.CONFLICT)
            if (!after.isRegularFile || after.symlinkTarget != null || after.size != size || after.lastModifiedAtMillis != before.lastModifiedAtMillis) fail(ProblemCode.CONFLICT)
            FileState(size, before.lastModifiedAtMillis, source.hash.hex())
        }

        val original = state(initial = true)
        if (original[""] == null) fail(ProblemCode.CONFLICT)
        fun stable() {
            if (state() != original || fs.list(root).filter { it != directory }.map { it.name }.toSet() != originalNames) fail(ProblemCode.CONFLICT)
        }

        fs.createDirectory(directory, mustCreate = true)
        var failure: Throwable? = null
        val version = try {
            for (suffix in suffixes.filter { it != "-shm" }) {
                val expected = original[suffix] ?: continue
                val source = HashingSource.sha256(fs.source(root / "library.sqlite3$suffix"))
                source.use { input ->
                    fs.openReadWrite(directory / "library.sqlite3$suffix", mustCreate = true).use { handle ->
                        handle.sink().buffer().use { output ->
                            val buffer = Buffer()
                            var count = 0L
                            while (true) {
                                context.ensureStorageActive()
                                val read = input.read(buffer, 8192)
                                if (read == -1L) break
                                if (read > expected.size - count) fail(ProblemCode.CONFLICT)
                                count += read
                                output.write(buffer, read)
                            }
                            if (count != expected.size) fail(ProblemCode.CONFLICT)
                        }
                        handle.flush()
                    }
                }
                if (source.hash.hex() != expected.hash) fail(ProblemCode.CONFLICT)
            }
            stable()
            val accepted = try {
                driver.open((directory / "library.sqlite3").toString()).use { probe ->
                    val version = probe.readSchema()
                    if (probe.rows("PRAGMA quick_check") != listOf(listOf("ok"))) fail(ProblemCode.INVALID_DATA)
                    validate(probe, version)
                    version
                }
            // 2.5.2 exposes only exception text, not a structured result code; do not
            // misdiagnose native IO errors as invalid user data or parse private message formats.
            } catch (error: SQLiteException) { fail(ProblemCode.IO_FAILURE) }
            stable()
            accepted
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            // Only the fresh directory created by this call is ours, never old probe/staging trees.
            var finishingFailure = failure
            try {
                if (failure !is kotlinx.coroutines.CancellationException) stable()
            } catch (error: Throwable) {
                finishingFailure = error
                throw error
            } finally {
                try { fs.deleteRecursively(directory, mustExist = true) } catch (cleanup: Throwable) {
                    if (finishingFailure is kotlinx.coroutines.CancellationException) throw finishingFailure
                    throw cleanup
                }
            }
        }
        stable()
        return version
    }
}
