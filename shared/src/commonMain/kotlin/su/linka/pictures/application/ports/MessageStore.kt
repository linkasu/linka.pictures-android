package su.linka.pictures.application.ports

import su.linka.pictures.application.OperationResult
import su.linka.pictures.domain.StoredMessage

interface MessageStore {
    suspend fun message(): OperationResult<StoredMessage>
    // Same epoch/version/value is idempotent; stale epoch or conflicting version never overwrites.
    suspend fun saveMessage(value: StoredMessage): OperationResult<Unit>
}
