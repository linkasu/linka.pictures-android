package su.linka.pictures.storage

import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal class StoredMessages(private val runtime: StorageRuntime) : MessageStore {
    private val db get() = runtime.db

    internal fun read(): StoredMessage {
        val row = db.rows("SELECT epoch,version,payload_json FROM message WHERE singleton=1").single()
        return StoredMessage(row[0]!!.toLong(), row[1]!!.toLong(), decode(row[2]!!))
    }

    override suspend fun message(): OperationResult<StoredMessage> {
        lateinit var value: StoredMessage
        return runtime.libraryStore.locked(roots = {
            value = read()
            if (value.epoch != runtime.epoch()) fail(ProblemCode.INTEGRITY_FAILURE)
            runtime.mediaStore.checkRoles(value.draft.assetRoles())
            value.draft.assetIds()
        }) { value }
    }

    override suspend fun saveMessage(value: StoredMessage): OperationResult<Unit> = runtime.libraryStore.locked(mutation = true, roots = {
        if (value.epoch != runtime.epoch()) fail(ProblemCode.STALE_REQUEST)
        value.draft.assetIds()
    }) {
        val old = read()
        if (value.epoch != runtime.epoch() || value.epoch != old.epoch) fail(ProblemCode.STALE_REQUEST)
        runtime.mediaStore.checkRoles(value.draft.assetRoles())
        if (value.version == old.version && value == old) return@locked
        if (value.version <= old.version) fail(ProblemCode.CONFLICT)
        if (value.draft.parts.size > 4096) fail(ProblemCode.LIMIT_EXCEEDED)
        runtime.transaction(null) {
            db.exec("UPDATE message SET epoch=?,version=?,payload_json=? WHERE singleton=1", value.epoch, value.version, encode(value.draft))
            runtime.libraryStore.refs("MESSAGE", "1", value.draft.assetIds())
        }
    }
}
