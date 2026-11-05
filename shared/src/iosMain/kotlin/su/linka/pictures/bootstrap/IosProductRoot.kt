@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

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
    val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<RootState>(RootState.Loading)
    val state = mutableState.asStateFlow()
    private val owners = OwnerWindows()
    private val windows = mutableMapOf<String, Window>() // UIKit/Main only.
    private val observers = mutableListOf<Any>()
    private val reload = Mutex()
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
                value?.windowScene?.let { sceneReference = WeakReference(it) }
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
    )

    init {
        observe(UIApplicationDidEnterBackgroundNotification) { background(null) }
        observe(UISceneDidEnterBackgroundNotification) { background(it?.`object` as? UIScene) }
        observe(UIApplicationWillResignActiveNotification) {
            windows.keys.forEach { owners.setActive(it, false) }
        }
        observe(UISceneWillDeactivateNotification) { notice ->
            val scene = notice?.`object` as? UIScene ?: return@observe
            windows.values.filter { it.scene === scene }.forEach { owners.setActive(it.id, false) }
        }
        observe(UIApplicationDidBecomeActiveNotification) { updateWindows(); notifyForeground() }
        observe(UISceneDidActivateNotification) { updateWindows(); notifyForeground() }
        observe(UISceneDidDisconnectNotification) { notice ->
            val scene = notice?.`object` as? UIScene ?: return@observe
            windows.values.filter { it.scene === scene }.toList().forEach { closeWindow(it) }
        }
        observe(UIWindowDidBecomeHiddenNotification) { notice ->
            val native = notice?.`object` as? UIWindow ?: return@observe
            windows.values.filter { it.native === native }.forEach { window ->
                owners.setActive(window.id, false)
                owners.owners(window.id).forEach { owner -> access?.revokeOwner(owner); files?.revoke(owner) }
                audio?.interrupt()
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
        access?.revokeOwner(owner)
        audio?.stop(owner)
        files?.revoke(owner)
    }

    fun closeWindow(window: Window) {
        check(NSThread.isMainThread)
        if (windows.remove(window.id) !== window) return
        val closedOwners = owners.closeWindow(window.id)
        closedOwners.forEach { owner -> access?.revokeOwner(owner); audio?.stop(owner); files?.revoke(owner) }
        audio?.interrupt()
        graph?.thumbnails?.remove(window.id)
        window.native = null
        window.foreground.value = false
        window.scope.cancel()
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
                    access?.revokeAll()
                    audio?.interrupt()
                    windows.values.flatMap { owners.owners(it.id) }.forEach { files?.revoke(it) }
                    closeSession()
                    if (closed) return@withLock
                    settings.value = store.library.settings().required()
                    createSession(store, checkNotNull(audio))
                    lastEpoch = actual
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
            }, owners::active, scope)
        val ownedFiles = OwnedFiles(nativeFiles, owners)
        files = ownedFiles
        val decoder = IosAudioDecoder(ownedFiles)
        val inspection = IosMediaInspectionPort(decoder::inspect)
        settings.value = store.library.settings().required()
        val recording = RecordingOwners(::ownerForeground, assistant::suspendForSystemPrompt)
        val backend = IosAudioBackend(ownedFiles, inspection, decoder::decodeToWave, recording::permission,
            { UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive }, scope)
        val coordinator = AudioCoordinator(backend, store.media, ownedFiles, scope) { settings.value?.languageTag }
        val port = recording.bind(coordinator)
        audio = port
        val thumbnails = ThumbnailLoader(store.media, IosThumbnailDecoder(inspection)::decode, scope)
        createSession(store, port)
        lastEpoch = store.library.listing.value.epoch
        val ready = Graph(store, assistant, port, ownedFiles, IosImagePreparationPort(ownedFiles, inspection), inspection,
            thumbnails, ids, session.asStateFlow())
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

    private suspend fun closeSession() {
        val old = session.value
        session.value = null
        old?.close()
        val parent = sessionJob
        parent?.cancelAndJoin()
        sessionJob = null
    }

    private fun ownerForeground(owner: String): Boolean {
        check(NSThread.isMainThread)
        val window = owners.window(owner)?.let(windows::get) ?: return false
        return !closed && owners.active(owner) && active(window)
    }

    private fun active(window: Window): Boolean {
        val native = window.controller?.viewIfLoaded?.window ?: return false
        return !native.hidden && UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive &&
            (native.windowScene?.activationState?.let { it == UISceneActivationStateForegroundActive } ?: true)
    }

    private fun updateWindows() {
        windows.values.toList().forEach { window ->
            if (window.controller == null) { closeWindow(window); return@forEach }
            window.controller?.viewIfLoaded?.window?.let { window.native = it }
            owners.setActive(window.id, !closed && active(window))
            val native = window.controller?.viewIfLoaded?.window
            // Inactive (including our OS prompts) is not a real-background UI event.
            window.foreground.value = !closed && native != null && !native.hidden &&
                UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateBackground &&
                native.windowScene?.activationState != UISceneActivationStateBackground
        }
    }

    private fun background(scene: UIScene?) {
        val affected = windows.values.filter { scene == null || it.scene === scene }
        // Security/audio revocation is synchronous and strictly before the UI's background state.
        if (scene == null) access?.revokeAll()
        else affected.flatMap { owners.owners(it.id) }.forEach { access?.revokeOwner(it) }
        audio?.interrupt()
        affected.forEach { window ->
            owners.setActive(window.id, false)
            window.foreground.value = false
            owners.owners(window.id).forEach { files?.revoke(it) }
        }
    }

    private fun notifyForeground() {
        windows.values.flatMap { owners.owners(it.id) }.filter(::ownerForeground).forEach { verifier?.onForeground(it) }
    }

    private fun observe(name: String?, action: (NSNotification?) -> Unit) {
        observers += NSNotificationCenter.defaultCenter.addObserverForName(name, null, NSOperationQueue.mainQueue, action)
    }

    private fun fail(problem: Problem) {
        mutableState.value = RootState.Failed(problem)
        close()
    }

    fun close() {
        check(NSThread.isMainThread)
        if (!closing.compareAndSet(false, true)) return
        access?.revokeAll()
        audio?.interrupt()
        windows.values.toList().forEach(::closeWindow)
        observers.forEach { runCatching { NSNotificationCenter.defaultCenter.removeObserver(it) } }
        observers.clear()
        // Independent but owned cleanup survives cancellation of every root/UI worker.
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanup.launch {
            withContext(NonCancellable) {
                runCatching { reload.withLock { closeSession() } }
                runCatching { job.cancelAndJoin() }
                runCatching { storage?.close() }.onFailure {
                    mutableState.value = RootState.Failed(Problem(ProblemCode.IO_FAILURE))
                }
                runCatching { withContext(Dispatchers.IO) {
                    temporaryRoot?.let { FileSystem.SYSTEM.deleteRecursively(it, mustExist = false) }
                } }
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
