@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class,
    kotlin.experimental.ExperimentalNativeApi::class)

package su.linka.pictures.bootstrap

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.*
import platform.UIKit.*
import kotlin.native.ref.WeakReference
import su.linka.pictures.access.*
import su.linka.pictures.application.*
import su.linka.pictures.application.communication.CommunicationSession
import su.linka.pictures.application.communication.MessageSavePhase
import su.linka.pictures.application.ports.*
import su.linka.pictures.audio.AudioCoordinator
import su.linka.pictures.domain.PortableSettings
import su.linka.pictures.platform.audio.*
import su.linka.pictures.platform.capabilities.*
import su.linka.pictures.platform.files.IosPlatformFilesPort
import su.linka.pictures.storage.StorageRuntime
import su.linka.pictures.ui.images.ThumbnailLoader

internal sealed interface RootState {
    data object Loading : RootState
    data class Ready(val graph: IosProductRoot.Graph) : RootState
    data class Failed(val problem: Problem) : RootState
}

/** One writer/audio/session per process; UI owners and native presenters remain window-specific. */
internal class IosProductRoot {
    private val job = SupervisorJob()
    private val failures = CoroutineExceptionHandler { _, _ ->
        // Completion handlers can report rather than throw to cancel(); do not lose that cleanup failure.
        val report = { recordCleanup(CleanupAttempts(Problem(ProblemCode.IO_FAILURE))) }
        if (NSThread.isMainThread) report() else NSOperationQueue.mainQueue.addOperationWithBlock(report)
    }
    val scope = CoroutineScope(job + Dispatchers.Main.immediate + failures)
    private val mutableState = MutableStateFlow<RootState>(RootState.Loading)
    val state = mutableState.asStateFlow()
    private val owners = OwnerWindows()
    private val windows = mutableMapOf<String, Window>() // UIKit/Main only.
    private val observers = mutableListOf<Any>()
    private val reload = Mutex()
    private val recovery = RootRecovery()
    private var storage: StorageRuntime? = null
    private var temporaryRoot: Path? = null
    private var access: LocalAssistantAccess? = null
    private var files: OwnedFiles? = null
    private var audio: AudioPort? = null
    private var verifier: IosDeviceOwnerVerifier? = null
    private var graph: Graph? = null
    private val closing = MutableStateFlow(false)
    private val closed: Boolean get() = closing.value
    private var lastEpoch = -1L
    private var sessionJob: Job? = null
    private val session = MutableStateFlow<CommunicationSession?>(null)
    private val ids = IdSource { NSUUID().UUIDString }
    private val settings = MutableStateFlow<PortableSettings?>(null)

    internal class Window(val id: String, controller: UIViewController, val scope: CoroutineScope) {
        private val host = WeakReference(controller)
        val controller: UIViewController? get() = host.get()
        val foreground = MutableStateFlow(true)
        private var nativeReference: WeakReference<UIWindow>? = null
        private var sceneReference: WeakReference<UIWindowScene>? = null
        val scene: UIWindowScene? get() = sceneReference?.get()
        var native: UIWindow?
            get() = nativeReference?.get()
            set(value) {
                nativeReference = value?.let(::WeakReference)
                sceneReference = value?.windowScene?.let(::WeakReference)
            }
    }

    internal class Graph(
        val storage: StorageRuntime,
        val access: AssistantAccess,
        val audio: AudioPort,
        val files: PlatformFilesPort,
        val preparation: ImagePreparationPort,
        val inspection: MediaInspectionPort,
        val thumbnails: ThumbnailLoader,
        val ids: IdSource,
        val session: StateFlow<CommunicationSession?>,
        val notice: StateFlow<RootNotice>,
    )

