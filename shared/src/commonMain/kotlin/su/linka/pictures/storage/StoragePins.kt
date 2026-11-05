package su.linka.pictures.storage

import su.linka.pictures.application.*
import su.linka.pictures.application.ports.MediaLease
import su.linka.pictures.domain.AssetId

// Writer mutex -> pins lock. No SQL, IO, runtime calls or external callbacks under this lock.
internal class StoragePins {
    private val lock = StoragePinsLock()
    private val references = mutableMapOf<AssetId, Int>()
    private var retaining = true

    private inner class Lease(val ids: Set<AssetId>) : MediaLease {
        val owner = this@StoragePins
        var closed = false
        override val assets: Set<AssetId> = ids.toSet()

        override fun retain(assets: Set<AssetId>): OperationResult<MediaLease> {
            val child = Lease(assets.toSet())
            return lock.withLock {
                if (!retaining || closed) return@withLock OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
                if (!ids.containsAll(child.ids)) return@withLock OperationResult.Failed(Problem(ProblemCode.MISSING_ASSET))
                register(child)
                OperationResult.Completed(child)
            }
        }

        override fun close() = lock.withLock {
            if (!closed) {
                closed = true
                for (id in ids) {
                    val count = references.getValue(id)
                    if (count == 1) references.remove(id) else references[id] = count - 1
                }
            }
        }
    }

    private fun register(lease: Lease) {
        if (lease.ids.any { references[it] == Int.MAX_VALUE }) fail(ProblemCode.LIMIT_EXCEEDED)
        for (id in lease.ids) references[id] = (references[id] ?: 0) + 1
    }

    fun pin(ids: Set<AssetId>): MediaLease {
        val lease = Lease(ids.toSet())
        lock.withLock { register(lease) }
        return lease
    }

    fun pinned(id: AssetId): Boolean = lock.withLock { id in references }

    fun allows(id: AssetId, lease: MediaLease): Boolean = lock.withLock {
        val own = lease as? Lease
        own != null && own.owner === this && !own.closed && id in own.ids
    }

    fun stopRetains() = lock.withLock { retaining = false }
}
