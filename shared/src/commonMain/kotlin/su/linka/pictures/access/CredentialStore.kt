package su.linka.pictures.access

import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.CommitGuard

internal interface CredentialStore {
    suspend fun read(): OperationResult<ByteArray?>
    // Atomic publish; after its last guard, cancellation cannot claim rollback of a completed write.
    suspend fun write(value: ByteArray, guard: CommitGuard): OperationResult<Unit>
}
