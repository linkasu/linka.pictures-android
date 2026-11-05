package su.linka.pictures.application.authoring

import kotlinx.coroutines.flow.*
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.MediaLease
import su.linka.pictures.domain.AssetId

internal class AuthoringLeaseProbe {
    val active = MutableStateFlow(0)
    val closes = MutableStateFlow(0)
    val failClose = MutableStateFlow(false)
    fun own(delegate: MediaLease): MediaLease {
        active.update { it + 1 }
        return object : MediaLease {
            val closed = MutableStateFlow(false)
            override val assets get() = delegate.assets
            override fun retain(assets: Set<AssetId>): OperationResult<MediaLease> = when (val result = delegate.retain(assets)) {
                is OperationResult.Completed -> OperationResult.Completed(own(result.value))
                else -> result
            }
            override fun close() {
                check(closed.compareAndSet(false, true)) { "Delegate lease closed twice" }
                try { delegate.close() }
                finally { active.update { it - 1 }; closes.update { it + 1 } }
                if (failClose.value) error("Controlled close failure after release")
            }
        }
    }
}
