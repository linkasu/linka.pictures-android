package su.linka.pictures.application.ports

import su.linka.pictures.application.OperationResult
import su.linka.pictures.domain.RequestId

// Native mechanics only; policy, freshness and stale-result handling belong to Access.
internal interface DeviceOwnerVerifier {
    suspend fun availability(): OwnerVerificationAvailability
    suspend fun verify(requestId: RequestId, ownerId: String): OperationResult<Unit>
    fun cancel(requestId: RequestId)
}
