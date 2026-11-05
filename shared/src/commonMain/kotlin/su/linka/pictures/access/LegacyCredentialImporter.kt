package su.linka.pictures.access

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.CommitGuard

/** Startup-only: library admission and all interactive credential writers are still closed. */
internal class LegacyCredentialImporter(private val store: CredentialStore) {
    private val json = Json { encodeDefaults = true }

    suspend fun prepare(enabled: Boolean, storedPassword: String?, guard: CommitGuard): OperationResult<Unit> = safely {
        if (enabled && (storedPassword?.length ?: 0) > 65_536) return@safely failed(ProblemCode.LIMIT_EXCEEDED)
        val existing = when (val raw = store.read()) {
            is OperationResult.Completed -> raw.value
            else -> return@safely raw.failure()
        }
        if (existing != null) {
            val record = decode(existing) ?: return@safely failed(ProblemCode.INTEGRITY_FAILURE)
            try {
                if (record.enabled != enabled || enabled && !record.legacy) return@safely failed(ProblemCode.CONFLICT)
                if (enabled) {
                    val actual = derive(storedPassword.orEmpty(), record.salt)
                    try { if (!PasswordCrypto.equal(actual, record.verifier)) return@safely failed(ProblemCode.CONFLICT) }
                    finally { actual.fill(0) }
                }
                currentCoroutineContext().ensureActive()
                return@safely guard()
            } finally { record.salt.fill(0); record.verifier.fill(0) }
        }
        val salt = if (enabled) PasswordCrypto.salt() else byteArrayOf()
        val verifier = if (enabled) derive(storedPassword.orEmpty(), salt) else byteArrayOf()
        val record = CredentialRecord(enabled = enabled, legacy = enabled, salt = salt, verifier = verifier)
        val encoded = json.encodeToString(CredentialRecord.serializer(), record).encodeToByteArray()
        try {
            currentCoroutineContext().ensureActive()
            when (val allowed = guard()) { is OperationResult.Completed -> Unit; else -> return@safely allowed }
            when (val written = store.write(encoded, guard)) {
                is OperationResult.Completed -> Unit
                else -> return@safely written
            }
            when (val read = store.read()) {
                is OperationResult.Completed -> if (read.value?.contentEquals(encoded) == true) OperationResult.Completed(Unit)
                    else failed(ProblemCode.INTEGRITY_FAILURE)
                else -> read.failure()
            }
        } finally { encoded.fill(0); salt.fill(0); verifier.fill(0) }
    }

    /** Existing admitted roots require an initialized valid record, including explicitly disabled locks. */
    suspend fun validate(): OperationResult<Unit> = safely {
        when (val raw = store.read()) {
            is OperationResult.Completed -> {
                val record = raw.value?.let(::decode) ?: return@safely failed(ProblemCode.INTEGRITY_FAILURE)
                record.salt.fill(0); record.verifier.fill(0)
                OperationResult.Completed(Unit)
            }
            else -> raw.failure()
        }
    }

    private fun decode(bytes: ByteArray): CredentialRecord? = try {
        if (bytes.size > 8192) null else json.decodeFromString(CredentialRecord.serializer(), bytes.decodeToString(throwOnInvalidSequence = true)).takeIf { it.valid() }
    } catch (_: Exception) { null }

    private suspend fun derive(password: String, salt: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val input = passwordBytes(password)
        try { PasswordCrypto.derive(input, salt, 1_300_000) } finally { input.fill(0) }
    }

    private suspend fun safely(work: suspend () -> OperationResult<Unit>): OperationResult<Unit> = try { work() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { failed(ProblemCode.IO_FAILURE) }
    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))
    private fun OperationResult<*>.failure(): OperationResult<Nothing> = when (this) {
        is OperationResult.Failed -> this
        OperationResult.Cancelled -> OperationResult.Cancelled
        OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
        is OperationResult.Completed -> error("Expected failure")
    }
}