    init {
        observe(UIApplicationDidEnterBackgroundNotification) { background(null) }
        observe(UISceneDidEnterBackgroundNotification) { background(it?.`object` as? UIScene) }
        observe(UIApplicationWillResignActiveNotification) {
            windows.keys.forEach { owners.setActive(it, false) }
        }
        observe(UISceneWillDeactivateNotification) { notice ->
            val scene = notice?.`object` as? UIScene ?: return@observe
            windows.values.filter { sameUIKitObject(it.scene, scene) }.forEach { owners.setActive(it.id, false) }
        }
        observe(UIApplicationDidBecomeActiveNotification) { updateWindows(); notifyForeground() }
        observe(UISceneDidActivateNotification) { updateWindows(); notifyForeground() }
        observe(UISceneDidDisconnectNotification) { notice ->
            val scene = notice?.`object` as? UIScene ?: return@observe
            windows.values.filter { sameUIKitObject(it.scene, scene) }.toList().forEach { closeWindow(it) }
        }
        observe(UIWindowDidBecomeHiddenNotification) { notice ->
            val native = notice?.`object` as? UIWindow ?: return@observe
            windows.values.filter { sameUIKitObject(it.native, native) }.forEach { window ->
                owners.setActive(window.id, false)
                val cleanup = CleanupAttempts(recovery.notice.value.problem)
                val affected = owners.owners(window.id)
                affected.forEach { owner -> cleanup.attempt { access?.revokeOwner(owner) } }
                cleanup.attempt { audio?.interrupt() }
                affected.forEach { owner -> cleanup.attempt { revokeFiles(owner) } }
                recordCleanup(cleanup)
            }
        }
        observe(UIWindowDidBecomeVisibleNotification) { updateWindows(); notifyForeground() }
        observe(UIApplicationWillTerminateNotification) { close() }
        scope.launch {
            try { open() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RootFailure) { fail(failure.problem) }
            catch (_: Throwable) { fail(Problem(ProblemCode.IO_FAILURE)) }
        }
    }

    fun openWindow(controller: UIViewController): Window {
        check(NSThread.isMainThread && !closed)
        val id = ids.next()
        owners.openWindow(id)
        return Window(id, controller, CoroutineScope(scope.coroutineContext + SupervisorJob(job))).also { windows[id] = it }
    }

    fun appeared(window: Window) {
        if (windows[window.id] !== window) return
        updateWindows()
        notifyForeground()
    }

    fun openOwner(owner: String, window: Window) {
        check(NSThread.isMainThread && windows[window.id] === window && !closed)
        owners.open(owner, window.id)
    }

    fun closeOwner(owner: String) {
        // Registry invalidation precedes access cancellation, files cleanup and child-scope cancellation.
        owners.close(owner)
        val cleanup = CleanupAttempts(recovery.notice.value.problem)
        cleanup.attempt { access?.revokeOwner(owner) }
        cleanup.attempt { audio?.stop(owner) }
        cleanup.attempt { revokeFiles(owner) }
        recordCleanup(cleanup)
    }

    fun closeWindow(window: Window) {
        check(NSThread.isMainThread)
        if (windows.remove(window.id) !== window) return
        val closedOwners = owners.closeWindow(window.id)
        window.native = null
        window.foreground.value = false
        val cleanup = CleanupAttempts(recovery.notice.value.problem)
        closedOwners.forEach { owner -> cleanup.attempt { access?.revokeOwner(owner) } }
        closedOwners.forEach { owner -> cleanup.attempt { audio?.stop(owner) } }
        cleanup.attempt { audio?.interrupt() }
        closedOwners.forEach { owner -> cleanup.attempt { revokeFiles(owner) } }
        cleanup.attempt { graph?.thumbnails?.remove(window.id) }
        val child = window.scope.coroutineContext[Job]
        child?.children?.toList()?.forEach { worker -> cleanup.attempt { worker.cancel() } }
        cleanup.attempt { child?.cancel() }
        recordCleanup(cleanup)
    }

