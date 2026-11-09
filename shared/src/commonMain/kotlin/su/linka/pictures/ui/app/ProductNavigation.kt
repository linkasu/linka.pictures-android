package su.linka.pictures.ui.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.communication.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.portability.TransferCoordinator
import su.linka.pictures.application.portability.TransferPhase
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.backup.BackupCodec
import su.linka.pictures.portability.linka.LinkaCodec
import su.linka.pictures.identity.ProductTelemetry
import su.linka.pictures.application.portability.TransferKind

internal enum class ProductPage { COMMUNICATION, LIBRARY, EDITOR, SETTINGS, ACCESS, TRANSFER }

internal class ProductRoute(val page: ProductPage, val owner: String, val scope: CoroutineScope,
    val editor: AuthoringSession? = null, val transfer: TransferCoordinator? = null,
    val initialAction: AuthoringAction? = null, val boardId: BoardId? = null) {
    val capability = MutableStateFlow<AssistantCapability?>(null)
    var deliveredEpoch: Long? = null
    var reloadFailed = false
    var closeFailed = false
    val invalidationStarted = MutableStateFlow(false)
    val invalidationFinished = CompletableDeferred<Unit>()
    val retirementScheduled = MutableStateFlow(false)
    val closeResult = CompletableDeferred<Boolean>()
}

