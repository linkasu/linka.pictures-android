package su.linka.pictures.portability.linka

import okio.*
import okio.ByteString.Companion.toByteString
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.zip.*

// In-memory file/lease/inspection seams; inspection is deliberately NOT native decoder evidence.
internal class CodecTestRig : PlatformFilesPort, MediaStore, MediaInspectionPort {
    val bytes = mutableMapOf<String, ByteArray>()
    val assets = mutableMapOf<AssetId, MediaAsset>()
    val temporary = mutableSetOf<String>()
    var sequence = 0
    var idCalls = 0
    var prepares = 0
    var acquires = 0
    var inspections = 0
    var openLeases = 0
    var openSources = 0
    var openSinks = 0
    var failPrepareAt = Int.MAX_VALUE
    var prepareOutcome: OperationResult<PreparedMedia> = OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
    var failAcquire = false
    var acquireOverride: (suspend (Set<AssetId>) -> OperationResult<MediaLease>)? = null
    var imageInfo = ImageInfo(2, 2, "image/png")
    var audioInfo = AudioInfo(1000, "audio/wav")
    var inspectionOutcome: OperationResult<Nothing>? = null
    var onInspection: (() -> Unit)? = null
    var badHash = false
    var failSinkWrites = false
    var failDiscardAfterRemoval = false
    var discardAttempts = 0
    var failSourcePath: String? = null
    var sourceOverride: ((LocalFile) -> Source?)? = null
    val codec = LinkaCodec(this, this, this, IdSource { idCalls++; "fresh-${++sequence}" })

    fun input(data: ByteArray): LocalFile = LocalFile("input-${++sequence}").also { bytes[it.path] = data.copyOf() }
    fun raw(file: LocalFile): ByteArray = bytes.getValue(file.path)
    override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> {
        val file = TemporaryFile("temp-${++sequence}")
        temporary.add(file.path)
        bytes[file.path] = byteArrayOf()
        return OperationResult.Completed(file)
    }
    override fun source(file: LocalFile): OperationResult<Source> {
        val data = bytes[file.path] ?: return OperationResult.Failed(Problem(ProblemCode.NOT_FOUND))
        openSources++
        val source = sourceOverride?.invoke(file) ?: Buffer().write(data)
        return OperationResult.Completed(object : Source {
            var closed = false
            override fun timeout() = Timeout.NONE
            override fun read(sink: Buffer, byteCount: Long): Long {
                check(!closed)
                if (file.path == failSourcePath) throw IOException("synthetic read failure")
                return source.read(sink, byteCount)
            }
            override fun close() { if (!closed) { closed = true; openSources--; source.close() } }
        })
    }
    override fun sink(file: TemporaryFile): OperationResult<Sink> {
        check(file.path in temporary)
        openSinks++
        val data = Buffer()
        return OperationResult.Completed(object : Sink {
            var closed = false
            override fun timeout() = Timeout.NONE
            override fun write(source: Buffer, byteCount: Long) {
                check(!closed)
                if (failSinkWrites) throw IOException("synthetic write failure")
                data.write(source, byteCount)
            }
            override fun flush() = Unit
            override fun close() { if (!closed) { closed = true; bytes[file.path] = data.readByteArray(); openSinks-- } }
        })
    }
    override fun discard(file: TemporaryFile) {
        discardAttempts++
        check(temporary.remove(file.path)); bytes.remove(file.path)
        if (failDiscardAfterRemoval) throw IOException("synthetic cleanup failure")
    }
    override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> = error("unused")
    override suspend fun save(file: LocalFile, suggestedName: String, ownerId: String): OperationResult<Unit> = error("unused")

    override suspend fun prepare(source: Source, kind: MediaKind, mediaType: String): OperationResult<PreparedMedia> {
        prepares++
        val data = source.buffer().use { it.readByteArray() }
        if (prepares == failPrepareAt) return prepareOutcome
        val hash = if (badHash) "0".repeat(64) else data.toByteString().sha256().hex()
        val id = AssetId(hash)
        val asset = MediaAsset(id, kind, mediaType, data.size.toLong())
        assets[id] = asset
        bytes[id.value] = data
        return OperationResult.Completed(PreparedMedia(asset, Lease(setOf(id))))
    }
    inner class Lease(override val assets: Set<AssetId>) : MediaLease {
        var closed = false
        init { openLeases++ }
        override fun retain(assets: Set<AssetId>): OperationResult<MediaLease> {
            check(!closed)
            return if (this.assets.containsAll(assets)) OperationResult.Completed(Lease(assets)) else OperationResult.Failed(Problem(ProblemCode.MISSING_ASSET))
        }
        override fun close() { if (!closed) { closed = true; openLeases-- } }
    }
    override suspend fun acquire(assets: Set<AssetId>): OperationResult<MediaLease> {
        acquires++
        acquireOverride?.let { return it(assets) }
        return if (failAcquire) OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) else OperationResult.Completed(Lease(assets))
    }
    override suspend fun metadata(id: AssetId): OperationResult<MediaAsset> = assets[id]?.let { OperationResult.Completed(it) } ?: OperationResult.Failed(Problem(ProblemCode.MISSING_ASSET))
    override fun file(id: AssetId, lease: MediaLease): OperationResult<LocalFile> {
        check(id in lease.assets && !(lease as Lease).closed)
        return OperationResult.Completed(LocalFile(id.value))
    }
    override fun source(id: AssetId, lease: MediaLease): OperationResult<Source> = source(file(id, lease).value())
    override suspend fun collectGarbage(): OperationResult<GarbageCollectionReport> = error("codec must not delete immutable assets")
    override suspend fun image(file: LocalFile): OperationResult<ImageInfo> {
        inspections++
        check(bytes.getValue(file.path).isNotEmpty())
        onInspection?.invoke()
        return inspectionOutcome ?: OperationResult.Completed(imageInfo)
    }
    override suspend fun audio(file: LocalFile): OperationResult<AudioInfo> {
        inspections++
        check(bytes.getValue(file.path).isNotEmpty())
        onInspection?.invoke()
        return inspectionOutcome ?: OperationResult.Completed(audioInfo)
    }
}
