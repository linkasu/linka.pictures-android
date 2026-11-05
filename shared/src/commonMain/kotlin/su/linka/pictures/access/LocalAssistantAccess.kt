package su.linka.pictures.access

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId

internal class LocalAssistantAccess private constructor(
    private val credentials: CredentialStore,
    private val verification: DeviceOwnerVerifier,
    private val ids: IdSource,
    private var record: CredentialRecord,
) : AssistantAccess {
    private class OwnerContext(val ticket: Any = Any(), val capability: AssistantCapability? = null, val requestId: RequestId? = null)
    private data class GateState(
        val protected: Boolean,
        val ready: Boolean = true,
        val revision: Any = Any(),
        val guardRevision: Any = Any(),
        val owners: Map<String, OwnerContext> = emptyMap(),
    )

    private val writer = Mutex()
    private val gate = MutableStateFlow(GateState(record.enabled))
    private var availability = OwnerVerificationAvailability.UNKNOWN
    private val mutableState = MutableStateFlow<AssistantState>(presentation())
    override val state: StateFlow<AssistantState> = mutableState.asStateFlow()

    override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): OperationResult<Unit> =
        check(action, capability, ownerId, gate.value)

    private fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String, current: GateState): OperationResult<Unit> {
        if (!validOwner(ownerId) || !current.ready || action == ProtectedAction.RESET_LOCK) return denied()
        if (capability != null && (capability.owner != ownerId || current.owners[ownerId]?.capability !== capability)) return denied()
        if (!current.protected) return OperationResult.Completed(Unit)
        return if (capability != null && current.owners[ownerId]?.capability === capability)
            OperationResult.Completed(Unit) else denied()
    }

    override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard {
        if (check(action, capability, ownerId) !is OperationResult.Completed) return { denied() }
        val ticket = context(ownerId, replace = false) ?: return { denied() }
        val revision = gate.value.revision
        val guardRevision = gate.value.guardRevision
        return {
            val snapshot = gate.value
            if (snapshot.guardRevision !== guardRevision || !current(ownerId, ticket, revision, snapshot)) denied()
            else check(action, capability, ownerId, snapshot)
        }
    }

    override suspend fun unlock(password: String, ownerId: String): OperationResult<AssistantCapability> {
        val ticket = context(ownerId, replace = true) ?: return denied()
        val revision = gate.value.revision
        return safely {
            writer.withLock {
                if (!current(ownerId, ticket, revision)) return@withLock denied()
                if (record.enabled) {
                    val input = if (record.legacy) password.trim() else password
                    if (input.length > 65_536 || record.legacy && input.isEmpty()) return@withLock denied()
                    val derived = derive(input, record.salt)
                    val matches = try { PasswordCrypto.equal(derived, record.verifier) } finally { derived.fill(0) }
                    if (!matches) return@withLock denied()
                }
                currentCoroutineContext().ensureActive()
                val capability = AssistantCapability(ids.next(), ownerId)
                while (true) {
                    val previous = gate.value
                    if (!current(ownerId, ticket, revision, previous)) return@withLock denied()
                    val next = previous.copy(owners = previous.owners + (ownerId to OwnerContext(ticket, capability)))
                    if (gate.compareAndSet(previous, next)) break
                }
                OperationResult.Completed(capability)
            }
        }
    }

    override suspend fun setPassword(password: String, ownerId: String, capability: AssistantCapability?): OperationResult<Unit> {
        val permit = guard(ProtectedAction.CONFIGURE_LOCK, capability, ownerId)
        return safely { writer.withLock {
            val allowed = permit()
            if (allowed !is OperationResult.Completed) return@withLock allowed
            if (!validPassword(password)) return@withLock failed(ProblemCode.INVALID_DATA)
            availability = verification.availability()
            mutableState.value = presentation()
            if (availability != OwnerVerificationAvailability.AVAILABLE) return@withLock failed(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE)
            publish(newRecord(password), permit)
        } }
    }

    override suspend fun resetPasswordWithDeviceOwner(password: String, ownerId: String): OperationResult<Unit> {
        if (!validPassword(password)) return failed(ProblemCode.INVALID_DATA)
        val requestId = try { RequestId(ids.next()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return failed(ProblemCode.IO_FAILURE) }
        val ticket = context(ownerId, replace = true, requestId = requestId) ?: return denied()
        val revision = gate.value.revision
        val permit: CommitGuard = { if (current(ownerId, ticket, revision)) OperationResult.Completed(Unit) else denied() }
        return safely { writer.withLock {
            if (permit() !is OperationResult.Completed) return@withLock denied()
            availability = verification.availability()
            mutableState.value = presentation()
            if (availability != OwnerVerificationAvailability.AVAILABLE) return@withLock failed(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE)
            currentCoroutineContext().ensureActive()
            if (permit() !is OperationResult.Completed) return@withLock denied()
            when (val proof = verification.verify(requestId, ownerId)) {
                is OperationResult.Completed -> Unit
                else -> return@withLock proof
            }
            currentCoroutineContext().ensureActive()
            if (permit() !is OperationResult.Completed) return@withLock denied()
            publish(newRecord(password), permit)
        } }
    }

    override suspend fun disableLock(capability: AssistantCapability, ownerId: String): OperationResult<Unit> {
        val permit = guard(ProtectedAction.CONFIGURE_LOCK, capability, ownerId)
        return safely { writer.withLock {
            if (permit() !is OperationResult.Completed) return@withLock denied()
            publish(CredentialRecord(enabled = false), permit)
        } }
    }

    override suspend fun refreshPrerequisites() {
        writer.withLock {
            availability = try { verification.availability() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { OwnerVerificationAvailability.UNKNOWN }
            if (gate.value.ready) mutableState.value = presentation()
        }
    }

    override fun revokeOwner(ownerId: String) {
        val previous = gate.getAndUpdate { it.copy(owners = it.owners - ownerId) }
        previous.owners[ownerId]?.requestId?.let { try { verification.cancel(it) } catch (_: Exception) { } }
    }

    override fun revokeAll() {
        val previous = gate.getAndUpdate { it.copy(revision = Any(), owners = emptyMap()) }
        previous.owners.values.forEach { context ->
            context.requestId?.let { try { verification.cancel(it) } catch (_: Exception) { } }
        }
    }

    // Only the root's owned system presentation uses this; real background still calls revokeAll().
    internal fun suspendForSystemPrompt() {
        gate.update { previous -> previous.copy(guardRevision = Any(), owners = previous.owners.filterValues { it.requestId != null }) }
    }

    internal fun isVerificationCurrent(requestId: RequestId, ownerId: String): Boolean {
        val snapshot = gate.value
        return snapshot.ready && snapshot.owners[ownerId]?.requestId == requestId
    }

    private fun context(ownerId: String, replace: Boolean, requestId: RequestId? = null): Any? {
        if (!validOwner(ownerId)) return null
        val created = OwnerContext(requestId = requestId)
        while (true) {
            val previous = gate.value
            if (!previous.ready) return null
            if (!replace) previous.owners[ownerId]?.let { return it.ticket }
            if (ownerId !in previous.owners && previous.owners.size >= 64) return null
            if (gate.compareAndSet(previous, previous.copy(owners = previous.owners + (ownerId to created)))) {
                if (replace) previous.owners[ownerId]?.requestId?.let { try { verification.cancel(it) } catch (_: Exception) { } }
                return created.ticket
            }
        }
    }

    private fun current(ownerId: String, ticket: Any, revision: Any, value: GateState = gate.value): Boolean =
        value.ready && value.revision === revision && value.owners[ownerId]?.ticket === ticket

    private suspend fun newRecord(password: String): CredentialRecord {
        val salt = PasswordCrypto.salt()
        return CredentialRecord(enabled = true, salt = salt, verifier = derive(password, salt))
    }

    private suspend fun derive(password: String, salt: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val bytes = passwordBytes(password)
        try { PasswordCrypto.derive(bytes, salt, 1_300_000) } finally { bytes.fill(0) }
    }

    private suspend fun publish(next: CredentialRecord, permit: CommitGuard): OperationResult<Unit> {
        val context = currentCoroutineContext()
        context.ensureActive()
        val encoded = json.encodeToString(CredentialRecord.serializer(), next).encodeToByteArray()
        return withContext(NonCancellable) {
            val lastGuard: CommitGuard = { if (!context.isActive) OperationResult.Cancelled else permit() }
            val result = try {
                when (val allowed = lastGuard()) {
                    is OperationResult.Completed -> credentials.write(encoded, lastGuard)
                    else -> allowed
                }
            } catch (_: Exception) { OperationResult.UnconfirmedCommit }
            when (result) {
                is OperationResult.Completed -> {
                    record = next
                    gate.update { it.copy(protected = next.enabled, revision = Any(), owners = emptyMap()) }
                    mutableState.value = presentation()
                }
                OperationResult.UnconfirmedCommit -> {
                    gate.update { it.copy(ready = false, revision = Any(), owners = emptyMap()) }
                    mutableState.value = AssistantState.Unavailable(Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
                }
                else -> Unit
            }
            result
        }
    }

    private fun presentation(): AssistantState = if (record.enabled) AssistantState.Protected(record.legacy, availability)
        else AssistantState.Unprotected(availability)

    private suspend fun <T> safely(block: suspend () -> OperationResult<T>): OperationResult<T> = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { failed(ProblemCode.IO_FAILURE) }

    companion object {
        private val json = Json { encodeDefaults = true }
        private fun validOwner(value: String): Boolean = value.isNotEmpty() && value.length <= 256
        private fun validPassword(value: String): Boolean = value.isNotEmpty() && value.length <= 65_536
        private fun denied() = failed(ProblemCode.UNAUTHORIZED)
        private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))

        suspend fun open(credentials: CredentialStore, verification: DeviceOwnerVerifier, ids: IdSource): OperationResult<LocalAssistantAccess> {
            return try {
            val raw = when (val read = credentials.read()) {
                is OperationResult.Completed -> read.value
                is OperationResult.Failed -> return read
                OperationResult.Cancelled -> return OperationResult.Cancelled
                OperationResult.UnconfirmedCommit -> return failed(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
            }
            if (raw != null && raw.size > 8192) return failed(ProblemCode.INTEGRITY_FAILURE)
            val record = if (raw == null) CredentialRecord(enabled = false) else
                try { json.decodeFromString(CredentialRecord.serializer(), raw.decodeToString(throwOnInvalidSequence = true)) }
                catch (_: Exception) { return failed(ProblemCode.INTEGRITY_FAILURE) }
            if (!record.valid()) return failed(ProblemCode.INTEGRITY_FAILURE)
            OperationResult.Completed(LocalAssistantAccess(credentials, verification, ids, record))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed(ProblemCode.IO_FAILURE) }
        }
    }
}
