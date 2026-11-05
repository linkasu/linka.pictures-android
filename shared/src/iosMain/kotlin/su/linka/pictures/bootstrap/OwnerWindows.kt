package su.linka.pictures.bootstrap

import platform.Foundation.NSLock

/** Native admission snapshot, updated by actual UIKit lifecycle events, readable from file workers. */
internal class OwnerWindows {
    private val lock = NSLock()
    private val windows = mutableMapOf<String, Boolean>()
    private val owners = mutableMapOf<String, String>()

    fun openWindow(window: String) = locked { check(windows.put(window, false) == null) }
    fun setActive(window: String, active: Boolean) = locked { if (window in windows) windows[window] = active }
    fun open(owner: String, window: String) = locked {
        require(owner.isNotEmpty() && owner.length <= 256)
        check(window in windows && owner !in owners)
        owners[owner] = window
    }
    fun window(owner: String): String? = locked { owners[owner] }
    fun active(owner: String): Boolean = locked { windows[owners[owner]] == true }
    fun close(owner: String) = locked { owners.remove(owner); Unit }
    fun owners(window: String): Set<String> = locked { owners.filterValues { it == window }.keys.toSet() }
    fun closeWindow(window: String): Set<String> = locked {
        windows.remove(window)
        val closed = owners.filterValues { it == window }.keys.toSet()
        closed.forEach(owners::remove)
        closed
    }
    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
