package su.linka.pictures.bootstrap

import kotlinx.coroutines.*
import platform.Foundation.NSLock
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*

/** Cancels only this root's picker jobs/destinations on owner close or scene background. */
internal class OwnedFiles(private val native: PlatformFilesPort, private val owners: OwnerWindows) : PlatformFilesPort by native {
    private val lock = NSLock()
    private val jobs = mutableMapOf<String, MutableSet<Job>>()
    private val destinations = mutableSetOf<Destination>()

    override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> {
        var temporary: TemporaryFile? = null
        var published = false
        try {
            val result = owned(ownerId) {
                val picked = native.pick(kind, ownerId)
                if (picked is OperationResult.Completed) temporary = picked.value
                if (owners.active(ownerId)) picked else OperationResult.Cancelled
            }
            currentCoroutineContext().ensureActive()
            published = result is OperationResult.Completed
            return result
        } finally { if (!published) temporary?.let(native::discard) }
    }

    override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> {
        var destination: Destination? = null
        var published = false
        try {
            val result = owned(ownerId) {
                when (val chosen = native.chooseDestination(suggestedName, mediaType, ownerId)) {
                    is OperationResult.Completed -> {
                        val handle = Destination(chosen.value)
                        destination = handle
                        val admitted = locked {
                            if (owners.active(ownerId)) { destinations += handle; true } else false
                        }
                        if (admitted) OperationResult.Completed(handle) else OperationResult.Cancelled
                    }
                    else -> chosen
                }
            }
            currentCoroutineContext().ensureActive()
            published = result is OperationResult.Completed
            return result
        } finally { if (!published) destination?.close() }
    }

    private suspend fun <T> owned(owner: String, operation: suspend () -> OperationResult<T>): OperationResult<T> = coroutineScope {
        val job = currentCoroutineContext().job
        val admitted = locked {
            if (owners.active(owner)) { jobs.getOrPut(owner) { mutableSetOf() } += job; true } else false
        }
        if (!admitted) return@coroutineScope OperationResult.Cancelled
        try { operation() } finally { locked { jobs[owner]?.let { it -= job; if (it.isEmpty()) jobs.remove(owner) } } }
    }

    fun revoke(owner: String) {
        val (pending, handles) = locked { jobs.remove(owner).orEmpty().toList() to destinations.filter { it.ownerId == owner } }
        pending.forEach { it.cancel() }
        handles.forEach { runCatching { it.close() } }
    }

    private inner class Destination(private val native: ExportDestination) : ExportDestination by native {
        override fun close() {
            locked { destinations.remove(this) }
            native.close()
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
