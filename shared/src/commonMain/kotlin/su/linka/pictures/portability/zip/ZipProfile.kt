package su.linka.pictures.portability.zip

internal const val BACKUP_LIMIT = 2L * 1024 * 1024 * 1024
internal const val JSONL_LIMIT = 64L * 1024 * 1024

internal enum class ZipProfile(val entries: Int, val bytes: Long, val nameBytes: Long) {
    LINKA(2000, ARCHIVE_LIMIT, CENTRAL_NAME_BYTE_LIMIT),
    BACKUP(60000, BACKUP_LIMIT, 8L * 1024 * 1024);

    fun entryBytes(name: String): Long = when (this) {
        LINKA -> if (name == "config.json") CONFIG_LIMIT else MEDIA_LIMIT
        BACKUP -> when {
            name == "assets.jsonl" -> JSONL_LIMIT
            name.startsWith("assets/") -> BACKUP_LIMIT
            else -> CONFIG_LIMIT
        }
    }
}
