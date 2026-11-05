@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package su.linka.pictures.integration

import kotlin.uuid.Uuid
import okio.*
import okio.Path.Companion.toPath
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.*
import kotlin.test.*

// Real owned files, not a filesystem fake. No picker/external destination is allowed in this test.
internal class CoreBackupFiles(private val fs: FileSystem, root: Path) : PlatformFilesPort, AutoCloseable {
    private val root = fs.canonicalize(root)
    private val owned = mutableSetOf<Path>()
    private val sources = mutableSetOf<Source>()
    private val sinks = mutableSetOf<Sink>()
    val temporaryCount: Int get() = owned.size
    val openHandles: Int get() = sources.size + sinks.size

    private fun path(file: LocalFile): Path {
        val path = file.path.toPath(normalize = true)
        check(path.parent == root && path in owned)
        check(fs.canonicalize(path).parent == root && fs.metadata(path).symlinkTarget == null)
        return path
    }

    override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> {
        val path = root / Uuid.random().toString()
        fs.sink(path, mustCreate = true).close()
        owned.add(path)
        return OperationResult.Completed(TemporaryFile(path.toString(), suggestedName, mediaType))
    }

    override fun source(file: LocalFile): OperationResult<Source> {
        val source = object : ForwardingSource(fs.source(path(file))) {
            private var closed = false
            override fun close() {
                if (closed) return
                super.close()
                closed = true
                sources.remove(this)
            }
        }
        sources.add(source)
        return OperationResult.Completed(source)
    }

    override fun sink(file: TemporaryFile): OperationResult<Sink> {
        val delegate = fs.sink(path(file))
        val sink = object : Sink by delegate {
            private var closed = false
            override fun close() {
                if (closed) return
                delegate.close()
                closed = true
                sinks.remove(this)
            }
        }
        sinks.add(sink)
        return OperationResult.Completed(sink)
    }

    override fun discard(file: TemporaryFile) {
        val path = path(file)
        fs.delete(path, mustExist = true)
        check(owned.remove(path))
    }

    override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> = error("No picker in core integration")
    override suspend fun save(file: LocalFile, suggestedName: String, ownerId: String): OperationResult<Unit> = error("No external export in core integration")

    override fun close() {
        var failure: Throwable? = null
        for (source in sources.toList()) try { source.close() } catch (error: Throwable) { if (failure == null) failure = error }
        for (sink in sinks.toList()) try { sink.close() } catch (error: Throwable) { if (failure == null) failure = error }
        if (sources.isEmpty() && sinks.isEmpty()) for (path in owned.toList()) {
            try { fs.delete(path, mustExist = true); owned.remove(path) } catch (error: Throwable) { if (failure == null) failure = error }
        }
        failure?.let { throw it }
        check(openHandles == 0 && owned.isEmpty())
    }
}

// Deliberately NOT native media proof: only known synthetic bytes, no PNG/WAV/codec decoding.
internal class SyntheticCoreInspection(private val fs: FileSystem, root: Path) : MediaInspectionPort {
    private val root = fs.canonicalize(root)
    var images = 0
        private set
    var audio = 0
        private set

    private fun content(file: LocalFile, prefix: String) {
        val path = fs.canonicalize(file.path.toPath())
        check(generateSequence(path.parent) { it.parent }.any { it == root })
        assertTrue(fs.read(path) { readUtf8() }.startsWith(prefix))
    }

    override suspend fun image(file: LocalFile): OperationResult<ImageInfo> {
        content(file, "SYNTHETIC_IMAGE_"); images++
        return OperationResult.Completed(ImageInfo(2, 3, "image/test"))
    }

    override suspend fun audio(file: LocalFile): OperationResult<AudioInfo> {
        content(file, "SYNTHETIC_AUDIO_"); audio++
        return OperationResult.Completed(AudioInfo(40, "audio/test"))
    }
}