/** Only app navigation lives here; message/page/mode state remains in CommunicationSession. */
internal class ProductNavigation(private val library: LibraryStore, private val media: MediaStore,
    private val messages: MessageStore, private val access: AssistantAccess, private val audio: AudioPort,
    private val files: PlatformFilesPort, private val images: ImagePreparationPort,
    private val inspection: MediaInspectionPort, private val ids: IdSource,
    private val rootScope: CoroutineScope, private val communication: CommunicationSession,
    private val controller: LibraryController,
    private val onOwnerOpened: (String) -> Unit, private val onOwnerClosed: (String) -> Unit,
    private val onLibraryReplaced: (Long) -> Unit, private val telemetry: ProductTelemetry? = null) {
    private val transition = Mutex()
    private val committedNotifications = Mutex()
    private val mutableRoute = MutableStateFlow<ProductRoute?>(null)
    val route: StateFlow<ProductRoute?> = mutableRoute.asStateFlow()
    private val mutableTransitioning = MutableStateFlow(false)
    val transitioning = mutableTransitioning.asStateFlow()
    val problem = controller.restartProblem
    private val mutableCommittedEpoch = MutableStateFlow<Long?>(null)
    val committedEpoch = mutableCommittedEpoch.asStateFlow()
    private val disposed = MutableStateFlow(false)
    private val started = MutableStateFlow(false)

    suspend fun start() {
        if (started.compareAndSet(false, true)) open(ProductPage.LIBRARY)
    }

    suspend fun open(page: ProductPage, action: AuthoringAction? = null, boardId: BoardId? = null) = transition.withLock {
        if (disposed.value) return@withLock
        if (problem.value?.code == ProblemCode.COMMIT_OUTCOME_UNKNOWN && page !in setOf(ProductPage.LIBRARY, ProductPage.COMMUNICATION)) return@withLock
        mutableTransitioning.value = true
        try {
            val previous = mutableRoute.value
            previous?.let { beginClose(it) }
            val closed = previous?.let { close(it) } ?: true
            mutableRoute.value = null
            if (!closed || disposed.value || page == ProductPage.COMMUNICATION || problem.value?.code == ProblemCode.IO_FAILURE) return@withLock
            when (page) {
                ProductPage.EDITOR, ProductPage.SETTINGS, ProductPage.ACCESS, ProductPage.TRANSFER -> {
                    if (page != ProductPage.SETTINGS || boardId == null) {
                        cleanup { audio.interrupt() }
                        cleanup { communication.dispatch(CommunicationCommand.Stop(CommandId(ids.next()))) }
                    }
                    if (problem.value?.code == ProblemCode.IO_FAILURE) return@withLock
                }
                else -> Unit
            }
            currentCoroutineContext().ensureActive()
            val owner = "product-${ids.next()}"
            val child = CoroutineScope(rootScope.coroutineContext + SupervisorJob(rootScope.coroutineContext[Job]))
            if (!cleanup { onOwnerOpened(owner) }) {
                close(ProductRoute(page, owner, child))
                return@withLock
            }
            val next = try { ProductRoute(page, owner, child,
                editor = if (page == ProductPage.EDITOR) AuthoringSession(library, media, audio, files, images, inspection, access, ids, child,
                    { created, count, completed -> telemetry?.setSaved(created, count, completed) }) else null,
                transfer = if (page == ProductPage.TRANSFER) TransferCoordinator(library, messages,
                    LinkaCodec(media, files, inspection, ids), BackupCodec(media, files, inspection, library),
                    files, access, ids, child) else null,
                initialAction = action, boardId = boardId)
            } catch (_: Throwable) {
                failLifecycle()
                close(ProductRoute(page, owner, child))
                return@withLock
            }
            if (disposed.value || !currentCoroutineContext().isActive) {
                close(next)
                currentCoroutineContext().ensureActive()
                return@withLock
            }
            mutableRoute.value = next
            when (page) {
                ProductPage.LIBRARY -> telemetry?.product("set_list_open")
                ProductPage.SETTINGS -> telemetry?.product("open_settings")
                else -> Unit
            }
            next.transfer?.let { coordinator ->
                val reported = mutableSetOf<OperationId>()
                child.launch { coordinator.state.collect { states ->
                    if (states.values.any { it.transfer.phase == TransferPhase.UNCONFIRMED }) controller.markCommitUnconfirmed()
                    states.values.filter { it.transfer.phase in setOf(TransferPhase.COMPLETED, TransferPhase.FAILED) && reported.add(it.transfer.id) }.forEach {
                        when (it.kind) {
                            TransferKind.IMPORT, TransferKind.RESTORE -> telemetry?.transfer(import = true, completed = it.transfer.phase == TransferPhase.COMPLETED)
                            TransferKind.EXPORT, TransferKind.BACKUP -> telemetry?.transfer(import = false, completed = it.transfer.phase == TransferPhase.COMPLETED)
                            else -> Unit
                        }
                    }
                    notifyCommitted(next)
                } }
            }
            next.editor?.let { editor ->
                var opened = false
                child.launch { editor.state.collect {
                    if (it.phase == AuthoringPhase.UNCONFIRMED || it.savePhase == DraftSavePhase.UNCONFIRMED) controller.markCommitUnconfirmed()
                    if (!opened && it.phase == AuthoringPhase.EDITING) { opened = true; telemetry?.product("editor_open") }
                } }
            }
        } finally { mutableTransitioning.value = false }
    }

    fun background() {
        // Root also revokes synchronously at native lifecycle delivery, before this composition effect.
        cleanup { access.revokeAll() }
        cleanup { audio.interrupt() }
        pauseProtectedOwner(AuthoringAction.Background)
    }

    fun revokeProtectedAccess() {
        val current = route.value ?: return
        cleanup(current) { access.revokeOwner(current.owner) }
        pauseProtectedOwner(AuthoringAction.Revoke)
    }

    private fun pauseProtectedOwner(action: AuthoringAction) {
        val current = route.value ?: return
        current.capability.value = null
        cleanup(current) { current.editor?.send(AuthoringCommand(CommandId(ids.next()), current.owner, null, action)) }
        rootScope.launch {
            withContext(NonCancellable) {
                cleanup(current) { current.transfer?.revokePending(current.owner) }
                cleanup(current) { notifyCommitted(current) }
                if (problem.value?.code == ProblemCode.IO_FAILURE) retireFailedOwner(current)
            }
        }
        if (problem.value?.code == ProblemCode.IO_FAILURE) retireFailedOwner(current)
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        route.value?.let { beginClose(it) }
        rootScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { transition.withLock {
                mutableRoute.value?.let { close(it) }
                mutableRoute.value = null
            } }
        }
    }

    private fun beginClose(current: ProductRoute) {
        if (!current.invalidationStarted.compareAndSet(false, true)) return
        try {
            // First external cleanup action: invalidate existing capabilities/commit guards, even if audio throws.
            cleanup(current) { access.revokeOwner(current.owner) }
            current.capability.value = null
            cleanup(current) { current.editor?.send(AuthoringCommand(CommandId(ids.next()), current.owner, null, AuthoringAction.Revoke)) }
            cleanup(current) { audio.stop(current.owner) }
            cleanup(current) { onOwnerClosed(current.owner) }
            recordAuthoringCleanup(current)
        } finally { current.invalidationFinished.complete(Unit) }
    }

    private suspend fun close(current: ProductRoute): Boolean = withContext(NonCancellable) {
        if (current.closeResult.isCompleted) return@withContext current.closeResult.await()
        beginClose(current)
        current.invalidationFinished.await()
        try {
            cleanup(current) { current.transfer?.revokePending(current.owner) }
            cleanup(current) {
                if (current.transfer?.state?.value?.values?.any { it.transfer.phase == TransferPhase.UNCONFIRMED } == true)
                    controller.markCommitUnconfirmed()
            }
        } finally {
            try {
                cleanup(current) { notifyCommitted(current) }
            } finally {
                cleanup(current) { current.scope.coroutineContext[Job]?.cancelAndJoin() }
                recordAuthoringCleanup(current)
                current.closeResult.complete(!current.closeFailed)
            }
        }
        current.closeResult.await()
    }

    private suspend fun notifyCommitted(current: ProductRoute) = committedNotifications.withLock {
        val epoch = current.transfer?.state?.value?.values?.filter { it.ownerId == current.owner }
            ?.mapNotNull { it.stateCommittedEpoch }?.maxOrNull()
        if (epoch != null) {
            mutableCommittedEpoch.update { previous -> maxOf(epoch, previous ?: -1) }
            if (!current.reloadFailed && epoch > (current.deliveredEpoch ?: -1)) {
                if (cleanup(current) { onLibraryReplaced(epoch) }) current.deliveredEpoch = epoch
                else {
                    current.reloadFailed = true
                    // The write is confirmed. Revoke admission, but never replay reload or cancel its returning result.
                    beginClose(current)
                }
            }
        }
    }

    private fun retireFailedOwner(current: ProductRoute) {
        beginClose(current)
        if (!current.retirementScheduled.compareAndSet(false, true)) return
        rootScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                transition.withLock {
                    close(current)
                    if (mutableRoute.value === current) mutableRoute.value = null
                }
            }
        }
    }

    private fun failLifecycle() = controller.haltForLifecycleFailure()

    private fun recordAuthoringCleanup(current: ProductRoute) {
        if (current.editor?.state?.value?.cleanupProblem != null) {
            current.closeFailed = true
            failLifecycle()
        }
    }

    private inline fun cleanup(current: ProductRoute? = null, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: Throwable) {
        current?.closeFailed = true
        failLifecycle()
        false
    }
}
