package su.linka.pictures.application.authoring

import kotlinx.coroutines.flow.MutableStateFlow
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.MediaLease
import su.linka.pictures.application.ports.DelegatingMediaLease
import su.linka.pictures.application.ports.readProof
import su.linka.pictures.domain.AssetId

/** Isolates each owned close so one failing delegate cannot strand a composite's remaining pins. */
internal class AuthoringLease(private val delegate: MediaLease, private val cleanup: (() -> Unit) -> Unit) : DelegatingMediaLease {
    private val closed = MutableStateFlow(false)
    override val assets get() = delegate.assets
    override fun readProof(id: AssetId): MediaLease? = if (closed.value) null else delegate.readProof(id)
    override fun retain(assets: Set<AssetId>): OperationResult<MediaLease> {
        if (closed.value) return OperationResult.Failed(Problem(ProblemCode.MISSING_ASSET))
        return when (val result = delegate.retain(assets)) {
            is OperationResult.Completed -> OperationResult.Completed(AuthoringLease(result.value, cleanup))
            else -> result
        }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) cleanup { delegate.close() }
    }
}
