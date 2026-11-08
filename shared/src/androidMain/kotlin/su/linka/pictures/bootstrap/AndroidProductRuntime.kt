package su.linka.pictures.bootstrap

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.Path
import su.linka.pictures.access.*
import su.linka.pictures.application.*
import su.linka.pictures.application.communication.CommunicationSession
import su.linka.pictures.application.ports.*
import su.linka.pictures.audio.AudioCoordinator
import su.linka.pictures.domain.*
import su.linka.pictures.platform.audio.AndroidAudioBackend
import su.linka.pictures.platform.capabilities.*
import su.linka.pictures.platform.files.AndroidPlatformFilesPort
import su.linka.pictures.storage.StorageRuntime
import su.linka.pictures.migration.MigrationReport
import su.linka.pictures.ui.app.LinkaApp
import su.linka.pictures.ui.images.ThumbnailLoader
import su.linka.pictures.resources.*
import su.linka.pictures.identity.*

/** One process-wide graph for both Android packages; startup alone selects/adopts existing storage. */
class AndroidProductRuntime private constructor(private val application: Context) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val identity = IdentitySession(AndroidIdentityDevice(application), scope)
    private val ids = IdSource { UUID.randomUUID().toString() }
    private val windows = MutableStateFlow<Map<String, Window>>(emptyMap())
    private val owners = MutableStateFlow<Map<String, String>>(emptyMap())
    private val language = MutableStateFlow("ru-RU")
    private val session = MutableStateFlow<CommunicationSession?>(null)
    private val rootProblem = MutableStateFlow<Problem?>(null)
    @Volatile private lateinit var storage: StorageRuntime
    private lateinit var files: AndroidPlatformFilesPort
    private lateinit var audio: AudioCoordinator
    private lateinit var access: LocalAssistantAccess
    private lateinit var verification: AndroidDeviceOwnerVerifier
    private lateinit var thumbnails: ThumbnailLoader
    private val inspection = AndroidMediaInspectionPort()
    private lateinit var images: AndroidImagePreparationPort
    private var admittedEpoch = -1L
    private val mutableStartup = MutableStateFlow<OperationResult<Unit>?>(null)
    val startup = mutableStartup.asStateFlow()
    internal val migration = MutableStateFlow(MigrationReport())
    private var startupJob: Job? = null
    private var admission: AndroidLibraryAdmission? = null
    private var filesRoot: Path? = null
    @Volatile private var graphReady = false
    private val startupVisibility = StartupVisibility()

    fun windowStarted(owner: Any) { startupVisibility.start(owner); identity.telemetry.foreground("activity:${System.identityHashCode(owner)}", true) }

    fun windowStopped(owner: Any, changingConfiguration: Boolean) {
        identity.telemetry.foreground("activity:${System.identityHashCode(owner)}", false, changingConfiguration)
        if (startupVisibility.stop(owner, changingConfiguration)) backgroundStartup()
    }

    fun resumeStartup() {
        if (startupJob?.isActive == true || migration.value.requiresRestart || this::storage.isInitialized) return
        val previous = mutableStartup.value
        if (previous != OperationResult.Cancelled && (previous as? OperationResult.Failed)?.problem?.code != ProblemCode.INTERRUPTED) return
        beginStartup(true)
    }

    private fun backgroundStartup() {
        if (!graphReady && !this::storage.isInitialized) startupJob?.cancel()
    }

    private fun beginStartup(resume: Boolean) {
        mutableStartup.value = null
        startupJob = scope.launch {
            val result = try { initialize(resume) }
            catch (cancelled: CancellationException) { if (graphReady) OperationResult.Completed(Unit) else OperationResult.Cancelled }
            catch (_: Exception) { OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) }
            if (result !is OperationResult.Completed && this@AndroidProductRuntime::storage.isInitialized) {
                withContext(NonCancellable) { runCatching { storage.close() } }
                migration.value = migration.value.copy(requiresRestart = true)
                mutableStartup.value = result
                job.cancel()
            } else mutableStartup.value = result
        }
    }

    class Requests(
        val document: suspend (Intent, RequestId, String) -> OperationResult<Uri>,
        val microphone: suspend (RequestId, String) -> OperationResult<Unit>,
        val credential: suspend (Intent, RequestId, String) -> OperationResult<Unit>,
        val cancel: (RequestId) -> Unit,
        val foreground: () -> Boolean,
        val visible: () -> Boolean,
    )

    inner class Window internal constructor(private val callbacks: Requests) {
        private val id = ids.next()
        private val closed = MutableStateFlow(false)
        private val call = MutableStateFlow<RequestId?>(null)
        private val controlled = MutableStateFlow(false)
        private val visible = MutableStateFlow(callbacks.visible())
        private val stopped = MutableStateFlow(!visible.value)
        private val shown = MutableStateFlow(visible.value)
        private val incoming = MutableStateFlow<Uri?>(null)
        private val incomingProblem = MutableStateFlow<Problem?>(null)
        private var backHandler: (() -> Unit)? = null // Owned by this window's Main-thread composition.
        private var requestedImages: Set<AssetId> = emptySet()

        internal fun owns(owner: String): Boolean = !closed.value && owners.value[owner] == id
        internal fun foreground(): Boolean = !closed.value && !stopped.value && callbacks.foreground()
        internal fun active(): Boolean = !closed.value && visible.value

        internal suspend fun <T> request(id: RequestId, work: suspend () -> OperationResult<T>): OperationResult<T> = withContext(Dispatchers.Main.immediate) {
            if (closed.value) return@withContext OperationResult.Cancelled
            if (!call.compareAndSet(null, id)) return@withContext OperationResult.Failed(Problem(ProblemCode.CONFLICT))
            try { work() }
            finally {
                call.compareAndSet(id, null)
                controlled.value = false
                lifecycleChanged()
            }
        }

        fun systemPresentationStarted() {
            if (closed.value) return
            controlled.value = true
            access.suspendForSystemPrompt()
        }

        /** Called from native Activity lifecycle/window-focus delivery, not from a composable flag. */
        fun lifecycleChanged() {
            if (closed.value) return
            visible.value = !stopped.value && callbacks.visible()
            if (visible.value) thumbnails.request(id, requestedImages) else thumbnails.remove(id)
            if (!visible.value && !controlled.value) {
                owners.value.filterValues { it == id }.keys.forEach(access::revokeOwner)
                shown.value = false
            } else if (visible.value) shown.value = true
            if (!visible.value && windows.value.values.none { it.active() }) {
                audio.interrupt()
                session.value?.interrupt()
            }
            if (foreground()) verification.onForeground()
        }

        fun started() { stopped.value = false; lifecycleChanged() }
        fun stopped() { stopped.value = true; lifecycleChanged() }

        fun isOwnerOpen(owner: String): Boolean = owns(owner) && (visible.value || controlled.value)

        fun setBackHandler(handler: (() -> Unit)?) {
            if (!closed.value) backHandler = handler
        }

        fun handleBack(): Boolean {
            if (closed.value) return false
            if (!foreground()) return true
            val handler = backHandler ?: return false
            runCatching { handler() }.onFailure { failRoot(Problem(ProblemCode.IO_FAILURE)) }
            return true
        }

        fun incomingDocument(uri: Uri): OperationResult<Unit> {
            if (closed.value) return OperationResult.Cancelled
            if (!acceptsIncomingDocument(uri)) {
                val problem = Problem(ProblemCode.UNSAFE_ARCHIVE)
                incomingProblem.value = problem
                return OperationResult.Failed(problem)
            }
            access.suspendForSystemPrompt()
            incomingProblem.value = null
            incoming.value = uri
            return OperationResult.Completed(Unit)
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            backHandler = null
            val owned = owners.value.filterValues { it == id }.keys
            owned.forEach(access::revokeOwner)
            owners.update { current -> current - owned }
            runCatching { call.value?.let(callbacks.cancel) }.onFailure { failRoot(Problem(ProblemCode.IO_FAILURE)) }
            owned.forEach { owner -> runCatching { audio.stop(owner) }.onFailure { failRoot(Problem(ProblemCode.IO_FAILURE)) } }
            thumbnails.remove(id)
            incoming.value = null
            incomingProblem.value = null
            windows.update { it - id }
            if (windows.value.values.none { it.active() }) {
                audio.interrupt()
                session.value?.interrupt()
            }
        }

        @Composable
        fun Content() {
            val communication by session.collectAsState()
            val foreground by shown.collectAsState()
            val bitmaps by thumbnails.images.collectAsState()
            val problem by rootProblem.collectAsState()
            val imageProblem by thumbnails.problem.collectAsState()
            val incomingDocument by incoming.collectAsState()
            val incomingFailure by incomingProblem.collectAsState()
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                if (problem != null) Text(stringResource(Res.string.bootstrap_stopped, problem!!.code))
                if (imageProblem != null) Text(stringResource(Res.string.bootstrap_images, imageProblem!!.code))
                if (incomingDocument != null) Text(stringResource(Res.string.bootstrap_incoming))
                if (incomingFailure != null) Text(stringResource(Res.string.bootstrap_incoming_failed, incomingFailure!!.code))
                communication?.let { current -> Box(Modifier.weight(1f)) { LinkaApp(storage.library, storage.media, storage.messages, access, audio, files, images, inspection,
                    ids, scope, current, foreground, bitmaps,
                    { requestedImages = it; if (visible.value) thumbnails.request(id, it) },
                    { owner -> if (!closed.value) owners.update { it + (owner to id) } },
                    { owner -> access.revokeOwner(owner); owners.update { it - owner } },
                    ::libraryReplaced, onBackHandlerChanged = ::setBackHandler, identity = identity) } }
            }
        }

        internal fun register() { windows.update { it + (id to this) }; lifecycleChanged() }
        internal suspend fun document(intent: Intent, id: RequestId, owner: String) = request(id) {
            val archive = acceptsIncomingArchive(intent)
            val input = if (archive && owns(owner) && callbacks.foreground()) incoming.getAndUpdate { null } else null
            if (input != null) OperationResult.Completed(input) else callbacks.document(intent, id, owner)
        }
        internal suspend fun microphone(id: RequestId, owner: String) = request(id) { callbacks.microphone(id, owner) }
        internal suspend fun credential(intent: Intent, id: RequestId, owner: String) = request(id) { callbacks.credential(intent, id, owner) }
        internal fun cancel(id: RequestId) { callbacks.cancel(id) }
    }

    fun attach(requests: Requests): Window = Window(requests).also { it.register() }
    private fun window(owner: String): Window? = owners.value[owner]?.let { windows.value[it] }?.takeIf { it.owns(owner) }

    private suspend fun initialize(resume: Boolean): OperationResult<Unit> = withContext(Dispatchers.IO) { initializeOnWorker(resume) }

    private suspend fun initializeOnWorker(resume: Boolean): OperationResult<Unit> {
        val fs = FileSystem.SYSTEM
        val temp = (application.cacheDir.absolutePath + "/pokazhi-temporaries").toPath()
        fs.createDirectories(temp)
        val selected = admission ?: AndroidLibraryAdmission(application, fs, BundledSQLiteDriver(), ids, inspection) { generation ->
            if (this::files.isInitialized) {
                check(filesRoot == generation)
                files
            } else AndroidPlatformFilesPort(application, fs.canonicalize(temp), generation / "media", fs,
                { intent, id, owner -> window(owner)?.document(intent, id, owner) ?: OperationResult.Cancelled },
                { owner -> window(owner)?.active() == true }, scope).also { files = it; filesRoot = generation }
        }.also { current ->
            admission = current
            scope.launch { current.report.collect { migration.value = it } }
        }
        when (val opened = selected.open(resume)) {
            is OperationResult.Completed -> storage = opened.value.runtime
            is OperationResult.Failed -> return opened
            OperationResult.Cancelled -> return OperationResult.Cancelled
            OperationResult.UnconfirmedCommit -> return OperationResult.UnconfirmedCommit
        }
        verification = AndroidDeviceOwnerVerifier(application, getString(Res.string.bootstrap_owner_title), getString(Res.string.bootstrap_owner_description),
            { intent, id, owner -> window(owner)?.credential(intent, id, owner) ?: OperationResult.Cancelled },
            { id -> windows.value.values.forEach { it.cancel(id) } },
            { id, owner -> this::access.isInitialized && window(owner) != null && access.isVerificationCurrent(id, owner) },
            { owner -> window(owner)?.foreground() == true },
            { if (this::access.isInitialized) access.suspendForSystemPrompt() })
        access = when (val opened = LocalAssistantAccess.open(selected.credentials, verification, ids)) {
            is OperationResult.Completed -> opened.value
            is OperationResult.Failed -> return opened
            else -> return failed(ProblemCode.IO_FAILURE)
        }
        language.value = when (val settings = storage.library.settings()) {
            is OperationResult.Completed -> settings.value.languageTag
            else -> return failed(ProblemCode.IO_FAILURE)
        }
        val backend = AndroidAudioBackend(application, files, inspection, { id ->
            val current = audio.state.value
            val owner = current.owner?.id
            if (current.requestId != id || owner == null) OperationResult.Cancelled
            else window(owner)?.microphone(id, owner) ?: OperationResult.Cancelled
        }, scope)
        audio = AudioCoordinator(backend, storage.media, files, scope) { language.value }
        images = AndroidImagePreparationPort(files, inspection)
        thumbnails = ThumbnailLoader(storage.media, AndroidThumbnailDecoder(inspection)::decode, scope)
        admittedEpoch = storage.library.listing.value.epoch
        session.value = CommunicationSession(storage.library, storage.messages, storage.media, audio, ids, scope, identity.telemetry::communication)
        scope.launch {
            storage.library.listing.collectLatest { stamp ->
                when (val settings = storage.library.settings()) {
                    is OperationResult.Completed -> if (storage.library.listing.value.epoch == stamp.epoch && storage.library.listing.value.revision == stamp.revision)
                        language.value = settings.value.languageTag
                    is OperationResult.Failed -> failRoot(settings.problem)
                    else -> failRoot(Problem(ProblemCode.IO_FAILURE))
                }
            }
        }
        access.refreshPrerequisites()
        graphReady = true
        return OperationResult.Completed(Unit)
    }

    private fun libraryReplaced(epoch: Long) {
        if (epoch == admittedEpoch) return
        if (storage.library.listing.value.epoch != epoch) { failRoot(Problem(ProblemCode.STALE_REQUEST)); return }
        access.revokeAll()
        audio.interrupt()
        session.value?.close()
        try {
            session.value = CommunicationSession(storage.library, storage.messages, storage.media, audio, ids, scope, identity.telemetry::communication)
            admittedEpoch = epoch
        } catch (_: Exception) { failRoot(Problem(ProblemCode.IO_FAILURE)) }
    }

    private fun failRoot(problem: Problem) {
        runCatching { access.block(problem) }
        runCatching { audio.interrupt() }
        runCatching { session.value?.interrupt() }
        rootProblem.value = problem
    }

    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))

    companion object {
        private var instance: AndroidProductRuntime? = null

        fun get(context: Context): AndroidProductRuntime {
            check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
            require(context.packageName == "su.linka.pictures" ||
                context.packageName == "su.linka.pictures.development" && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            return instance ?: AndroidProductRuntime(context.applicationContext).also {
                instance = it
                it.beginStartup(false)
            }
        }
    }
}
