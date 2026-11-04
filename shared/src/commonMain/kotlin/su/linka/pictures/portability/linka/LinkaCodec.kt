package su.linka.pictures.portability.linka

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.portability.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

class LinkaCodec(
    private val media: MediaStore,
    private val files: PlatformFilesPort,
    private val inspection: MediaInspectionPort,
    private val ids: IdSource,
) {
    suspend fun read(file: LocalFile, name: String): OperationResult<PreparedBoard> = boundary {
        val temporaries = mutableListOf<TemporaryFile>()
        val prepared = mutableListOf<PreparedMedia>()
        var finalLease: MediaLease? = null
        try {
            limit(name.length <= 65536)
            val zip = LinkaZip.open(files, file)
            val staged = mutableMapOf<String, Triple<TemporaryFile, String, Long>>()
            var config: String? = null
            zip.visit { entry, source ->
                if (entry.name == "config.json") {
                    config = source.buffer().readByteArray().decodeToString(throwOnInvalidSequence = true)
                } else if (!entry.directory) {
                    val temp = files.createTemporaryFile("linka-entry", "application/octet-stream").value()
                    temporaries.add(temp)
                    val hash = HashingSink.sha256(files.sink(temp).value())
                    hash.buffer().use { sink -> copy(source, sink) }
                    staged[entry.name] = Triple(temp, hash.hash.hex(), entry.size)
                }
            }
            val wire = LinkaWire.parse(boundedJson(config ?: fail(ProblemCode.INVALID_DATA)))
            // Raw schema and every late-page/card feature are checked before the first prepare.
            val references = wire.references
            for (path in references.keys) if (path !in staged) fail(ProblemCode.MISSING_ASSET)
            val imported = mutableMapOf<String, AssetId>()
            var mediaBytes = 0L
            var images = 0
            var recordings = 0
            for ((path, kind) in references) {
                currentCoroutineContext().ensureActive()
                val (temp, hash, byteCount) = staged.getValue(path)
                val mediaType = inspect(temp, kind)
                val source = files.source(temp).value()
                val asset = media.prepare(source, kind, mediaType).value()
                prepared.add(asset)
                if (asset.asset.id.value != hash || asset.asset.kind != kind || asset.asset.mediaType != mediaType || asset.asset.byteCount != byteCount) fail(ProblemCode.INTEGRITY_FAILURE)
                limit(asset.asset.byteCount <= MEDIA_LIMIT)
                imported[path] = asset.asset.id
                mediaBytes += asset.asset.byteCount
                if (kind == MediaKind.IMAGE) images++ else recordings++
            }
            val (document, normalized) = wire.document(name, imported, ids)
            currentCoroutineContext().ensureActive()
            finalLease = media.acquire(document.assetIds()).value()
            cleanup(prepared, temporaries, throwOnFailure = true)
            val result = PreparedBoard(document,
                BoardTransferReport(document.pages.size, document.pages.sumOf { it.cells.count { cell -> cell.kind == CellKind.CONTENT } }, images, recordings, mediaBytes, normalized),
                finalLease)
            finalLease = null
            result
        } finally {
            try { finalLease?.close() } catch (_: Exception) { /* Preserve the primary outcome. */ }
            cleanup(prepared, temporaries, throwOnFailure = false)
        }
    }

    suspend fun write(board: PublishedBoard, lease: MediaLease): OperationResult<TemporaryFile> = boundary {
        val document = board.document
        val assetIds = document.assetIds()
        limit(assetIds.size + 1 <= 2000)
        val paths = assetIds.associateWith { "media/${it.value}" }
        val config = encodeWire(document, paths).toString().encodeToByteArray()
        val retained = lease.retain(assetIds).value()
        var temporary: TemporaryFile? = null
        try {
            val kinds = mutableMapOf<AssetId, MutableSet<MediaKind>>()
            for (page in document.pages) for (cell in page.cells) cell.card?.let { card ->
                card.image?.let { kinds.getOrPut(it) { mutableSetOf() }.add(MediaKind.IMAGE) }
                (card.speech as? SpeechSource.Recording)?.let { kinds.getOrPut(it.assetId) { mutableSetOf() }.add(MediaKind.AUDIO) }
            }
            val metadata = assetIds.associateWith { id ->
                val asset = media.metadata(id).value()
                checkData(asset.id == id && asset.kind != MediaKind.ARCHIVE)
                limit(asset.byteCount <= MEDIA_LIMIT)
                checkData(kinds[id] == setOf(asset.kind))
                val actualType = inspect(media.file(id, retained).value(), asset.kind)
                if (actualType != asset.mediaType) fail(ProblemCode.INTEGRITY_FAILURE)
                asset
            }
            limit(metadata.values.sumOf { it.byteCount } + config.size <= ARCHIVE_LIMIT)
            val temp = files.createTemporaryFile("board.linka", "application/zip").value()
            temporary = temp
            files.sink(temp).value().buffer().use { sink ->
                val writer = LinkaZipWriter(sink)
                writer.add("config.json", Buffer().write(config))
                for ((id, path) in paths) {
                    val asset = metadata.getValue(id)
                    writer.add(path, media.source(id, retained).value(), asset.byteCount, id.value)
                }
                writer.finish()
            }
            LinkaZip.open(files, temp).visit { _, _ -> }
            currentCoroutineContext().ensureActive()
            retained.close()
            temporary = null
            temp
        } finally {
            try { temporary?.let(files::discard) } catch (_: Exception) { /* Preserve the primary outcome. */ }
            try { retained.close() } catch (_: Exception) { /* Close is idempotent; ownership was not transferred on failure. */ }
        }
    }

    private fun cleanup(prepared: MutableList<PreparedMedia>, temporaries: MutableList<TemporaryFile>, throwOnFailure: Boolean) {
        var failure: Exception? = null
        while (prepared.isNotEmpty()) {
            val owned = prepared.removeAt(prepared.lastIndex)
            try { owned.lease.close() } catch (error: Exception) { if (failure == null) failure = error }
        }
        while (temporaries.isNotEmpty()) {
            val owned = temporaries.removeAt(temporaries.lastIndex)
            try { files.discard(owned) } catch (error: Exception) { if (failure == null) failure = error }
        }
        if (throwOnFailure) failure?.let { throw it }
    }

    private suspend fun inspect(file: LocalFile, kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> inspection.image(file).value().let {
            limit(it.width <= 8192 && it.height <= 8192 && it.width.toLong() * it.height <= 16L * 1000 * 1000)
            it.mediaType
        }
        MediaKind.AUDIO -> inspection.audio(file).value().let {
            limit(it.durationMillis <= 10L * 60 * 1000)
            it.mediaType
        }
        MediaKind.ARCHIVE -> fail(ProblemCode.INVALID_DATA)
    }
}

private suspend fun copy(source: Source, sink: BufferedSink) {
    val buffer = Buffer()
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = source.read(buffer, 8192)
        if (count == -1L) return
        sink.write(buffer, count)
    }
}

private suspend fun <T> boundary(block: suspend () -> T): OperationResult<T> = try {
    OperationResult.Completed(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: CodecCancelled) {
    OperationResult.Cancelled
} catch (_: CodecUnconfirmed) {
    OperationResult.UnconfirmedCommit
} catch (failure: CodecFailure) {
    OperationResult.Failed(failure.problem)
} catch (_: EOFException) {
    OperationResult.Failed(Problem(ProblemCode.UNSAFE_ARCHIVE))
} catch (_: IOException) {
    OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
} catch (_: SerializationException) {
    OperationResult.Failed(Problem(ProblemCode.INVALID_DATA))
} catch (_: IllegalArgumentException) {
    OperationResult.Failed(Problem(ProblemCode.INVALID_DATA))
} catch (_: Exception) {
    OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
}
