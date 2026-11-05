package su.linka.pictures.migration

import okio.*
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.storage.storageActive

internal data class LegacyEntry(val path: Path, val size: Long, val hash: String)
internal data class LegacyInventory(val entries: List<LegacyEntry>, val settings: LegacyUpgradeSettings, val digest: String)

internal suspend fun inventory(files: FileSystem, source: Path, settings: LegacyUpgradeSettings): LegacyInventory {
    val captured = settings.copy(boardGrid = settings.boardGrid.toMap())
    val entries = mutableListOf<LegacyEntry>()
    val root = files.metadataOrNull(source)
    if (root != null) {
        if (!root.isDirectory || root.symlinkTarget != null || files.canonicalize(source) != source.normalized()) reject(ProblemCode.INVALID_DATA)
        for (path in files.list(source).sortedBy { it.name }) {
            storageActive()
            val before = files.metadata(path)
            if (!before.isRegularFile || before.symlinkTarget != null || files.canonicalize(path).parent != source.normalized()) reject(ProblemCode.INVALID_DATA)
            val size = before.size ?: reject(ProblemCode.INVALID_DATA)
            if (size > 2L * 1024 * 1024 * 1024) reject(ProblemCode.LIMIT_EXCEEDED)
            val sourceHash = HashingSource.sha256(files.source(path))
            var count = 0L
            sourceHash.use { stream ->
                val buffer = Buffer()
                while (true) {
                    storageActive()
                    val read = stream.read(buffer, 65536)
                    if (read == -1L) break
                    count += read
                    if (count > size) reject(ProblemCode.INTEGRITY_FAILURE)
                    buffer.clear()
                }
            }
            val after = files.metadata(path)
            if (count != size || !after.isRegularFile || after.symlinkTarget != null || after.size != size ||
                before.lastModifiedAtMillis != after.lastModifiedAtMillis || before.createdAtMillis != after.createdAtMillis ||
                files.canonicalize(path).parent != source.normalized()) reject(ProblemCode.INTEGRITY_FAILURE)
            entries += LegacyEntry(path, size, sourceHash.hash.hex())
            if (entries.size > 100000) reject(ProblemCode.LIMIT_EXCEEDED)
        }
    }
    val digest = HashingSink.sha256(blackholeSink())
    digest.buffer().use { sink ->
        sink.writeUtf8("linka-legacy-admission-1")
        for (entry in entries) {
            val name = entry.path.name.encodeToByteArray()
            sink.writeInt(name.size).write(name).writeLong(entry.size).writeUtf8(entry.hash)
        }
        sink.writeInt(-1).writeByte(if (captured.parentEnabled) 1 else 0)
        for ((key, value) in captured.boardGrid.entries.sortedBy { it.key }) {
            val name = key.encodeToByteArray()
            sink.writeInt(name.size).write(name).writeInt(value)
        }
    }
    return LegacyInventory(entries, captured, digest.hash.hex())
}
