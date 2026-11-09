@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package su.linka.pictures.platform.files

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import okio.*
import okio.Path.Companion.toPath
import platform.Foundation.*
import platform.UIKit.UIViewController
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.text.isNativeText

class IosPlatformFilesPort internal constructor(
    private val temporaryRoot: Path,
    private val mediaRoot: Path,
    private val fileSystem: FileSystem,
    private val presenter: (String) -> UIViewController?,
    private val isOwnerActive: (String) -> Boolean,
    private val scope: CoroutineScope,
) : PlatformFilesPort {
    private val lock = NSLock()
    private class Owned(val path: Path, var sinkOpened: Boolean = false)
    private val owned = mutableMapOf<TemporaryFile, Owned>()

    init {
        require(temporaryRoot.isAbsolute && mediaRoot.isAbsolute)
        require(isNativeText(temporaryRoot.toString()) && isNativeText(mediaRoot.toString()))
        require(temporaryRoot == temporaryRoot.toString().toPath(normalize = true) && mediaRoot == mediaRoot.toString().toPath(normalize = true))
        require(temporaryRoot != mediaRoot && !inside(temporaryRoot, mediaRoot) && !inside(mediaRoot, temporaryRoot))
    }

    override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> = boundary {
        worker()
        if (!validLeaf(suggestedName) || !validMime(mediaType)) fail(ProblemCode.INVALID_DATA)
        fileSystem.createDirectories(temporaryRoot)
        validateRoots()
        // Native AVAudioRecorder/AVAudioFile select the WAV container from the extension, not a hint.
        val path = temporaryRoot / (NSUUID().UUIDString + if (mediaType == "audio/wav") ".wav" else "")
        // A collision must fail without truncating or deleting the existing file.
        val handle = fileSystem.openReadWrite(path, mustCreate = true)
        try {
            handle.use { }
            val file = TemporaryFile(path.toString(), suggestedName, mediaType)
            synchronized { owned[file] = Owned(path) }
            file
        } catch (failure: Throwable) {
            // A returned must-create handle proves ownership even if its close subsequently fails.
            try { fileSystem.delete(path, mustExist = false) } catch (_: Throwable) { }
            throw failure
        }
    }

    override fun source(file: LocalFile): OperationResult<Source> = boundary {
        worker()
        if (!isNativeText(file.path)) fail(ProblemCode.INVALID_DATA)
        val registration = synchronized { (file as? TemporaryFile)?.let { owned[it] } }
        val path = if (registration != null) registration.path else {
            if (file is TemporaryFile) fail(ProblemCode.NOT_FOUND)
            validateRoots()
            val candidate = file.path.toPath(normalize = true)
            if (!inside(candidate, mediaRoot)) fail(ProblemCode.NOT_FOUND)
            if (!inside(fileSystem.canonicalize(candidate), fileSystem.canonicalize(mediaRoot))) fail(ProblemCode.NOT_FOUND)
            candidate
        }
        val metadata = fileSystem.metadataOrNull(path) ?: fail(ProblemCode.NOT_FOUND)
        if (!metadata.isRegularFile || metadata.symlinkTarget != null) fail(ProblemCode.NOT_FOUND)
        val input = fileSystem.source(path)
        object : ForwardingSource(input) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                worker()
                if (registration != null && !synchronized { owned[file] === registration }) throw FileNotFoundException("Revoked handle")
                return super.read(sink, byteCount)
            }
            override fun close() { worker(); super.close() }
        }
    }

    override fun sink(file: TemporaryFile): OperationResult<Sink> = boundary {
        worker()
        val registration = synchronized {
            val value = owned[file] ?: fail(ProblemCode.NOT_FOUND)
            if (value.sinkOpened) fail(ProblemCode.CONFLICT)
            value.sinkOpened = true
            value
        }
        val metadata = fileSystem.metadataOrNull(registration.path) ?: fail(ProblemCode.NOT_FOUND)
        if (!metadata.isRegularFile || metadata.symlinkTarget != null || metadata.size != 0L) fail(ProblemCode.CONFLICT)
        val handle = fileSystem.openReadWrite(registration.path, mustExist = true)
        val output = try { handle.sink() } finally { handle.close() }
        object : Sink by output {
            private fun check() {
                worker()
                if (!synchronized { owned[file] === registration }) throw FileNotFoundException("Revoked handle")
            }
            override fun write(source: Buffer, byteCount: Long) { check(); output.write(source, byteCount) }
            override fun flush() { check(); output.flush() }
            override fun close() { worker(); output.close() }
        }
    }

    override fun discard(file: TemporaryFile) {
        // Only registry invalidation is synchronous: audio may call this from any thread.
        val registration = synchronized { owned.remove(file) } ?: return
        scope.launch(Dispatchers.IO) {
            try { fileSystem.delete(registration.path, mustExist = false) } catch (_: Exception) { /* Private cache cleanup is best-effort. */ }
        }
    }

    override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> {
        var selection: IosFileSelection? = null
        var result: TemporaryFile? = null
        var published = false
        try {
            selection = selectIosFile(kind, false, ownerId, { presenter(ownerId) }, isOwnerActive) { selection = it }
            val selected = selection!!
            val copied = copyPickedUrl(selected.url, kind) { selected.current() }
            if (copied is OperationResult.Completed) result = copied.value
            currentCoroutineContext().ensureActive()
            if (!selected.current()) return OperationResult.Cancelled
            published = copied is OperationResult.Completed
            return copied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FileFailure) {
            return failure.result
        } catch (_: Exception) {
            return problem(ProblemCode.IO_FAILURE)
        } finally {
            if (!published) result?.let(::discard)
            selection?.close()
        }
    }

    override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> {
        if (!validLeaf(suggestedName) || !validMime(mediaType)) return problem(ProblemCode.INVALID_DATA)
        var selection: IosFileSelection? = null
        var transferred = false
        try {
            selection = selectIosFile(null, true, ownerId, { presenter(ownerId) }, isOwnerActive) { selection = it }
            val selected = selection!!
            currentCoroutineContext().ensureActive()
            if (!selected.current()) return OperationResult.Cancelled
            val destination = IosExportDestination(ownerId, selected.url, suggestedName, fileSystem,
                ::source, { selected.current() }, { selected.close() })
            selected.onBackground = destination::close
            transferred = true
            return OperationResult.Completed(destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FileFailure) {
            return failure.result
        } catch (_: Exception) {
            return problem(ProblemCode.IO_FAILURE)
        } finally {
            if (!transferred) selection?.close()
        }
    }

    // The same coordinated streaming path is exercised with owned local URLs, without claiming picker proof.
    internal suspend fun copyPickedUrl(url: NSURL, kind: PickedFileKind, current: () -> Boolean): OperationResult<TemporaryFile> {
        var temporary: TemporaryFile? = null
        var published = false
        try {
            val result = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                var value: OperationResult<TemporaryFile> = problem(ProblemCode.IO_FAILURE)
                coordinatedRead(url) { path ->
                    context.ensureActive()
                    if (!current()) failCancelled()
                    val budget = budget(kind)
                    val metadata = fileSystem.metadata(path)
                    if (!metadata.isRegularFile) fail(ProblemCode.UNSUPPORTED_MEDIA)
                    if ((metadata.size ?: 0) > budget) fail(ProblemCode.LIMIT_EXCEEDED)
                    val name = url.lastPathComponent ?: "import"
                    val target = createTemporaryFile(name, "application/octet-stream").get()
                    temporary = target
                    fileSystem.source(path).use { input ->
                        sink(target).get().use { output ->
                            val buffer = Buffer()
                            var total = 0L
                            while (true) {
                                context.ensureActive()
                                if (!current()) failCancelled()
                                val count = input.read(buffer, 64 * 1024L)
                                if (count == -1L) break
                                total += count
                                if (total > budget) fail(ProblemCode.LIMIT_EXCEEDED)
                                output.write(buffer, count)
                            }
                            output.flush()
                        }
                    }
                    context.ensureActive()
                    if (!current()) failCancelled()
                    value = OperationResult.Completed(target)
                }
                value
            }
            currentCoroutineContext().ensureActive()
            if (!current()) return OperationResult.Cancelled
            published = result is OperationResult.Completed
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FileFailure) {
            return failure.result
        } catch (_: Exception) {
            return problem(ProblemCode.IO_FAILURE)
        } finally {
            if (!published) temporary?.let(::discard)
        }
    }

    private inline fun <T> synchronized(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    private fun validateRoots() {
        if (!fileSystem.metadata(temporaryRoot).isDirectory || !fileSystem.metadata(mediaRoot).isDirectory) fail(ProblemCode.INVALID_DATA)
        val temporary = fileSystem.canonicalize(temporaryRoot)
        val media = fileSystem.canonicalize(mediaRoot)
        if (temporary == media || inside(temporary, media) || inside(media, temporary)) fail(ProblemCode.INVALID_DATA)
    }

    internal companion object {
        fun validLeaf(name: String): Boolean = name.isNotEmpty() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && isNativeText(name)
        fun validMime(type: String): Boolean = isNativeText(type) &&
            Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+/[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(type)
        fun budget(kind: PickedFileKind): Long = when (kind) {
            PickedFileKind.IMAGE, PickedFileKind.AUDIO -> 64L * 1024 * 1024
            PickedFileKind.LINKA -> 500L * 1024 * 1024
            PickedFileKind.BACKUP -> 2L * 1024 * 1024 * 1024
        }
        private fun inside(path: Path, root: Path) = path != root && path.toString().startsWith(root.toString().trimEnd('/') + "/")
    }
}

internal class FileFailure(val result: OperationResult<Nothing>) : Exception()
internal fun fail(code: ProblemCode): Nothing = throw FileFailure(problem(code))
internal fun failCancelled(): Nothing = throw FileFailure(OperationResult.Cancelled)
internal fun problem(code: ProblemCode) = OperationResult.Failed(Problem(code))
internal fun <T> OperationResult<T>.get(): T = when (this) {
    is OperationResult.Completed -> value
    is OperationResult.Failed -> throw FileFailure(this)
    OperationResult.Cancelled -> failCancelled()
    OperationResult.UnconfirmedCommit -> throw FileFailure(OperationResult.UnconfirmedCommit)
}
internal inline fun <T> boundary(block: () -> T): OperationResult<T> = try {
    OperationResult.Completed(block())
} catch (cancelled: CancellationException) { throw cancelled
} catch (failure: FileFailure) { failure.result
} catch (_: FileNotFoundException) { problem(ProblemCode.NOT_FOUND)
} catch (_: Exception) { problem(ProblemCode.IO_FAILURE) }

internal fun worker() { check(!NSThread.isMainThread) { "File IO requires a worker caller" } }

internal fun coordinatedRead(url: NSURL, block: (Path) -> Unit) = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    var invoked = false
    var thrown: Throwable? = null
    NSFileCoordinator(null).coordinateReadingItemAtURL(url, 0u, error.ptr) { coordinated ->
        invoked = true
        try {
            val path = coordinated?.path ?: fail(ProblemCode.INVALID_DATA)
            if (!isNativeText(path)) fail(ProblemCode.INVALID_DATA)
            block(path.toPath())
        } catch (failure: Throwable) { thrown = failure }
    }
    thrown?.let { throw it }
    if (!invoked || error.value != null) fail(ProblemCode.IO_FAILURE)
}
