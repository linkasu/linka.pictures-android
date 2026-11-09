package su.linka.pictures.platform.files

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okio.Buffer
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Sink
import okio.Source
import okio.buffer
import okio.sink
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId
import su.linka.pictures.text.isNativeText

internal class AndroidPlatformFilesPort(
    context: Context,
    temporaryRoot: Path,
    mediaRoot: Path,
    private val fileSystem: FileSystem,
    private val requestDocument: suspend (Intent, RequestId, String) -> OperationResult<Uri>,
    private val isOwnerActive: (String) -> Boolean,
    scope: CoroutineScope,
) : PlatformFilesPort {
    private val resolver = context.applicationContext.contentResolver
    private val runtime = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val owned = OwnedFileHandles(temporaryRoot, mediaRoot, fileSystem, { runtime.isActive }) { wake.trySend(Unit) }
    init {
        runtime.launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { for (signal in wake) owned.drain() }
            finally { withContext(NonCancellable + Dispatchers.IO) { owned.shutdown(); wake.close(); owned.drain() } }
        }
    }

    override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> = NativeFileRules.result {
        NativeFileRules.worker(); if (!runtime.isActive) NativeFileRules.cancelled()
        NativeFileRules.leaf(suggestedName); NativeFileRules.mime(mediaType)
        owned.create(suggestedName, mediaType)
    }
    override fun source(file: LocalFile): OperationResult<Source> = NativeFileRules.result { owned.source(file) }
    override fun sink(file: TemporaryFile): OperationResult<Sink> = NativeFileRules.result { owned.sink(file) }
    override fun discard(file: TemporaryFile) { owned.discard(file) }

    private suspend fun live(owner: String) {
        currentCoroutineContext().ensureActive()
        if (!runtime.isActive || !isOwnerActive(owner)) NativeFileRules.cancelled()
    }
    private suspend fun present(intent: Intent, owner: String): Uri {
        if (owner.isEmpty() || !isNativeText(owner)) NativeFileRules.fail(ProblemCode.INVALID_DATA)
        live(owner)
        val uri = withContext(Dispatchers.Main.immediate) {
            live(owner)
            NativeFileRules.value(requestDocument(intent, RequestId(UUID.randomUUID().toString()), owner))
        }
        live(owner)
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrEmpty() || !isNativeText(uri.toString())) NativeFileRules.fail(ProblemCode.INVALID_DATA)
        return uri
    }

    override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> = perform {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        when (kind) {
            PickedFileKind.IMAGE -> intent.type = "image/*"
            PickedFileKind.AUDIO -> intent.type = "audio/*"
            PickedFileKind.LINKA, PickedFileKind.BACKUP -> {
                intent.type = "*/*"
                intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
            }
        }
        val uri = present(intent, ownerId)
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) name = cursor.getString(index)
        }
        name?.let(NativeFileRules::leaf)
        val type = resolver.getType(uri)?.also(NativeFileRules::mime)
        live(ownerId)
        val target = owned.create(name, type)
        var delivered = false
        try {
            (resolver.openInputStream(uri) ?: throw FileNotFoundException("Document unavailable")).use { input ->
                NativeFileRules.value(sink(target)).buffer().use { output ->
                    val buffer = ByteArray(8192)
                    var bytes = 0L
                    while (true) {
                        live(ownerId)
                        val count = input.read(buffer)
                        if (count == -1) break
                        if (count == 0) NativeFileRules.fail(ProblemCode.IO_FAILURE)
                        if (count > NativeFileRules.budget(kind) - bytes) NativeFileRules.fail(ProblemCode.LIMIT_EXCEEDED)
                        output.write(buffer, 0, count); bytes += count
                    }
                    output.flush()
                }
            }
            live(ownerId)
            delivered = true
            target
        } finally { if (!delivered) discard(target) }
    }

    override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> = perform {
        NativeFileRules.leaf(suggestedName); NativeFileRules.mime(mediaType)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mediaType)
            .putExtra(Intent.EXTRA_TITLE, suggestedName).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Destination(ownerId, present(intent, ownerId))
    }

    private enum class DestinationState { NEW, WRITING, TERMINAL, CLOSED }
    private inner class Destination(override val ownerId: String, private val uri: Uri) : ExportDestination {
        private val state = AtomicReference(DestinationState.NEW)
        private val writing = AtomicReference<Job?>(null)
        override suspend fun write(file: LocalFile, guard: CommitGuard): OperationResult<Unit> {
            if (!state.compareAndSet(DestinationState.NEW, DestinationState.WRITING)) return OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
            try {
                return perform {
                    writing.set(currentCoroutineContext().job)
                    suspend fun authorized() {
                        live(ownerId)
                        if (state.get() != DestinationState.WRITING) NativeFileRules.cancelled()
                        NativeFileRules.value(guard())
                        // The guard itself may close/revoke; this final check admits only the next IO step.
                        live(ownerId)
                        if (state.get() != DestinationState.WRITING) NativeFileRules.cancelled()
                    }
                    authorized()
                    // Resolve/open the private source before any external open/truncate.
                    NativeFileRules.value(source(file)).buffer().use { input ->
                        val size = fileSystem.metadata(file.path.toPath()).size
                        if (size != null && size > NativeFileRules.OUTPUT_LIMIT) NativeFileRules.fail(ProblemCode.LIMIT_EXCEEDED)
                        authorized()
                        (resolver.openOutputStream(uri, "wt") ?: throw FileNotFoundException("Destination unavailable")).use { output ->
                            val buffer = ByteArray(8192)
                            var bytes = 0L
                            while (true) {
                                authorized()
                                val count = input.read(buffer)
                                if (count == -1) break
                                if (count == 0) NativeFileRules.fail(ProblemCode.IO_FAILURE)
                                if (count > NativeFileRules.OUTPUT_LIMIT - bytes) NativeFileRules.fail(ProblemCode.LIMIT_EXCEEDED)
                                authorized()
                                output.write(buffer, 0, count); bytes += count
                            }
                            authorized(); output.flush()
                        }
                    }
                    authorized() // A late owner/caller revocation never produces a foreground success.
                    Unit
                }
            } finally { writing.set(null); state.compareAndSet(DestinationState.WRITING, DestinationState.TERMINAL) }
        }
        override fun close() { state.set(DestinationState.CLOSED); writing.get()?.cancel() }
    }

    private fun dispose(value: Any?) {
        when (value) {
            is TemporaryFile -> discard(value)
            is ExportDestination -> value.close()
        }
    }
    private suspend fun <T> perform(action: suspend () -> T): OperationResult<T> = suspendCancellableCoroutine { reply ->
        var outcome: OperationResult<T> = OperationResult.Cancelled
        val job = runtime.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            outcome = try {
                currentCoroutineContext().ensureActive()
                OperationResult.Completed(action())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: NativeFileRules.Failure) { failure.result }
            catch (_: FileNotFoundException) { OperationResult.Failed(Problem(ProblemCode.NOT_FOUND)) }
            catch (_: SecurityException) { OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED)) }
            catch (_: Exception) { OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) }
        }
        job.invokeOnCompletion {
            val result = if (job.isCancelled && outcome is OperationResult.Completed) {
                (outcome as? OperationResult.Completed)?.let { dispose(it.value) }
                OperationResult.Cancelled
            } else outcome
            reply.resume(result) { _, undelivered, _ ->
                if (undelivered is OperationResult.Completed) dispose(undelivered.value)
            }
        }
        reply.invokeOnCancellation { job.cancel() }
        job.start()
    }
}
