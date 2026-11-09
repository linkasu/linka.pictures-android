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
import kotlin.coroutines.CoroutineContext

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
                        checkCurrent(context)
                        val metadata = files.metadata(folder)
                        checkCurrent(context)
                        if (!metadata.isDirectory) fail(ProblemCode.INVALID_DATA)
                        val target = folder / name
                        source(file).get().use { input ->
                            checkCurrent(context)
                            val existing = files.metadataOrNull(target)
                            checkCurrent(context)
                            if (existing != null) fail(ProblemCode.CONFLICT)
                            admit(context, guard)
                            val output = files.sink(target, mustCreate = true)
                            output.use { sink ->
                                checkCurrent(context)
                                val buffer = Buffer()
                                var total = 0L
                                while (true) {
                                    admit(context, guard)
                                    val count = input.read(buffer, 64 * 1024L)
                                    checkCurrent(context)
                                    if (count == -1L) break
                                    total += count
                                    if (total > 2L * 1024 * 1024 * 1024) fail(ProblemCode.LIMIT_EXCEEDED)
                                    admit(context, guard)
                                    sink.write(buffer, count)
                                    checkCurrent(context)
                                }
                                admit(context, guard)
                                sink.flush()
                                checkCurrent(context)
                            }
                            checkCurrent(context)
                        }
                        checkCurrent(context)
                        admit(context, guard)
                    }
                }
            }
            val caller = currentCoroutineContext()
            return locked {
                caller.ensureActive()
                if (!current() || state != State.WRITING) OperationResult.Cancelled else {
                    caller.ensureActive()
                    state = State.TERMINAL
                    result
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: FileFailure) {
            return failure.result
        } catch (_: Exception) {
            return problem(ProblemCode.IO_FAILURE)
        } finally {
            locked {
                when (state) {
                    State.WRITING -> state = State.TERMINAL
                    State.CLOSING -> state = State.CLOSED
                    else -> Unit
                }
            }
            releaseOnce()
        }
    }

    private fun checkCurrent(context: CoroutineContext) {
        context.ensureActive()
        if (locked { state != State.WRITING } || !current()) failCancelled()
    }

    private fun admit(context: CoroutineContext, guard: CommitGuard) = locked {
        checkCurrent(context)
        guard().get()
        checkCurrent(context)
        // Only this unit is admitted. A subsequent close cannot wait for or revoke its syscall.
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
