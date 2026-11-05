package su.linka.pictures.portability.linka

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.portability.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.zip.*
import su.linka.pictures.text.isNativeText

class LinkaCodec(
    private val media: MediaStore,
    private val files: PlatformFilesPort,
    private val inspection: MediaInspectionPort,
    private val ids: IdSource,
) {
    suspend fun read(file: LocalFile, name: String): OperationResult<PreparedBoard> = readValidated(file, name, legacyUpgrade = false)

    // Android1.4 shipped flat standard1.3 assets; only its private upgrade path admits that schema.
    internal suspend fun readLegacyUpgrade(file: LocalFile, name: String): OperationResult<PreparedBoard> = readValidated(file, name, legacyUpgrade = true)

    private suspend fun readValidated(file: LocalFile, name: String, legacyUpgrade: Boolean): OperationResult<PreparedBoard> = codecBoundary {
        val temporaries = mutableListOf<TemporaryFile>()
        val prepared = mutableListOf<PreparedMedia>()
        var finalLease: MediaLease? = null
        try {
            limit(name.length <= 65536)
            checkData(isNativeText(name))
            val zip = CheckedZip.open(files, file, ZipProfile.LINKA)
            val staged = mutableMapOf<String, Triple<TemporaryFile, String, Long>>()
            var config: String? = null
            zip.visit { entry, source ->
                if (entry.name == "config.json") {
                    config = source.buffer().readByteArray().decodeToString(throwOnInvalidSequence = true)
                } else if (!entry.directory) {
                    val temp = files.createTemporaryFile("linka-entry", "application/octet-stream").value()
                    temporaries.add(temp)
                    val hash = HashingSink.sha256(files.sink(temp).value())
                    hash.buffer().use { sink -> copyStream(source, sink) }
                    staged[entry.name] = Triple(temp, hash.hash.hex(), entry.size)
                }
            }
            val wire = LinkaWire.parse(boundedJson(config ?: fail(ProblemCode.INVALID_DATA)), legacyUpgrade)
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
                val mediaType = inspectMedia(temp, kind, inspection)
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
            currentCoroutineContext().ensureActive()
            cleanup(prepared, temporaries, throwOnFailure = true)
            val result = PreparedBoard(document,
                BoardTransferReport(document.pages.size, document.pages.sumOf { it.cells.count { cell -> cell.kind == CellKind.CONTENT } }, images, recordings, mediaBytes, normalized),
                finalLease)
            currentCoroutineContext().ensureActive()
            finalLease = null
            result
        } finally {
            try { finalLease?.close() } catch (_: Exception) { /* Preserve the primary outcome. */ }
            cleanup(prepared, temporaries, throwOnFailure = false)
        }
    }

    suspend fun write(board: PublishedBoard, lease: MediaLease): OperationResult<TemporaryFile> = codecBoundary {
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
                val actualType = inspectMedia(media.file(id, retained).value(), asset.kind, inspection)
                if (actualType != asset.mediaType) fail(ProblemCode.INTEGRITY_FAILURE)
                asset
            }
            limit(metadata.values.sumOf { it.byteCount } + config.size <= ARCHIVE_LIMIT)
            val temp = files.createTemporaryFile("board.linka", "application/zip").value()
            temporary = temp
            files.sink(temp).value().buffer().use { sink ->
                val writer = CheckedZipWriter(sink, ZipProfile.LINKA)
                writer.add("config.json", Buffer().write(config))
                for ((id, path) in paths) {
                    val asset = metadata.getValue(id)
                    writer.add(path, media.source(id, retained).value(), asset.byteCount, id.value)
                }
                writer.finish()
            }
            CheckedZip.open(files, temp, ZipProfile.LINKA).visit { _, _ -> }
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

}
