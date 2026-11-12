package su.linka.pictures.activity

/** Cold import may advance slowly; neither unchanged progress nor changing UI can wait forever. */
internal class StartupWait(private val startedAt: Long) {
    private var changedAt = startedAt
    private var previous: List<String>? = null

    fun expired(now: Long, progress: List<String>): Boolean {
        if (progress != previous) {
            previous = progress.toList()
            changedAt = now
        }
        return now - startedAt >= 180_000 || now - changedAt >= 45_000
    }
}
