package su.linka.pictures.portability.backup

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.encodeToString
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.zip.*

class BackupCodec(
    private val media: MediaStore,
    private val files: PlatformFilesPort,
    private val inspection: MediaInspectionPort,
    private val library: LibraryStore,
) {
    private val documentName = Regex("(?:boards|drafts|retained)/[0-9]{8}\\.json")
    private val assetName = Regex("assets/[0-9a-f]{64}")
    suspend fun write(snapshot: SnapshotReader): OperationResult<TemporaryFile> = codecBoundary {
        val resources = BackupResources(files)
        try {
            val manifest = BackupManifest(snapshot.revision, snapshot.settings, snapshot.counts, organization = snapshot.organization)
            checkCounts(manifest.counts)
            var settingsSize = 0L
            for ((id, presentation) in manifest.settings.boards) {
                settingsSize += backupJson.encodeToString(mapOf(id to presentation)).encodeToByteArray().size
                limit(settingsSize <= 2 * CONFIG_LIMIT)
            }
            val retained = snapshot.lease.retain(snapshot.lease.assets).value()
            resources.owned.add(retained)
            val output = resources.temporary("library.linkabackup")
            val rowsFile = resources.temporary("assets.jsonl")
            val refs = BackupReferences()
            files.sink(output).value().buffer().use { sink ->
                val zip = CheckedZipWriter(sink, ZipProfile.BACKUP)
                zip.add("manifest.json", Buffer().write(encodeMetadata(manifest)))
                var boards = 0
                snapshot.boards().collect { value ->
                    currentCoroutineContext().ensureActive()
                    checkData(boards < manifest.counts.boards); refs.board(value)
                    zip.add(entryName("boards", boards++), Buffer().write(encodeMetadata(value)))
                }
                checkData(boards == manifest.counts.boards)
                var drafts = 0
                snapshot.drafts().collect { value ->
                    currentCoroutineContext().ensureActive()
                    checkData(drafts < manifest.counts.drafts); refs.draft(value)
                    zip.add(entryName("drafts", drafts++), Buffer().write(encodeMetadata(value)))
                }
                checkData(drafts == manifest.counts.drafts)
                var archives = 0
                snapshot.retained().collect { value ->
                    currentCoroutineContext().ensureActive()
                    checkData(archives < manifest.counts.retained); refs.retained(value)
                    zip.add(entryName("retained", archives++), Buffer().write(encodeMetadata(value)))
                }
                checkData(archives == manifest.counts.retained)
                refs.organization(snapshot.organization)
                var count = 0
                var bytes = 0L
                var rowBytes = 0L
                val ids = mutableSetOf<AssetId>()
                val rowsHash = HashingSink.sha256(files.sink(rowsFile).value())
                rowsHash.buffer().use { rows ->
                    snapshot.assets().collect { asset ->
                        currentCoroutineContext().ensureActive()
                        checkData(++count <= manifest.counts.assets && ids.add(asset.id)); refs.asset(asset)
                        limit(asset.byteCount <= BACKUP_LIMIT - bytes); bytes += asset.byteCount
                        val row = encodeMetadata(asset)
                        rowBytes += row.size + 1
                        limit(rowBytes <= JSONL_LIMIT)
                        rows.write(row).writeByte('\n'.code)
                    }
                }
                checkData(count == manifest.counts.assets && bytes == manifest.counts.assetBytes && ids == refs.kinds.keys)
                checkData(retained.assets.containsAll(ids))
                val rowDigest = rowsHash.hash
                zip.add("assets.jsonl", files.source(rowsFile).value(), rowBytes, rowDigest.hex())
                val repeatedHash = HashingSink.sha256(blackholeSink())
                val repeatedIds = mutableSetOf<AssetId>()
                repeatedHash.buffer().use { rows ->
                    snapshot.assets().collect { asset ->
                        currentCoroutineContext().ensureActive()
                        checkData(repeatedIds.add(asset.id) && asset.id in ids); refs.asset(asset)
                        rows.write(encodeMetadata(asset)).writeByte('\n'.code)
                        val actual = media.metadata(asset.id).value()
                        if (actual != asset) fail(ProblemCode.INTEGRITY_FAILURE)
                        inspect(asset, media.file(asset.id, retained).value())
                        zip.add("assets/${asset.id.value}", media.source(asset.id, retained).value(), asset.byteCount, asset.id.value)
                    }
                }
                checkData(repeatedIds == ids)
                if (repeatedHash.hash != rowDigest) fail(ProblemCode.INTEGRITY_FAILURE)
                zip.finish()
            }
            CheckedZip.open(files, output, ZipProfile.BACKUP).visit { _, _ -> }
            resources.cleanup(throwOnFailure = true, keep = output)
            currentCoroutineContext().ensureActive()
            resources.transfer(output)
            output
        } finally { resources.cleanup(throwOnFailure = false) }
    }

    private data class Staged(val file: TemporaryFile, val size: Long, val hash: String)

    suspend fun read(file: LocalFile): OperationResult<SnapshotReader> = codecBoundary {
        val resources = BackupResources(files)
        var sealed: SnapshotReader? = null
        try {
            val zip = CheckedZip.open(files, file, ZipProfile.BACKUP)
            checkData(zip.items.all { !it.directory && allowedName(it.name) })
            val staged = mutableMapOf<String, Staged>()
            zip.visit { item, source ->
                val temporary = resources.temporary("backup-entry")
                val hash = HashingSink.sha256(files.sink(temporary).value())
                hash.buffer().use { copyStream(source, it) }
                staged[item.name] = Staged(temporary, item.size, hash.hash.hex())
            }
            val header = staged["manifest.json"] ?: fail(ProblemCode.INVALID_DATA)
            val manifestBytes = metadataBytes(header)
            val rawHeader = boundedJson(manifestBytes.decodeToString(throwOnInvalidSequence = true))
            checkData(rawHeader.string("format") == "su.linka.pictures.backup")
            val manifest = when (rawHeader.string("version")) {
                "1.0" -> decodeMetadata<LegacyBackupManifest>(manifestBytes).current()
                "1.1" -> decodeMetadata<BackupManifest>(manifestBytes)
                else -> fail(ProblemCode.UNSUPPORTED_VERSION)
            }
            checkCounts(manifest.counts)
            val counts = manifest.counts
            checkData(staged.size.toLong() == counts.boards.toLong() + counts.drafts + counts.retained + counts.assets + 2)
            val rows = staged["assets.jsonl"] ?: fail(ProblemCode.INVALID_DATA)
            val refs = BackupReferences()
            // All metadata, namespaces, closure, sizes and hashes are validated before the first prepare/builder.
            for (index in 0 until counts.boards) refs.board(decodeMetadata<PublishedBoard>(metadataBytes(staged[entryName("boards", index)] ?: fail(ProblemCode.INVALID_DATA))))
            for (index in 0 until counts.drafts) refs.draft(decodeMetadata<StoredDraft>(metadataBytes(staged[entryName("drafts", index)] ?: fail(ProblemCode.INVALID_DATA))))
            for (index in 0 until counts.retained) refs.retained(decodeMetadata<RetainedArchive>(metadataBytes(staged[entryName("retained", index)] ?: fail(ProblemCode.INVALID_DATA))))
            refs.organization(manifest.organization)
            val ids = mutableSetOf<AssetId>()
            var assetBytes = 0L
            readAssetRows(files, rows.file, rows.hash) { asset ->
                checkData(ids.size < counts.assets && ids.add(asset.id)); refs.asset(asset)
                limit(asset.byteCount <= BACKUP_LIMIT - assetBytes); assetBytes += asset.byteCount
                val payload = staged["assets/${asset.id.value}"] ?: fail(ProblemCode.MISSING_ASSET)
                if (payload.size != asset.byteCount || payload.hash != asset.id.value) fail(ProblemCode.INTEGRITY_FAILURE)
            }
            checkData(ids.size == counts.assets && assetBytes == counts.assetBytes && ids == refs.kinds.keys)
            val builder = library.createSnapshotBuilder().value()
            resources.owned.add(builder)
            currentCoroutineContext().ensureActive()
            readAssetRows(files, rows.file, rows.hash) { asset ->
                currentCoroutineContext().ensureActive()
                val payload = staged.getValue("assets/${asset.id.value}")
                refs.asset(asset)
                inspect(asset, payload.file)
                val prepared = media.prepare(files.source(payload.file).value(), asset.kind, asset.mediaType).value()
                resources.owned.add(prepared.lease)
                currentCoroutineContext().ensureActive()
                if (prepared.asset != asset) fail(ProblemCode.INTEGRITY_FAILURE)
                builder.addAsset(asset).value()
            }
            for (index in 0 until counts.boards) {
                currentCoroutineContext().ensureActive()
                builder.addBoard(decodeMetadata<PublishedBoard>(metadataBytes(staged.getValue(entryName("boards", index))))).value()
            }
            for (index in 0 until counts.drafts) {
                currentCoroutineContext().ensureActive()
                builder.addDraft(decodeMetadata<StoredDraft>(metadataBytes(staged.getValue(entryName("drafts", index))))).value()
            }
            for (index in 0 until counts.retained) {
                currentCoroutineContext().ensureActive()
                builder.addRetained(decodeMetadata<RetainedArchive>(metadataBytes(staged.getValue(entryName("retained", index))))).value()
            }
            currentCoroutineContext().ensureActive()
            sealed = builder.seal(manifest.revision, manifest.settings, manifest.organization).value()
            currentCoroutineContext().ensureActive()
            checkData(sealed.counts == counts && sealed.revision == manifest.revision && sealed.settings == manifest.settings && sealed.organization == manifest.organization && sealed.lease.assets == ids)
            resources.cleanup(throwOnFailure = true)
            currentCoroutineContext().ensureActive()
            val result = sealed
            sealed = null
            result
        } finally {
            try { sealed?.close() } catch (_: Exception) { /* Preserve the primary outcome. */ }
            resources.cleanup(throwOnFailure = false)
        }
    }

    private suspend fun inspect(asset: MediaAsset, file: LocalFile) {
        limit(asset.byteCount <= assetBudget(asset.kind))
        if (asset.kind != MediaKind.ARCHIVE && inspectMedia(file, asset.kind, inspection) != asset.mediaType) fail(ProblemCode.UNSUPPORTED_MEDIA)
    }

    private fun allowedName(name: String): Boolean = name == "manifest.json" || name == "assets.jsonl" ||
        documentName.matches(name) || assetName.matches(name)

    private suspend fun metadataBytes(staged: Staged): ByteArray {
        limit(staged.size <= CONFIG_LIMIT)
        val context = currentCoroutineContext()
        val hash = HashingSource.sha256(files.source(staged.file).value())
        val buffer = Buffer()
        hash.use { source ->
            while (true) {
                context.ensureActive()
                val n = source.read(buffer, 8192)
                if (n == -1L) break
                limit(buffer.size <= CONFIG_LIMIT)
            }
        }
        if (buffer.size != staged.size || hash.hash.hex() != staged.hash) fail(ProblemCode.INTEGRITY_FAILURE)
        return buffer.readByteArray()
    }
}
