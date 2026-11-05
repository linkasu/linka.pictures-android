package su.linka.pictures.ui.app

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.communication.*
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.portability.TransferCoordinator
import su.linka.pictures.application.portability.TransferPhase
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.backup.BackupCodec
import su.linka.pictures.portability.linka.LinkaCodec

internal enum class ProductPage { COMMUNICATION, LIBRARY, EDITOR, SETTINGS, ACCESS, TRANSFER }

internal class ProductRoute(val page: ProductPage, val owner: String, val scope: CoroutineScope,
    val editor: AuthoringSession? = null, val transfer: TransferCoordinator? = null,
    val initialAction: AuthoringAction? = null, val boardId: BoardId? = null) {
    val capability = MutableStateFlow<AssistantCapability?>(null)
    var deliveredEpoch: Long? = null
    var closed = false
}

/** Only app navigation lives here; message/page/mode state remains in CommunicationSession. */
internal class ProductNavigation(private val library: LibraryStore, private val media: MediaStore,
    private val messages: MessageStore, private val access: AssistantAccess, private val audio: AudioPort,
    private val files: PlatformFilesPort, private val images: ImagePreparationPort,
    private val inspection: MediaInspectionPort, private val ids: IdSource,
    private val rootScope: CoroutineScope, private val communication: CommunicationSession,
    private val controller: LibraryController,
    private val onOwnerOpened: (String) -> Unit, private val onOwnerClosed: (String) -> Unit,
    private val onLibraryReplaced: (Long) -> Unit) {
    private val transition = Mutex()
    private val committedNotifications = Mutex()
    private val mutableRoute = MutableStateFlow<ProductRoute?>(null)
    val route: StateFlow<ProductRoute?> = mutableRoute.asStateFlow()
    private val mutableTransitioning = MutableStateFlow(false)
    val transitioning = mutableTransitioning.asStateFlow()
    private var disposed = false

    suspend fun open(page: ProductPage, action: AuthoringAction? = null, boardId: BoardId? = null) = transition.withLock {
        if (disposed) return@withLock
        if (controller.requiresRestart.value && page !in setOf(ProductPage.LIBRARY, ProductPage.COMMUNICATION)) return@withLock
        mutableTransitioning.value = true
        try {
            audio.interrupt()
            communication.dispatch(CommunicationCommand.Stop(CommandId(ids.next())))
            mutableRoute.value?.let { close(it) }
            mutableRoute.value = null
            if (disposed || page == ProductPage.COMMUNICATION) return@withLock
            currentCoroutineContext().ensureActive()
            val owner = "product-${ids.next()}"
            val child = CoroutineScope(rootScope.coroutineContext + SupervisorJob(rootScope.coroutineContext[Job]))
            onOwnerOpened(owner)
            val next = ProductRoute(page, owner, child,
                editor = if (page == ProductPage.EDITOR) AuthoringSession(library, media, audio, files, images, inspection, access, ids, child) else null,
                transfer = if (page == ProductPage.TRANSFER) TransferCoordinator(library, messages,
                    LinkaCodec(media, files, inspection, ids), BackupCodec(media, files, inspection, library),
                    files, access, ids, child) else null,
                initialAction = action, boardId = boardId)
            mutableRoute.value = next
            next.transfer?.let { coordinator ->
                child.launch { coordinator.state.collect { states ->
                    if (states.values.any { it.transfer.phase == TransferPhase.UNCONFIRMED }) controller.markCommitUnconfirmed()
                    notifyCommitted(next)
                } }
            }
            next.editor?.let { editor ->
                child.launch { editor.state.collect {
                    if (it.phase == AuthoringPhase.UNCONFIRMED || it.savePhase == DraftSavePhase.UNCONFIRMED) controller.markCommitUnconfirmed()
                } }
            }
        } finally { mutableTransitioning.value = false }
    }

    fun background() {
        // Root also revokes synchronously at native lifecycle delivery, before this composition effect.
        access.revokeAll()
        audio.interrupt()
        pauseProtectedOwner(AuthoringAction.Background)
    }

    fun revokeProtectedAccess() = pauseProtectedOwner(AuthoringAction.Revoke)

    private fun pauseProtectedOwner(action: AuthoringAction) {
        val current = route.value ?: return
        current.capability.value = null
        current.editor?.send(AuthoringCommand(CommandId(ids.next()), current.owner, null, action))
        rootScope.launch { current.transfer?.revokePending(current.owner) }
    }

    fun dispose() {
        disposed = true
        val current = route.value ?: return
        invalidate(current)
        rootScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { transition.withLock { close(current); mutableRoute.value = null } }
        }
    }

    private fun invalidate(current: ProductRoute) {
        current.editor?.send(AuthoringCommand(CommandId(ids.next()), current.owner, null, AuthoringAction.Revoke))
        current.capability.value = null
        audio.stop(current.owner)
        access.revokeOwner(current.owner)
    }

    private suspend fun close(current: ProductRoute) {
        if (current.closed) return
        current.closed = true
        invalidate(current)
        // Close native admission before cancelling workers; await transfer-owned handle cleanup first.
        onOwnerClosed(current.owner)
        withContext(NonCancellable) {
            try {
                current.transfer?.revokePending(current.owner)
                if (current.transfer?.state?.value?.values?.any { it.transfer.phase == TransferPhase.UNCONFIRMED } == true)
                    controller.markCommitUnconfirmed()
                notifyCommitted(current)
            }
            finally { current.scope.coroutineContext[Job]?.cancelAndJoin() }
        }
    }

    private suspend fun notifyCommitted(current: ProductRoute) = committedNotifications.withLock {
        val epoch = current.transfer?.state?.value?.values?.filter { it.ownerId == current.owner }
            ?.mapNotNull { it.stateCommittedEpoch }?.maxOrNull()
        if (epoch != null && epoch > (current.deliveredEpoch ?: -1)) {
            current.deliveredEpoch = epoch
            onLibraryReplaced(epoch)
        }
    }
}
