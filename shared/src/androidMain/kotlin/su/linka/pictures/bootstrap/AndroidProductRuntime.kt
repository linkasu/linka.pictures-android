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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath
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
import su.linka.pictures.ui.app.LinkaApp
import su.linka.pictures.ui.images.ThumbnailLoader

/** Development sandbox only; normal-package startup is admitted by the separately reviewed legacy upgrade. */
class AndroidProductRuntime private constructor(private val application: Context) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val ids = IdSource { UUID.randomUUID().toString() }
    private val windows = MutableStateFlow<Map<String, Window>>(emptyMap())
    private val owners = MutableStateFlow<Map<String, String>>(emptyMap())
    private val language = MutableStateFlow("ru-RU")
    private val session = MutableStateFlow<CommunicationSession?>(null)
    private val rootProblem = MutableStateFlow<Problem?>(null)
    private lateinit var storage: StorageRuntime
    private lateinit var files: AndroidPlatformFilesPort
    private lateinit var audio: AudioCoordinator
    private lateinit var access: LocalAssistantAccess
    private lateinit var verification: AndroidDeviceOwnerVerifier
    private lateinit var thumbnails: ThumbnailLoader
    private val inspection = AndroidMediaInspectionPort()
    private lateinit var images: AndroidImagePreparationPort
    private var admittedEpoch = -1L

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
                if (problem != null) Text("Приложение остановило изменения: ${problem!!.code}. Перезапустите его для проверки сохранённых данных.")
                if (imageProblem != null) Text("Не удалось подготовить часть изображений: ${imageProblem!!.code}")
                if (incomingDocument != null) Text("Получен файл. Откройте библиотеку → Перенос → Импорт или восстановление, чтобы проверить его. Автоматического добавления нет.")
                if (incomingFailure != null) Text("Файл не открыт: ${incomingFailure!!.code}. Выберите файл через меню импорта; библиотека не изменена.")
                communication?.let { current -> Box(Modifier.weight(1f)) { LinkaApp(storage.library, storage.media, storage.messages, access, audio, files, images, inspection,
                    ids, scope, current, foreground, bitmaps,
                    { requestedImages = it; if (visible.value) thumbnails.request(id, it) },
                    { owner -> if (!closed.value) owners.update { it + (owner to id) } },
                    { owner -> access.revokeOwner(owner); owners.update { it - owner } },
                    ::libraryReplaced, onBackHandlerChanged = ::setBackHandler) } }
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

    private suspend fun initialize(): OperationResult<Unit> = withContext(Dispatchers.IO) { initializeOnWorker() }

    private suspend fun initializeOnWorker(): OperationResult<Unit> {
        val fs = FileSystem.SYSTEM
        val library = (application.filesDir.absolutePath + "/pokazhi-development").toPath()
        val temp = (application.cacheDir.absolutePath + "/pokazhi-temporaries").toPath()
        val credentials = FileCredentialStore((application.noBackupFilesDir.absolutePath + "/pokazhi-assistant").toPath(), fs, ids)
        val importer = LegacyCredentialImporter(credentials)
        when (val raw = credentials.read()) {
            is OperationResult.Completed -> if (raw.value == null) {
                if (fs.exists(library) && fs.list(library).isNotEmpty()) return failed(ProblemCode.INTEGRITY_FAILURE)
                val prepared = importer.prepare(false, null) { if (job.isActive) OperationResult.Completed(Unit) else OperationResult.Cancelled }
                if (prepared !is OperationResult.Completed) return prepared
            }
            is OperationResult.Failed -> return raw
            else -> return failed(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
        }
        val checked = importer.validate()
        if (checked !is OperationResult.Completed) return checked
        fs.createDirectories(library)
        fs.createDirectories(temp)
        verification = AndroidDeviceOwnerVerifier(application, "Восстановление доступа помощника", "Подтвердите владельца устройства",
            { intent, id, owner -> window(owner)?.credential(intent, id, owner) ?: OperationResult.Cancelled },
            { id -> windows.value.values.forEach { it.cancel(id) } },
            { id, owner -> this::access.isInitialized && window(owner) != null && access.isVerificationCurrent(id, owner) },
            { owner -> window(owner)?.foreground() == true },
            { if (this::access.isInitialized) access.suspendForSystemPrompt() })
        access = when (val opened = LocalAssistantAccess.open(credentials, verification, ids)) {
            is OperationResult.Completed -> opened.value
            is OperationResult.Failed -> return opened
            else -> return failed(ProblemCode.IO_FAILURE)
        }
        storage = when (val opened = StorageRuntime.open(fs.canonicalize(library), fs, BundledSQLiteDriver())) {
            is OperationResult.Completed -> opened.value
            is OperationResult.Failed -> return opened
            else -> return failed(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
        }
        // Storage admission must see a fresh empty root; it creates media/staging after schema acceptance.
        files = AndroidPlatformFilesPort(application, fs.canonicalize(temp), fs.canonicalize(library / "media"), fs,
            { intent, id, owner -> window(owner)?.document(intent, id, owner) ?: OperationResult.Cancelled },
            { owner -> window(owner)?.active() == true }, scope)
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
        session.value = CommunicationSession(storage.library, storage.messages, storage.media, audio, ids, scope)
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
        return OperationResult.Completed(Unit)
    }

    private fun libraryReplaced(epoch: Long) {
        if (epoch == admittedEpoch) return
        if (storage.library.listing.value.epoch != epoch) { failRoot(Problem(ProblemCode.STALE_REQUEST)); return }
        access.revokeAll()
        audio.interrupt()
        session.value?.close()
        try {
            session.value = CommunicationSession(storage.library, storage.messages, storage.media, audio, ids, scope)
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
        private val opening = Mutex()
        private var development: AndroidProductRuntime? = null
        private var blocked: Problem? = null

        suspend fun openDevelopment(context: Context): OperationResult<AndroidProductRuntime> = withContext(Dispatchers.Main.immediate) {
            opening.withLock {
                if (context.packageName != "su.linka.pictures.development" || context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
                    return@withLock OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
                blocked?.let { return@withLock OperationResult.Failed(it) }
                development?.let { return@withLock OperationResult.Completed(it) }
                val runtime = AndroidProductRuntime(context.applicationContext)
                try {
                    when (val ready = runtime.initialize()) {
                        is OperationResult.Completed -> { development = runtime; OperationResult.Completed(runtime) }
                        is OperationResult.Failed -> ready
                        OperationResult.Cancelled -> OperationResult.Cancelled
                        OperationResult.UnconfirmedCommit -> {
                            blocked = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
                            OperationResult.UnconfirmedCommit
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) }
                finally {
                    if (development !== runtime) {
                        withContext(NonCancellable) {
                            runtime.job.cancelAndJoin()
                            if (runtime::storage.isInitialized) runCatching { runtime.storage.close() }
                                .onFailure { blocked = Problem(ProblemCode.IO_FAILURE) }
                        }
                    }
                }
            }
        }
    }
}
