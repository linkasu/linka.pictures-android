package su.linka.pictures.application.portability

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.backup.BackupCodec
import su.linka.pictures.portability.linka.LinkaCodec
import su.linka.pictures.portability.zip.*

class TransferCoordinator(
    private val library: LibraryStore,
    private val messages: MessageStore,
    private val linka: LinkaCodec,
    private val backup: BackupCodec,
    private val files: PlatformFilesPort,
    private val gate: AssistantGate,
    private val ids: IdSource,
    private val scope: CoroutineScope,
) {
    private class Pending(val id: OperationId, val owner: String, val kind: TransferKind, val epoch: Long) {
        val revoked = MutableStateFlow(false)
        val terminalPhase = MutableStateFlow<TransferPhase?>(null)
        val resources = mutableListOf<() -> Unit>()
        val cleanupLock = Mutex()
        var job: Deferred<OperationResult<Unit>>? = null
        var board: PreparedBoard? = null
        var snapshot: SnapshotReader? = null
        var output: TemporaryFile? = null
        var destination: ExportDestination? = null
        var recovery: RecoveryId? = null
        var expectedRevision: Long = 0
        var expectedMessage: MessageStamp? = null
        var entries = 0
        var bytes = 0L
    }

    private val lock = Mutex()
    private val pending = mutableMapOf<OperationId, Pending>()
    private val closedOwners = mutableSetOf<String>()
    private val mutableState = MutableStateFlow<Map<OperationId, TransferOperationState>>(emptyMap())
    val state: StateFlow<Map<OperationId, TransferOperationState>> = mutableState.asStateFlow()
    private val frozen = MutableStateFlow(false)

    suspend fun prepareImport(ownerId: String, name: String): OperationResult<OperationId> =
        prepare(ownerId, TransferKind.IMPORT) { op ->
            val input = take(files.pick(PickedFileKind.LINKA, ownerId))
            op.resources.add { files.discard(input) }
            currentCoroutineContext().ensureActive()
            val prepared = take(linka.read(input, name))
            op.resources.add(prepared::close)
            op.board = prepared
            currentCoroutineContext().ensureActive()
            countArchive(op, input, ZipProfile.LINKA)
            TransferPreview(op.kind, prepared.document.name, board = prepared.report)
        }

    suspend fun prepareRestore(ownerId: String): OperationResult<OperationId> =
        prepare(ownerId, TransferKind.RESTORE) { op ->
            captureStamps(op)
            val input = take(files.pick(PickedFileKind.BACKUP, ownerId))
            op.resources.add { files.discard(input) }
            currentCoroutineContext().ensureActive()
            val snapshot = take(backup.read(input))
            op.resources.add(snapshot::close)
            op.snapshot = snapshot
            currentCoroutineContext().ensureActive()
            countArchive(op, input, ZipProfile.BACKUP)
            TransferPreview(op.kind, snapshot = snapshot.counts,
                expectedLibraryRevision = op.expectedRevision, expectedMessage = op.expectedMessage)
        }

    suspend fun prepareExport(ownerId: String, boardId: BoardId): OperationResult<OperationId> =
        prepare(ownerId, TransferKind.EXPORT) { op ->
            val board = take(library.board(boardId))
            op.resources.add(board.lease::close)
            currentCoroutineContext().ensureActive()
            if (board.epoch != op.epoch) stop(ProblemCode.STALE_REQUEST)
            val output = take(linka.write(board.board, board.lease))
            op.resources.add { files.discard(output) }
            op.output = output
            currentCoroutineContext().ensureActive()
            countArchive(op, output, ZipProfile.LINKA)
            destination(op, "board.linka")
            TransferPreview(op.kind, name = board.board.document.name)
        }

    suspend fun prepareBackup(ownerId: String): OperationResult<OperationId> =
        prepare(ownerId, TransferKind.BACKUP) { op ->
            val snapshot = take(library.snapshot())
            op.resources.add(snapshot::close)
            currentCoroutineContext().ensureActive()
            if (snapshot.epoch != op.epoch) stop(ProblemCode.STALE_REQUEST)
            val output = take(backup.write(snapshot))
            op.resources.add { files.discard(output) }
            op.output = output
            currentCoroutineContext().ensureActive()
            countArchive(op, output, ZipProfile.BACKUP)
            destination(op, "library.linkabackup")
            TransferPreview(op.kind, snapshot = snapshot.counts)
        }

    suspend fun prepareReturnToRecovery(ownerId: String, id: RecoveryId): OperationResult<OperationId> =
        prepareRecovery(ownerId, id, TransferKind.RETURN_TO_RECOVERY)

    suspend fun prepareDeleteRecovery(ownerId: String, id: RecoveryId): OperationResult<OperationId> =
        prepareRecovery(ownerId, id, TransferKind.DELETE_RECOVERY)

    private suspend fun prepareRecovery(owner: String, id: RecoveryId, kind: TransferKind) =
        prepare(owner, kind) { op ->
            captureStamps(op)
            val recovery = take(library.recovery()) ?: stop(ProblemCode.RECOVERY_UNAVAILABLE)
            if (recovery.id != id) stop(ProblemCode.CONFLICT)
            op.recovery = id
            TransferPreview(kind, expectedLibraryRevision = op.expectedRevision, expectedMessage = op.expectedMessage)
        }

    private suspend fun captureStamps(op: Pending) {
        val listing = library.listing.value
        if (listing.epoch != op.epoch) stop(ProblemCode.STALE_REQUEST)
        listing.refreshProblem?.let { throw Halt(OperationResult.Failed(it)) }
        op.expectedRevision = listing.revision
        op.expectedMessage = take(messages.message()).stamp
        currentCoroutineContext().ensureActive()
        if (op.expectedMessage!!.epoch != op.epoch) stop(ProblemCode.STALE_REQUEST)
    }

    private suspend fun destination(op: Pending, name: String) {
        currentCoroutineContext().ensureActive()
        val destination = take(files.chooseDestination(name, "application/zip", op.owner))
        // A foreign handle never becomes ours, including for cleanup.
        if (destination.ownerId != op.owner) stop(ProblemCode.UNAUTHORIZED)
        op.resources.add(destination::close)
        op.destination = destination
        currentCoroutineContext().ensureActive()
    }

    private suspend fun countArchive(op: Pending, file: LocalFile, profile: ZipProfile) {
        val items = take(codecBoundary { CheckedZip.open(files, file, profile).items })
        op.entries = items.size
        op.bytes = items.sumOf { it.size }
    }

    private suspend fun prepare(
        owner: String,
        kind: TransferKind,
        work: suspend (Pending) -> TransferPreview,
    ): OperationResult<OperationId> {
        if (owner.isBlank()) return failed(ProblemCode.INVALID_DATA)
        val op = Pending(OperationId(ids.next()), owner, kind, library.listing.value.epoch)
        val job: Deferred<OperationResult<Unit>>
        lock.withLock {
            if (frozen.value) return failed(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
            if (owner in closedOwners) return failed(ProblemCode.STALE_REQUEST)
            if (pending.values.any { it.owner == owner }) return failed(ProblemCode.CONFLICT)
            if (op.id in mutableState.value) return failed(ProblemCode.CONFLICT)
            pending[op.id] = op
            publish(op, if (kind == TransferKind.IMPORT || kind == TransferKind.RESTORE) TransferPhase.VALIDATING else TransferPhase.PREPARING, replaceOwner = true)
            op.job = task(op, preparing = true) {
                val preview = work(op)
                currentCoroutineContext().ensureActive()
                lock.withLock {
                    if (op.revoked.value) throw CancellationException()
                    publish(op, TransferPhase.AWAITING_CONFIRMATION, preview = preview)
                }
                OperationResult.Completed(Unit)
            }
            job = op.job!!
        }
        return when (val result = await(op, job)) {
            is OperationResult.Completed -> OperationResult.Completed(op.id)
            is OperationResult.Failed -> result
            OperationResult.Cancelled -> OperationResult.Cancelled
            OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
        }
    }

    suspend fun confirm(
        ownerId: String,
        operationId: OperationId,
        capability: AssistantCapability?,
        confirmation: TransferConfirmation,
    ): OperationResult<Unit> {
        val op: Pending
        val job: Deferred<OperationResult<Unit>>
        lock.withLock {
            op = pending[operationId]?.takeIf { it.owner == ownerId } ?: return failed(ProblemCode.STALE_REQUEST)
            if (frozen.value) return failed(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
            if (op.revoked.value) return failed(ProblemCode.STALE_REQUEST)
            if (mutableState.value[operationId]?.transfer?.phase != TransferPhase.AWAITING_CONFIRMATION)
                return failed(ProblemCode.CONFLICT)
            if (!matches(op.kind, confirmation)) return failed(ProblemCode.INVALID_DATA)
            publish(op, TransferPhase.WRITING)
            op.job = task(op, preparing = false) {
                // No FSM lock is held while creating or invoking application authorization callbacks.
                val fresh = gate.guard(action(op.kind), capability, ownerId)
                val guard: CommitGuard = {
                    when {
                        op.revoked.value -> OperationResult.Cancelled
                        frozen.value -> failed(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
                        library.listing.value.epoch != op.epoch -> failed(ProblemCode.STALE_REQUEST)
                        else -> fresh()
                    }
                }
                take(guard())
                val permit = WritePermit(op.epoch, guard)
                when (confirmation) {
                    TransferConfirmation.ImportNew -> library.addBoard(
                        op.board!!.document.copy(id = BoardId(ids.next())), permit).unit()
                    is TransferConfirmation.ImportReplace -> library.replaceBoard(
                        op.board!!.document.copy(id = confirmation.boardId), confirmation.expectedRevision, permit).unit()
                    TransferConfirmation.WriteFile -> op.destination!!.write(op.output!!, guard)
                    TransferConfirmation.ReplaceWholeLibrary -> library.replaceLibrary(
                        op.snapshot!!, op.expectedRevision, op.expectedMessage!!, permit).unit()
                    TransferConfirmation.ReturnToRecovery -> library.returnToRecovery(
                        op.recovery!!, op.expectedRevision, op.expectedMessage!!, permit).unit()
                    TransferConfirmation.DeleteRecovery -> library.deleteRecovery(op.recovery!!, permit)
                }
            }
            job = op.job!!
        }
        return await(op, job)
    }

    suspend fun cancel(ownerId: String, operationId: OperationId, capability: AssistantCapability?): OperationResult<Unit> {
        val op = lock.withLock { pending[operationId]?.takeIf { it.owner == ownerId } }
            ?: return failed(ProblemCode.STALE_REQUEST)
        val allowed = gate.check(action(op.kind), capability, ownerId)
        if (allowed !is OperationResult.Completed) return allowed
        revoke(op)
        return OperationResult.Completed(Unit)
    }

    // Lifecycle invalidation is not authorization and never revives the revoked operation.
    suspend fun revokePending(ownerId: String) {
        val operations = lock.withLock { pending.values.filter { it.owner == ownerId } }
        operations.forEach { revoke(it) }
    }

    suspend fun close(ownerId: String, capability: AssistantCapability?): OperationResult<Unit> {
        val operations = lock.withLock { pending.values.filter { it.owner == ownerId } }
        val records = state.value.values.filter { it.ownerId == ownerId }
        if (records.isEmpty() && operations.isEmpty()) return OperationResult.Completed(Unit)
        for (record in records) {
            val allowed = gate.check(action(record.kind), capability, ownerId)
            if (allowed !is OperationResult.Completed) return allowed
        }
        lock.withLock {
            if (pending.values.filter { it.owner == ownerId }.toSet() != operations.toSet()) return failed(ProblemCode.CONFLICT)
            closedOwners.add(ownerId)
        }
        operations.forEach { revoke(it) }
        lock.withLock { mutableState.value = mutableState.value.filterValues { it.ownerId != ownerId } }
        return OperationResult.Completed(Unit)
    }

    private suspend fun revoke(op: Pending) {
        val job = lock.withLock {
            if (pending[op.id] !== op) return
            op.revoked.value = true
            op.job
        }
        job?.cancel()
        withContext(NonCancellable) {
            job?.join()
            cleanup(op)
            lock.withLock {
                val phase = mutableState.value[op.id]?.transfer?.phase
                if (phase == TransferPhase.AWAITING_CONFIRMATION) publish(op, TransferPhase.CANCELLED)
                pending.remove(op.id)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    private fun task(op: Pending, preparing: Boolean, work: suspend () -> OperationResult<Unit>) =
        scope.async(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
            var keep = false
            try {
                val result = try {
                    currentCoroutineContext().ensureActive()
                    work()
                } catch (halt: Halt) {
                    halt.result
                } catch (cancelled: CancellationException) {
                    OperationResult.Cancelled
                } catch (error: SnapshotReadException) {
                    OperationResult.Failed(error.problem)
                } catch (_: Exception) {
                    failed(ProblemCode.IO_FAILURE)
                }
                withContext(NonCancellable) {
                    lock.withLock {
                        keep = preparing && result is OperationResult.Completed && !op.revoked.value
                        if (!keep) {
                            val phase = when (result) {
                                is OperationResult.Completed -> if (preparing) TransferPhase.CANCELLED else TransferPhase.COMPLETED
                                OperationResult.Cancelled -> TransferPhase.CANCELLED
                                OperationResult.UnconfirmedCommit -> TransferPhase.UNCONFIRMED
                                is OperationResult.Failed -> TransferPhase.FAILED
                            }
                            if (phase == TransferPhase.UNCONFIRMED) frozen.value = true
                            op.terminalPhase.value = phase
                            val problem = when (result) {
                                is OperationResult.Failed -> result.problem
                                OperationResult.UnconfirmedCommit -> Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
                                else -> null
                            }
                            publish(op, phase, problem = problem,
                                epoch = if (phase == TransferPhase.COMPLETED &&
                                    op.kind in setOf(TransferKind.RESTORE, TransferKind.RETURN_TO_RECOVERY)) library.listing.value.epoch else null)
                        }
                    }
                }
                result
            } finally {
                if (!keep) withContext(NonCancellable) {
                    cleanup(op)
                    lock.withLock { pending.remove(op.id) }
                }
            }
        }

    private suspend fun await(op: Pending, job: Deferred<OperationResult<Unit>>): OperationResult<Unit> = try {
        job.await()
    } catch (cancelled: CancellationException) {
        // New admission may already have replaced the public record before this waiter resumes.
        val terminal = op.terminalPhase.value
        if (terminal == TransferPhase.COMPLETED) OperationResult.Completed(Unit)
        else if (terminal == TransferPhase.UNCONFIRMED) OperationResult.UnconfirmedCommit
        else if (op.revoked.value) OperationResult.Cancelled else {
            withContext(NonCancellable) { revoke(op) }
            throw cancelled
        }
    }

    private suspend fun cleanup(op: Pending) = withContext(Dispatchers.IO) {
        op.cleanupLock.withLock {
            while (op.resources.isNotEmpty()) {
                val close = op.resources.removeAt(op.resources.lastIndex)
                try { close() } catch (_: Exception) { /* Preserve the primary outcome; finish remaining cleanup. */ }
            }
        }
    }

    // Called only under the tiny state lock. No callback, IO, SQL or resource cleanup here.
    private fun publish(op: Pending, phase: TransferPhase, preview: TransferPreview? = null, problem: Problem? = null, epoch: Long? = null, replaceOwner: Boolean = false) {
        val old = mutableState.value[op.id]
        val shown = preview ?: old?.preview
        // Admission replaces this owner's terminal outcome atomically with the new operation.
        val records = if (replaceOwner) mutableState.value.filterValues { it.ownerId != op.owner } else mutableState.value
        mutableState.value = records + (op.id to TransferOperationState(op.owner, op.kind,
            TransferState(op.id, phase, op.entries, op.bytes, problem), shown, epoch ?: old?.stateCommittedEpoch))
    }

    private class Halt(val result: OperationResult<Nothing>) : Exception()
    private fun stop(code: ProblemCode): Nothing = throw Halt(failed(code))
    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))
    private fun <T> take(result: OperationResult<T>): T = when (result) {
        is OperationResult.Completed -> result.value
        is OperationResult.Failed -> throw Halt(result)
        OperationResult.Cancelled -> throw Halt(OperationResult.Cancelled)
        OperationResult.UnconfirmedCommit -> throw Halt(OperationResult.UnconfirmedCommit)
    }
    private fun <T> OperationResult<T>.unit(): OperationResult<Unit> = when (this) {
        is OperationResult.Completed -> OperationResult.Completed(Unit)
        is OperationResult.Failed -> this
        OperationResult.Cancelled -> OperationResult.Cancelled
        OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
    }
    private fun action(kind: TransferKind) = when (kind) {
        TransferKind.IMPORT -> ProtectedAction.IMPORT_BOARD
        TransferKind.EXPORT -> ProtectedAction.EXPORT_BOARD
        TransferKind.BACKUP -> ProtectedAction.CREATE_BACKUP
        TransferKind.RESTORE, TransferKind.RETURN_TO_RECOVERY -> ProtectedAction.RESTORE_BACKUP
        TransferKind.DELETE_RECOVERY -> ProtectedAction.DELETE_RECOVERY
    }
    private fun matches(kind: TransferKind, confirmation: TransferConfirmation) = when (kind) {
        TransferKind.IMPORT -> confirmation is TransferConfirmation.ImportNew || confirmation is TransferConfirmation.ImportReplace
        TransferKind.EXPORT, TransferKind.BACKUP -> confirmation == TransferConfirmation.WriteFile
        TransferKind.RESTORE -> confirmation == TransferConfirmation.ReplaceWholeLibrary
        TransferKind.RETURN_TO_RECOVERY -> confirmation == TransferConfirmation.ReturnToRecovery
        TransferKind.DELETE_RECOVERY -> confirmation == TransferConfirmation.DeleteRecovery
    }
}
