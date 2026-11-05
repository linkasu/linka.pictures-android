package su.linka.pictures.storage

import platform.Foundation.NSLock

internal actual class StoragePinsLock actual constructor() {
    private val delegate = NSLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
