package su.linka.pictures.migration

import okio.*
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.storage.storageActive

internal interface MigrationSource {
    val kind: MigrationSourceKind
    fun validateTarget(target: Path)
    suspend fun entries(): List<LegacyEntry>
    fun open(entry: LegacyEntry): Source
}

internal suspend fun sourceHash(source: Source, size: Long): String {
    if (size < 0 || size > 2L * 1024 * 1024 * 1024) reject(ProblemCode.LIMIT_EXCEEDED)
    val hashing = HashingSource.sha256(source)
    hashing.use {
        val buffer = Buffer()
        var count = 0L
        while (true) {
            storageActive()
            val read = it.read(buffer, 65536)
            if (read == -1L) break
            count += read
            if (count > size) reject(ProblemCode.INTEGRITY_FAILURE)
            buffer.clear()
        }
        if (count != size) reject(ProblemCode.INTEGRITY_FAILURE)
    }
    return hashing.hash.hex()
}
