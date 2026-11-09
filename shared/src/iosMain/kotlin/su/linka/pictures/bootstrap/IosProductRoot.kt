@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class,
    kotlin.experimental.ExperimentalNativeApi::class)

package su.linka.pictures.bootstrap

import androidx.sqlite.driver.NativeSQLiteDriver
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
import su.linka.pictures.storage.attempt
import su.linka.pictures.ui.images.ThumbnailLoader
import org.jetbrains.compose.resources.getString
import su.linka.pictures.resources.Res
import su.linka.pictures.resources.ios_owner_verification_reason
import su.linka.pictures.identity.*
import su.linka.pictures.migration.*
import su.linka.pictures.portability.linka.LinkaCodec

internal sealed interface RootState {
    data object Loading : RootState
    data object StartupResume : RootState
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
    internal val identity = IdentitySession(IosIdentityDevice(), scope)
    private val mutableState = MutableStateFlow<RootState>(RootState.Loading)
    val state = mutableState.asStateFlow()
    private val owners = OwnerWindows()
    private val windows = mutableMapOf<String, Window>() // UIKit/Main only.
    private val observers = mutableListOf<Any>()
    private val reload = Mutex()
    private val recovery = RootRecovery()
    private var storage: StorageRuntime? = null
    private var temporaryRoot: Path? = null
    private var rawFiles: Pair<Path, PlatformFilesPort>? = null
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
            start(false)
        }
    }

    fun resumeStartup() {
        check(NSThread.isMainThread)
        if (closed || mutableState.value != RootState.StartupResume) return
        mutableState.value = RootState.Loading
        scope.launch { start(true) }
    }

    private suspend fun start(resume: Boolean) {
        try { open(resume) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RootFailure) { fail(failure.problem) }
        catch (_: Throwable) { fail(Problem(ProblemCode.IO_FAILURE)) }
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
                             CoroutineScope(scope.coroutineContext + parent), identity.telemetry::communication)
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

    private suspend fun open(resume: Boolean) {
        val fs = FileSystem.SYSTEM
        val paths = withContext(Dispatchers.IO) {
            val application = directory(NSApplicationSupportDirectory) / "LINKa"
            fs.createDirectories(application)
            val canonical = fs.canonicalize(application)
            val root = startupRoot(canonical, fs)
            val cache = temporaryRoot ?: run {
                val cacheParent = directory(NSCachesDirectory) / "LINKa"
                fs.createDirectories(cacheParent)
                val next = fs.canonicalize(cacheParent) / ids.next()
                fs.createDirectory(next, mustCreate = true)
                temporaryRoot = next
                next
            }
            Paths(root, cache, root == canonical / "library-v1")
        }
        val credentials = KeychainCredentialStore()
        val guard: CommitGuard = {
            if (job.isActive && !closed) OperationResult.Completed(Unit) else OperationResult.Cancelled
        }
        val store: StorageRuntime
        val nativeFiles: PlatformFilesPort
        if (paths.direct) {
            admitRootCredentials(credentials, true, guard).required()
            // Assignment must survive cancellation of startup: cleanup must see every opened writer.
            store = withContext(NonCancellable) {
                StorageRuntime.open(paths.root, fs, NativeSQLiteDriver()).required().also { storage = it }
            }
            nativeFiles = platformFiles(paths.root, paths.temporary, fs)
        } else {
            val receipts = AdmissionFiles(paths.root, fs, ids)
            val (active, journal) = withContext(Dispatchers.IO) { attempt {
                val active = receipts.read("active")
                active to if (active == null) receipts.read("journal") else null
            }.required() }
            if (startupNeedsResume(active, journal) && !resume) {
                mutableState.value = RootState.StartupResume
                return
            }
            val importer = LegacyCredentialImporter(credentials)
            val upgrade = LegacyUpgrade(paths.root, StarterMigrationSource(), fs, NativeSQLiteDriver(),
                { generation -> platformFiles(generation, paths.temporary, fs) }, ids,
                readSettings = { LegacyUpgradeSettings(emptyMap(), false) },
                prepareCredentials = { importer.prepare(false, null, guard) },
                validateCredentials = importer::validate,
                readBoard = { file, name, media, registered ->
                    val decoder = IosAudioDecoder(registered)
                    LinkaCodec(media, registered, IosMediaInspectionPort(decoder::inspect), ids).readLegacyUpgrade(file, name)
                })
            val opened = withContext(NonCancellable) {
                upgrade.open(resume).required().also { storage = it.runtime }
            }
            store = opened.runtime
            nativeFiles = opened.files
        }
        currentCoroutineContext().ensureActive()
        credentials.allowDeviceBackup(guard).required()
        val verification = IosDeviceOwnerVerifier(getString(Res.string.ios_owner_verification_reason),
            { request, owner -> access?.isVerificationCurrent(request, owner) == true },
            ::ownerForeground, { access?.suspendForSystemPrompt() }, scope)
        verifier = verification
        val assistant = LocalAssistantAccess.open(credentials, verification, ids).required()
        access = assistant
        assistant.refreshPrerequisites()
        val ownedFiles = OwnedFiles(nativeFiles, owners)
        files = ownedFiles
        val decoder = IosAudioDecoder(ownedFiles)
        val inspection = IosMediaInspectionPort(decoder::inspect)
        settings.value = store.library.settings().required()
        val recording = RecordingOwners(::ownerForeground, assistant::suspendForSystemPrompt, recovery::denied)
        val backend = IosAudioBackend(ownedFiles, inspection, decoder::preparePlayback, recording::permission,
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

    private fun platformFiles(root: Path, temporary: Path, fs: FileSystem): PlatformFilesPort {
        rawFiles?.let {
            if (it.first != root) throw RootFailure(Problem(ProblemCode.CONFLICT))
            return it.second
        }
        return IosPlatformFilesPort(temporary, root / "media", fs,
            { owner ->
                val assistant = access
                if (assistant == null || !ownerForeground(owner)) null else {
                    val controller = owners.window(owner)?.let { windows[it]?.controller }
                    if (controller != null && controller.presentedViewController == null) assistant.suspendForSystemPrompt()
                    controller
                }
            }, { owner -> recovery.denied() == null && owners.active(owner) }, scope)
            .also { rawFiles = root to it }
    }

    private fun createSession(store: StorageRuntime, port: AudioPort) {
        check(session.value == null && sessionJob == null)
        val parent = SupervisorJob(job)
        sessionJob = parent
        session.value = CommunicationSession(store.library, store.messages, store.media, port, ids,
             CoroutineScope(scope.coroutineContext + parent), identity.telemetry::communication)
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

    private data class Paths(val root: Path, val temporary: Path, val direct: Boolean)

    internal companion object {
        fun startupRoot(application: Path, fs: FileSystem): Path {
            val direct = application / "library-v1"
            val starter = application / "starter-v1"
            val existing = fs.exists(direct)
            if (existing && fs.exists(starter) && fs.list(starter).isNotEmpty()) throw RootFailure(Problem(ProblemCode.CONFLICT))
            return if (existing) direct else starter
        }

        fun startupNeedsResume(active: AdmissionRecord?, journal: AdmissionRecord?): Boolean {
            if (active != null || journal == null) return false
            if (journal.phase == MigrationPhase.UNCONFIRMED) throw RootFailure(Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
            if (journal.sourceKind != MigrationSourceKind.BUNDLED || journal.total != 5 || journal.phase == MigrationPhase.COMMITTED)
                throw RootFailure(Problem(ProblemCode.CONFLICT))
            return true
        }
    }
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
