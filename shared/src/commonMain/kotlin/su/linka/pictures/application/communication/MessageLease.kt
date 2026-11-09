package su.linka.pictures.application.communication

import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.Problem
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.ports.MediaLease
import su.linka.pictures.application.ports.DelegatingMediaLease
import su.linka.pictures.application.ports.readProof
import su.linka.pictures.domain.AssetId

/** Owns independent references obtained from already pinned sources, without an acquire/GC gap. */
internal class MessageLease(private val leases: List<MediaLease>) : DelegatingMediaLease {
    private var closed = false
    override val assets = leases.flatMap { it.assets }.toSet()

    override fun readProof(id: AssetId): MediaLease? =
        if (closed) null else leases.firstOrNull { id in it.assets }?.readProof(id)

    override fun retain(assets: Set<AssetId>): OperationResult<MediaLease> =
        if (closed) OperationResult.Failed(Problem(ProblemCode.MISSING_ASSET)) else retainFrom(assets, leases)

    override fun close() {
        if (!closed) {
            closed = true
            leases.forEach { it.close() }
        }
    }

    companion object {
        fun retainFrom(assets: Set<AssetId>, sources: List<MediaLease>): OperationResult<MediaLease> {
            var remaining = assets
            val owned = mutableListOf<MediaLease>()
            for (source in sources) {
                val subset = remaining.intersect(source.assets)
                if (subset.isEmpty()) continue
                when (val retained = source.retain(subset)) {
                    is OperationResult.Completed -> owned += retained.value
                    is OperationResult.Failed -> {
                        owned.forEach { it.close() }
                        return retained
                    }
                    OperationResult.Cancelled -> {
                        owned.forEach { it.close() }
                        return OperationResult.Cancelled
                    }
                    OperationResult.UnconfirmedCommit -> {
                        owned.forEach { it.close() }
                        return OperationResult.UnconfirmedCommit
                    }
                }
                remaining = remaining - subset
            }
            if (remaining.isNotEmpty()) {
                owned.forEach { it.close() }
                return OperationResult.Failed(Problem(ProblemCode.MISSING_ASSET))
            }
            return OperationResult.Completed(MessageLease(owned))
        }
    }
}
