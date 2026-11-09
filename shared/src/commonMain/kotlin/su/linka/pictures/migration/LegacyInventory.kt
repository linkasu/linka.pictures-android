package su.linka.pictures.migration

import okio.*
import su.linka.pictures.application.ProblemCode

internal data class LegacyEntry(val name: String, val size: Long, val hash: String)
internal data class LegacyInventory(val entries: List<LegacyEntry>, val settings: LegacyUpgradeSettings, val digest: String)

internal suspend fun inventory(source: MigrationSource, settings: LegacyUpgradeSettings): LegacyInventory {
    val captured = settings.copy(boardGrid = settings.boardGrid.toMap())
    val entries = source.entries().sortedBy { it.name }
    if (entries.size > 100000 || entries.map { it.name }.toSet().size != entries.size) reject(ProblemCode.INVALID_DATA)
    val digest = HashingSink.sha256(blackholeSink())
    digest.buffer().use { sink ->
        sink.writeUtf8("linka-legacy-admission-1")
        for (entry in entries) {
            val name = entry.name.encodeToByteArray()
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
