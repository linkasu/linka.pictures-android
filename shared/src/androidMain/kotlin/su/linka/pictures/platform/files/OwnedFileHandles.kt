package su.linka.pictures.platform.files

import java.io.Closeable
import java.io.FileNotFoundException
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import okio.*
import okio.Path.Companion.toPath
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.ports.LocalFile
import su.linka.pictures.application.ports.TemporaryFile

internal class OwnedFileHandles(
    private val temporaryRoot: Path,
    private val mediaRoot: Path,
    private val fs: FileSystem,
    private val rootActive: () -> Boolean,
    private val wake: () -> Unit,
) {
    private class Entry(val path: Path) { var valid = true; var sinkUsed = false; var writing = false }
    private val lock = Any()
    private val temps = IdentityHashMap<TemporaryFile, Entry>()
    private val handles = IdentityHashMap<Closeable, Entry?>()
    private val pending = ArrayDeque<Entry>()
    private var closed = false
    init {
        require(temporaryRoot.isAbsolute && mediaRoot.isAbsolute)
        require(temporaryRoot == temporaryRoot.normalized() && mediaRoot == mediaRoot.normalized())
        require(!inside(temporaryRoot, mediaRoot) && !inside(mediaRoot, temporaryRoot))
    }
    private fun inside(path: Path, root: Path) = path == root || path.segments.size > root.segments.size && path.segments.take(root.segments.size) == root.segments
    private fun unavailable(): Nothing = throw FileNotFoundException("Handle unavailable")
    private fun alive(entry: Entry?) = synchronized(lock) { !closed && rootActive() && (entry == null || entry.valid) }

    fun create(name: String?, type: String?): TemporaryFile {
        NativeFileRules.worker()
        synchronized(lock) { if (closed || !rootActive()) unavailable() }
        fs.createDirectories(temporaryRoot)
        val root = fs.canonicalize(temporaryRoot)
        if (fs.exists(mediaRoot)) {
            val media = fs.canonicalize(mediaRoot)
            if (inside(root, media) || inside(media, root)) NativeFileRules.fail(ProblemCode.INVALID_DATA)
        }
        val path = root / (UUID.randomUUID().toString() + ".tmp")
        // Only a successfully must-created file may ever enter cleanup ownership.
        val output = fs.sink(path, mustCreate = true)
        try { output.close() } catch (failure: Exception) { try { fs.delete(path) } catch (_: Exception) { }; throw failure }
        val file = TemporaryFile(path.toString(), name, type)
        val accepted = synchronized(lock) {
            if (closed || !rootActive()) false else { temps[file] = Entry(path); true }
        }
        if (!accepted) { try { fs.delete(path) } catch (_: Exception) { }; unavailable() }
        return file
    }

    fun source(file: LocalFile): Source {
        val entry = if (file is TemporaryFile) synchronized(lock) { temps[file] ?: unavailable() } else null
        NativeFileRules.worker()
        if (!alive(entry)) unavailable()
        val path = if (entry != null) {
            synchronized(lock) { if (entry.writing) NativeFileRules.fail(ProblemCode.CONFLICT) }
            if (fs.canonicalize(entry.path) != entry.path || fs.metadata(entry.path).symlinkTarget != null) unavailable()
            entry.path
        } else {
            val candidate = file.path.toPath()
            if (candidate != candidate.normalized() || candidate.parent?.parent != mediaRoot) unavailable()
            val hash = candidate.name
            if (hash.length != 64 || hash.any { it !in '0'..'9' && it !in 'a'..'f' } || candidate.parent?.name != hash.take(2)) unavailable()
            val expected = fs.canonicalize(mediaRoot) / hash.take(2) / hash
            if (fs.canonicalize(candidate) != expected || fs.metadata(candidate).symlinkTarget != null) unavailable()
            expected
        }
        if (!fs.metadata(path).isRegularFile) unavailable()
        val delegate = fs.source(path)
        val result = object : ForwardingSource(delegate) {
            private val done = AtomicBoolean(false)
            override fun read(sink: Buffer, byteCount: Long): Long {
                NativeFileRules.worker(); if (done.get() || !alive(entry)) unavailable()
                return super.read(sink, byteCount)
            }
            override fun close() {
                NativeFileRules.worker()
                if (done.compareAndSet(false, true)) { try { super.close() } finally { synchronized(lock) { handles.remove(this) } } }
            }
        }
        if (!register(result, entry)) { try { delegate.close() } catch (_: Exception) { }; unavailable() }
        return result
    }

    fun sink(file: TemporaryFile): Sink {
        val entry = synchronized(lock) { temps[file] ?: unavailable() }
        NativeFileRules.worker()
        synchronized(lock) {
            if (closed || !rootActive() || !entry.valid) unavailable()
            if (entry.sinkUsed) NativeFileRules.fail(ProblemCode.CONFLICT)
            entry.sinkUsed = true; entry.writing = true
        }
        var handle: FileHandle? = null
        try {
            if (fs.canonicalize(entry.path) != entry.path || fs.metadata(entry.path).symlinkTarget != null) unavailable()
            // openReadWrite does not truncate; verify the actual opened file before taking a sink.
            val opened = fs.openReadWrite(entry.path, mustExist = true).also { handle = it }
            if (opened.size() != 0L) NativeFileRules.fail(ProblemCode.CONFLICT)
            val delegate = opened.sink()
            val result = object : ForwardingSink(delegate) {
                private val done = AtomicBoolean(false)
                override fun write(source: Buffer, byteCount: Long) {
                    NativeFileRules.worker(); if (done.get() || !alive(entry)) unavailable()
                    super.write(source, byteCount)
                }
                override fun flush() { NativeFileRules.worker(); if (done.get() || !alive(entry)) unavailable(); super.flush() }
                override fun close() {
                    NativeFileRules.worker()
                    if (done.compareAndSet(false, true)) {
                        try { super.close() } finally {
                            try { opened.close() } finally { synchronized(lock) { entry.writing = false; handles.remove(this) } }
                        }
                    }
                }
            }
            if (!register(result, entry)) { try { result.close() } catch (_: Exception) { }; unavailable() }
            handle = null
            return result
        } finally {
            try { handle?.close() } catch (_: Exception) { /* Preserve the original open/validation failure. */ }
            if (handle != null) synchronized(lock) { entry.writing = false }
        }
    }

    private fun register(handle: Closeable, entry: Entry?): Boolean = synchronized(lock) {
        if (closed || !rootActive() || entry?.valid == false) false else { handles[handle] = entry; true }
    }
    fun discard(file: TemporaryFile) {
        synchronized(lock) {
            val entry = temps.remove(file) ?: return
            entry.valid = false; pending.addLast(entry)
        }
        wake() // Logical invalidation only on the caller thread, including Main.
    }
    fun shutdown() {
        synchronized(lock) {
            closed = true
            temps.values.forEach { it.valid = false; pending.addLast(it) }; temps.clear()
        }
    }
    fun drain() {
        while (true) {
            val entry = synchronized(lock) { if (pending.isEmpty()) null else pending.removeFirst() } ?: break
            val streams = synchronized(lock) { handles.entries.filter { it.value === entry }.map { it.key } }
            streams.forEach { try { it.close() } catch (_: Exception) { } }
            try { fs.delete(entry.path, mustExist = false) } catch (_: Exception) { /* Owned orphan recovery is best-effort. */ }
        }
        if (synchronized(lock) { closed }) {
            val streams = synchronized(lock) { handles.keys.toList() }
            streams.forEach { try { it.close() } catch (_: Exception) { } }
        }
    }
}