    fun libraryReplaced(epoch: Long) {
        // Callback is total: never throw through the UI's committed-epoch notification.
        if (closed) return
        scope.launch {
            try {
                reload.withLock {
                    val store = storage ?: throw RootFailure(Problem(ProblemCode.STALE_REQUEST))
                    val actual = store.library.listing.value.epoch
                    if (epoch > actual) throw RootFailure(Problem(ProblemCode.STALE_REQUEST))
                    if (actual <= lastEpoch) return@withLock
                    if (!recovery.beginReplacement(actual)) { closeSession(); return@withLock }
                    val cleanup = CleanupAttempts()
                    cleanup.attempt { access?.revokeAll() }
                    cleanup.attempt { audio?.interrupt() }
                    windows.values.flatMap { owners.owners(it.id) }.forEach { owner -> cleanup.attempt { revokeFiles(owner) } }
                    cleanup.problem?.let { throw RootFailure(it) }
                    val nextSettings = store.library.settings().required()
                    val parent = SupervisorJob(job)
                    var published = false
                    var candidate: CommunicationSession? = null
                    try {
                        val next = CommunicationSession(store.library, store.messages, store.media, checkNotNull(audio), ids,
                            CoroutineScope(scope.coroutineContext + parent))
                        candidate = next
                        val loaded = next.state.first { it.savePhase != MessageSavePhase.LOADING }
                        if (loaded.problem != null) throw RootFailure(loaded.problem)
                        if (loaded.savePhase != MessageSavePhase.SAVED || loaded.stamp.epoch != actual || store.library.listing.value.epoch != actual)
                            throw RootFailure(Problem(ProblemCode.STALE_REQUEST))
                        recovery.notice.value.problem?.let { throw RootFailure(it) }
                        closeSession()?.let { throw RootFailure(it) }
                        if (closed) return@withLock
                        settings.value = nextSettings
                        sessionJob = parent
                        session.value = next
                        lastEpoch = actual
                        published = true
                        recovery.replacementReady()
                    } finally {
                        if (!published) retireSession(candidate, parent)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RootFailure) { fail(failure.problem) }
            catch (_: Throwable) { fail(Problem(ProblemCode.IO_FAILURE)) }
        }
    }

    private suspend fun open() {
        val fs = FileSystem.SYSTEM
        val paths = withContext(Dispatchers.IO) {
            val application = directory(NSApplicationSupportDirectory) / "LINKa"
            fs.createDirectories(application)
            val canonical = fs.canonicalize(application)
            val root = canonical / "library-v1"
            val existed = fs.exists(root)
            val cacheParent = directory(NSCachesDirectory) / "LINKa"
            fs.createDirectories(cacheParent)
            val cache = fs.canonicalize(cacheParent) / ids.next()
            fs.createDirectory(cache, mustCreate = true)
            temporaryRoot = cache
            Paths(root, cache, existed)
        }
        val credentials = KeychainCredentialStore()
        admitRootCredentials(credentials, paths.existed) {
            if (job.isActive && !closed) OperationResult.Completed(Unit) else OperationResult.Cancelled
        }.required()
        val verification = IosDeviceOwnerVerifier("Подтвердите владельца устройства для изменения защиты помощника",
            { request, owner -> access?.isVerificationCurrent(request, owner) == true },
            ::ownerForeground, { access?.suspendForSystemPrompt() }, scope)
        verifier = verification
        val assistant = LocalAssistantAccess.open(credentials, verification, ids).required()
        access = assistant
        assistant.refreshPrerequisites()
        // Assignment must survive cancellation of startup: cleanup must see every opened writer.
        val store = withContext(NonCancellable) {
            StorageRuntime.open(paths.root, fs, BundledSQLiteDriver()).required().also { storage = it }
        }
        currentCoroutineContext().ensureActive()
        val nativeFiles = IosPlatformFilesPort(paths.temporary, paths.root / "media", fs,
            { owner ->
                if (!ownerForeground(owner)) null else {
                    val controller = owners.window(owner)?.let { windows[it]?.controller }
                    if (controller != null && controller.presentedViewController == null) assistant.suspendForSystemPrompt()
                    controller
                }
            }, { owner -> recovery.denied() == null && owners.active(owner) }, scope)
        val ownedFiles = OwnedFiles(nativeFiles, owners)
        files = ownedFiles
        val decoder = IosAudioDecoder(ownedFiles)
        val inspection = IosMediaInspectionPort(decoder::inspect)
        settings.value = store.library.settings().required()
        val recording = RecordingOwners(::ownerForeground, assistant::suspendForSystemPrompt, recovery::denied)
        val backend = IosAudioBackend(ownedFiles, inspection, decoder::decodeToWave, recording::permission,
            { UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive }, scope)
        val coordinator = AudioCoordinator(backend, store.media, ownedFiles, scope) { settings.value?.languageTag }
        val port = recording.bind(coordinator)
        audio = port
        val thumbnails = ThumbnailLoader(store.media, IosThumbnailDecoder(inspection)::decode, scope)
        createSession(store, port)
        lastEpoch = store.library.listing.value.epoch
        val ready = Graph(store, RootAssistantAccess(assistant, recovery), port, ownedFiles, IosImagePreparationPort(ownedFiles, inspection), inspection,
            thumbnails, ids, session.asStateFlow(), recovery.notice)
        graph = ready
        mutableState.value = RootState.Ready(ready)
        updateWindows()
        scope.launch {
            try {
                store.library.listing.collectLatest { listing ->
                    val read = store.library.settings().required()
                    if (store.library.listing.value.let { it.epoch == listing.epoch && it.revision == listing.revision })
                        settings.value = read
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RootFailure) { fail(failure.problem) }
            catch (_: Throwable) { fail(Problem(ProblemCode.IO_FAILURE)) }
        }
    }

    private fun createSession(store: StorageRuntime, port: AudioPort) {
        check(session.value == null && sessionJob == null)
        val parent = SupervisorJob(job)
        sessionJob = parent
        session.value = CommunicationSession(store.library, store.messages, store.media, port, ids,
            CoroutineScope(scope.coroutineContext + parent))
    }

    private suspend fun closeSession(): Problem? {
        val old = session.value
        session.value = null
        val parent = sessionJob
        sessionJob = null
        return retireSession(old, parent)
    }

    private suspend fun retireSession(old: CommunicationSession?, parent: Job?): Problem? = withContext(NonCancellable) {
        val cleanup = CleanupAttempts()
        cleanup.attempt { old?.close() }
        parent?.children?.toList()?.forEach { worker -> cleanup.attempt { worker.cancel() } }
        cleanup.attempt { parent?.cancel() }
        cleanup.suspending { parent?.join() }
        cleanup.problem
    }

    private fun ownerForeground(owner: String): Boolean {
        check(NSThread.isMainThread)
        val window = owners.window(owner)?.let(windows::get) ?: return false
        return recovery.denied() == null && owners.active(owner) && snapshot(window).active
    }

    private fun snapshot(window: Window): RootWindowSnapshot {
        val current = window.controller?.viewIfLoaded?.window
        if (window.native == null && current != null) window.native = current
        val application = UIApplication.sharedApplication.applicationState
        return rootWindowSnapshot(!closed && windows[window.id] === window && window.controller != null,
            current, window.native, application == UIApplicationState.UIApplicationStateActive,
            application == UIApplicationState.UIApplicationStateBackground, { it.hidden },
            { it.windowScene?.activationState?.let { state -> state == UISceneActivationStateForegroundActive } ?: true },
            { it.windowScene?.activationState == UISceneActivationStateBackground }, ::sameUIKitObject)
    }

    private fun updateWindows() {
        windows.values.toList().forEach { window ->
            if (window.controller == null) { closeWindow(window); return@forEach }
            val snapshot = snapshot(window)
            owners.setActive(window.id, snapshot.active)
            // Inactive (including our OS prompts) is not a real-background UI event.
            window.foreground.value = snapshot.foreground
        }
    }

    private fun background(scene: UIScene?) {
        val affected = windows.values.filter { scene == null || sameUIKitObject(it.scene, scene) }
        val affectedOwners = affected.flatMap { owners.owners(it.id) }
        affected.forEach { owners.setActive(it.id, false) }
        val cleanup = CleanupAttempts(recovery.notice.value.problem)
        // Security/audio revocation is synchronous and strictly before the UI's background state.
        if (scene == null) cleanup.attempt { access?.revokeAll() }
        else affectedOwners.forEach { owner -> cleanup.attempt { access?.revokeOwner(owner) } }
        cleanup.attempt { audio?.interrupt() }
        affected.forEach { window ->
            window.foreground.value = false
        }
        affectedOwners.forEach { owner -> cleanup.attempt { revokeFiles(owner) } }
        recordCleanup(cleanup)
    }

    private fun notifyForeground() {
        windows.values.flatMap { owners.owners(it.id) }.filter(::ownerForeground).forEach { verifier?.onForeground(it) }
    }

    private fun observe(name: String?, action: (NSNotification?) -> Unit) {
        observers += NSNotificationCenter.defaultCenter.addObserverForName(name, null, NSOperationQueue.mainQueue, action)
    }

    private fun fail(problem: Problem) {
        val committed = storage?.library?.listing?.value?.epoch?.takeIf { it > lastEpoch && graph != null }
        val action = recovery.fail(problem, graph != null, committed)
        val primary = checkNotNull(recovery.notice.value.problem)
        val cleanup = CleanupAttempts(primary)
        // Actual native block is permanent, not a presentation-only unavailable flag.
        cleanup.attempt { access?.block(primary) }
        cleanup.attempt { audio?.interrupt() }
        windows.values.flatMap { owners.owners(it.id) }.forEach { owner -> cleanup.attempt { revokeFiles(owner) } }
        when (action) {
            RootFailureAction.CLOSE_STARTUP -> { mutableState.value = RootState.Failed(primary); close() }
            RootFailureAction.KEEP_VISIBLE_SESSION -> Unit // Keep the same graph, editor composition and in-memory phrase.
            RootFailureAction.INVALIDATE_REPLACED_SESSION -> scope.launch { reload.withLock { closeSession() } }
        }
    }

    private fun revokeFiles(owner: String) { files?.revoke(owner)?.let { throw RootFailure(it) } }

    private fun recordCleanup(cleanup: CleanupAttempts) {
        val problem = cleanup.problem ?: return
        if (closed) {
            recovery.fail(problem, false)
            mutableState.value = RootState.Failed(checkNotNull(recovery.notice.value.problem))
        } else if (recovery.notice.value.problem == null) fail(problem)
    }

    fun close() {
        check(NSThread.isMainThread)
        if (!closing.compareAndSet(false, true)) return
        windows.keys.forEach { owners.setActive(it, false) }
        val attempts = CleanupAttempts(recovery.notice.value.problem)
        attempts.attempt { access?.revokeAll() }
        attempts.attempt { audio?.interrupt() }
        windows.values.toList().forEach { window -> attempts.attempt { closeWindow(window) } }
        observers.forEach { token -> attempts.attempt { NSNotificationCenter.defaultCenter.removeObserver(token) } }
        observers.clear()
        // Independent but owned cleanup survives cancellation of every root/UI worker.
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + failures)
        cleanup.launch {
            withContext(NonCancellable) {
                attempts.suspending { reload.withLock { closeSession()?.let { throw RootFailure(it) } } }
                job.children.toList().forEach { worker -> attempts.attempt { worker.cancel() } }
                attempts.attempt { job.cancel() }
                attempts.suspending { job.join() }
                attempts.suspending { storage?.close() }
                attempts.suspending { withContext(Dispatchers.IO) {
                    temporaryRoot?.let { FileSystem.SYSTEM.deleteRecursively(it, mustExist = false) }
                } }
                recordCleanup(attempts)
            }
            cleanup.cancel()
        }
    }

    private data class Paths(val root: Path, val temporary: Path, val existed: Boolean)
    private fun directory(kind: NSSearchPathDirectory): Path =
        (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String
            ?: throw RootFailure(Problem(ProblemCode.IO_FAILURE))).toPath(normalize = true)
}

private class RootFailure(val problem: Problem) : Exception()
private fun <T> OperationResult<T>.required(): T = when (this) {
    is OperationResult.Completed -> value
    is OperationResult.Failed -> throw RootFailure(problem)
    OperationResult.Cancelled -> throw CancellationException("Root initialization cancelled")
    OperationResult.UnconfirmedCommit -> throw RootFailure(Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
}
