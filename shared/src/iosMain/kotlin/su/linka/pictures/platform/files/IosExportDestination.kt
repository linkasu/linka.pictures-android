@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package su.linka.pictures.platform.files

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import okio.*
import okio.Path.Companion.toPath
import platform.Foundation.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.text.isNativeText

internal class IosExportDestination(
    override val ownerId: String,
    private val directory: NSURL,
    private val name: String,
    private val files: FileSystem,
    private val source: (LocalFile) -> OperationResult<Source>,
    private val current: () -> Boolean,
    private val release: () -> Unit,
) : ExportDestination {
    private enum class State { NEW, WRITING, CLOSING, TERMINAL, CLOSED }
    private val lock = NSRecursiveLock()
    private var state = State.NEW
    private var released = false

    override suspend fun write(file: LocalFile, guard: CommitGuard): OperationResult<Unit> {
        val claimed = locked {
            when (state) {
                State.NEW -> { state = State.WRITING; true }
                else -> false
            }
        }
        if (!claimed) return locked { if (state == State.CLOSED) OperationResult.Cancelled else problem(ProblemCode.CONFLICT) }
        try {
            val result = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                boundary {
                    if (!IosPlatformFilesPort.validLeaf(name)) fail(ProblemCode.INVALID_DATA)
                    coordinatedDirectoryWrite(directory) { folder ->
                        if (!files.metadata(folder).isDirectory) fail(ProblemCode.INVALID_DATA)
                        val target = folder / name
                        source(file).get().use { input ->
                            val output = locked {
                                context.ensureActive()
                                allowed(guard)
                                context.ensureActive()
                                if (files.metadataOrNull(target) != null) fail(ProblemCode.CONFLICT)
                                // This lock linearizes close against the irreversible exclusive open.
                                files.sink(target, mustCreate = true)
                            }
                            output.use { sink ->
                                val buffer = Buffer()
                                var total = 0L
                                while (true) {
                                    context.ensureActive()
                                    allowed(guard)
                                    val count = input.read(buffer, 64 * 1024L)
                                    if (count == -1L) break
                                    total += count
                                    if (total > 2L * 1024 * 1024 * 1024) fail(ProblemCode.LIMIT_EXCEEDED)
                                    // Check again after the read: it can itself suspend in a provider.
                                    context.ensureActive()
                                    locked { allowed(guard); context.ensureActive(); sink.write(buffer, count) }
                                }
                                context.ensureActive()
                                allowed(guard)
                                sink.flush()
                            }
                        }
                        context.ensureActive()
                        allowed(guard)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!current() || locked { state != State.WRITING }) return OperationResult.Cancelled
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FileFailure) {
            return failure.result
        } catch (_: Exception) {
            return problem(ProblemCode.IO_FAILURE)
        } finally {
            locked { state = if (state == State.WRITING) State.TERMINAL else State.CLOSED }
            releaseOnce()
        }
    }

    private fun allowed(guard: CommitGuard) {
        if (locked { state != State.WRITING } || !current()) failCancelled()
        guard().get()
        if (locked { state != State.WRITING } || !current()) failCancelled()
    }

    override fun close() {
        val releaseNow = locked {
            val writing = state == State.WRITING || state == State.CLOSING
            state = if (writing) State.CLOSING else State.CLOSED
            !writing
        }
        // An in-flight write keeps scope until all native streams have closed.
        if (releaseNow) releaseOnce()
    }

    private fun releaseOnce() {
        val shouldRelease = locked { if (released) false else { released = true; true } }
        if (shouldRelease) try { release() } catch (_: Throwable) { /* Never replace the primary outcome. */ }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}

private fun coordinatedDirectoryWrite(url: NSURL, block: (Path) -> Unit) = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    var invoked = false
    var thrown: Throwable? = null
    NSFileCoordinator(null).coordinateWritingItemAtURL(url, 0u, error.ptr) { directory ->
        invoked = true
        try {
            val path = directory?.path ?: fail(ProblemCode.INVALID_DATA)
            if (!isNativeText(path)) fail(ProblemCode.INVALID_DATA)
            block(path.toPath())
        } catch (failure: Throwable) { thrown = failure }
    }
    thrown?.let { throw it }
    if (!invoked || error.value != null) fail(ProblemCode.IO_FAILURE)
}
