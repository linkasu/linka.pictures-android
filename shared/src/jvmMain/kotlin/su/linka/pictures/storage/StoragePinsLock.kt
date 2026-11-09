package su.linka.pictures.storage

import java.util.concurrent.locks.ReentrantLock

internal actual class StoragePinsLock actual constructor() {
    private val delegate = ReentrantLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
