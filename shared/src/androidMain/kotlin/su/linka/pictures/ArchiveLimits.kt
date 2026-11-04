package su.linka.pictures

data class ArchiveLimits(
    val maxEntries: Int = 10_000,
    val maxConfigBytes: Long = 8L * 1024 * 1024,
    val maxMediaBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 512L * 1024 * 1024,
    val maxInputBytes: Long = 512L * 1024 * 1024,
    val maxDirectoryBytes: Long = 1024L * 1024
) {
    init {
        require(maxEntries >= 0 && maxConfigBytes >= 0 && maxMediaBytes >= 0 &&
            maxTotalBytes >= 0 && maxInputBytes >= 0 && maxDirectoryBytes >= 0)
    }
}
