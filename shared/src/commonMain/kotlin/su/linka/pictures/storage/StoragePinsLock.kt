package su.linka.pictures.storage

internal expect class StoragePinsLock() {
    fun lock()
    fun unlock()
}

internal inline fun <T> StoragePinsLock.withLock(block: () -> T): T {
    lock()
    try { return block() } finally { unlock() }
}
