@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package su.linka.pictures.integration

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.backup.*
import su.linka.pictures.portability.zip.*
import su.linka.pictures.storage.StorageRuntime
import kotlin.test.*

class CoreBackupIntegrationTest {
    private val fs = FileSystem.SYSTEM
    private fun <T> OperationResult<T>.ok(): T = assertIs<OperationResult.Completed<T>>(this).value
    private fun OperationResult<*>.problem(code: ProblemCode) = assertEquals(code, assertIs<OperationResult.Failed>(this).problem.code)
    // Test-only successful guard, not assistant authorization/P08 proof.
    private fun permit(epoch: Long = 0) = WritePermit(epoch) { OperationResult.Completed(Unit) }

    private data class LibraryData(
        val boards: List<PublishedBoard>, val drafts: List<StoredDraft>, val retained: List<RetainedArchive>,
        val assets: List<MediaAsset>, val settings: PortableSettings, val counts: SnapshotCounts,
    )

    private suspend fun data(reader: SnapshotReader) = LibraryData(
        reader.boards().toList(), reader.drafts().toList(), reader.retained().toList(),
        reader.assets().toList().sortedBy { it.id.value }, reader.settings, reader.counts,
    )

    private suspend fun data(runtime: StorageRuntime): LibraryData {
        val reader = runtime.library.snapshot().ok()
        return try { data(reader) } finally { reader.close() }
    }

    private suspend fun assertAssets(runtime: StorageRuntime, assets: List<MediaAsset>, bytes: Map<AssetId, ByteString>) {
        val lease = runtime.media.acquire(assets.map { it.id }.toSet()).ok()
        try {
            for (asset in assets) {
                assertEquals(asset, runtime.media.metadata(asset.id).ok())
                val actual = runtime.media.source(asset.id, lease).ok().buffer().use { it.readByteString() }
                assertEquals(bytes.getValue(asset.id), actual)
                assertEquals(asset.id.value, actual.sha256().hex())
                assertEquals(asset.byteCount, actual.size.toLong())
            }
        } finally { lease.close() }
    }

    @Test fun actualSqliteSnapshotBackupRestoreReopenAndRecoveryPreserveExactRoots() = runTest {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "linka-core-backup-${Uuid.random()}"
        fs.createDirectory(root, mustCreate = true)
        val closeables = mutableListOf<AutoCloseable>()
        val runtimes = mutableListOf<StorageRuntime>()
        var files: CoreBackupFiles? = null
        var primary: Throwable? = null
        try {
            val sourceRoot = root / "source"
            val targetRoot = root / "target"
            val filesRoot = root / "files"
            for (directory in listOf(sourceRoot, targetRoot, filesRoot)) fs.createDirectory(directory, mustCreate = true)
            val credential = root / "synthetic-credential-sentinel"
            val credentialBytes = Buffer().writeUtf8("TEST_CREDENTIAL_SENTINEL_NOT_REAL").readByteString()
            fs.sink(credential, mustCreate = true).buffer().use { it.write(credentialBytes) }
            val filePort = CoreBackupFiles(fs, filesRoot).also { files = it }
            val inspection = SyntheticCoreInspection(fs, root)
            suspend fun open(path: Path): StorageRuntime = StorageRuntime.open(path, fs, BundledSQLiteDriver()).ok().also(runtimes::add)
            val source = open(sourceRoot)
            var target = open(targetRoot)
            val bytes = mutableMapOf<AssetId, ByteString>()
            suspend fun prepare(runtime: StorageRuntime, text: String, kind: MediaKind): PreparedMedia {
                val payload = Buffer().writeUtf8(text).readByteString()
                val type = when (kind) { MediaKind.IMAGE -> "image/test"; MediaKind.AUDIO -> "audio/test"; MediaKind.ARCHIVE -> "application/octet-stream" }
                val prepared = runtime.media.prepare(Buffer().write(payload), kind, type).ok()
                closeables.add(prepared.lease)
                assertEquals(payload.sha256().hex(), prepared.asset.id.value)
                assertEquals(payload.size.toLong(), prepared.asset.byteCount)
                bytes[prepared.asset.id] = payload
                return prepared
            }

            val image = prepare(source, "SYNTHETIC_IMAGE_SOURCE_BOARD", MediaKind.IMAGE)
            val audio = prepare(source, "SYNTHETIC_AUDIO_SOURCE_RECORDING", MediaKind.AUDIO)
            val formImage = prepare(source, "SYNTHETIC_IMAGE_READY_FORM", MediaKind.IMAGE)
            val formAudio = prepare(source, "SYNTHETIC_AUDIO_READY_FORM", MediaKind.AUDIO)
            val rawArchive = prepare(source, "UNREADABLE_ARCHIVE_RAW_NOT_A_ZIP", MediaKind.ARCHIVE)
            val messageOnly = prepare(source, "SYNTHETIC_IMAGE_CURRENT_MESSAGE_ONLY_MARKER", MediaKind.IMAGE)
            val messageMarker = "CURRENT_MESSAGE_ONLY_MARKER"
            val document = BoardDocument(
                BoardId("source-board"), "Источник", listOf(BoardPage(PageId("source-page"), 1, 3, listOf(
                    BoardCell(0, CellKind.CONTENT, CardDocument(CardId("source-recording"), "Хочу cafe\u0301", image.asset.id,
                        SpeechSource.Recording(audio.asset.id, "recording editor text", "test voice"), """{"custom":{"namespace":"test/source","actions":[]}}""")),
                    BoardCell(1, CellKind.CONTENT, CardDocument(CardId("source-tts"), "Текст", speech = SpeechSource.Tts("Синтетический текст", "ru-RU"))),
                    BoardCell(2, CellKind.SPACE, extensionsJson = """{"custom":"space metadata"}"""),
                ))), BoardBehavior(InteractionMode.DIRECT, InputStyle.LITERAL), "Описание cafe\u0301", """{"custom":{"namespace":"test/library"}}""",
            )
            val first = source.library.addBoard(document, permit()).ok()
            val published = source.library.renameBoard(document.id, "Источник cafe\u0301", first.revision, permit()).ok()
            assertEquals(2, published.revision)
            val form = EditorForm.Card(PageId("source-page"), 2, CardId("open-form"), label = "Готовая форма",
                speechText = "Запись формы", speechChoice = SpeechChoice.RECORDING, image = formImage.asset.id, recording = formAudio.asset.id,
                audioText = "form editor text", audioVoice = "form voice", extensionsJson = """{"custom":true}""")
            val draft = EditorDraft(DraftId("source-draft"), document.id, published.revision, published.document, form)
            val firstDraft = source.library.saveDraft(draft, null, permit()).ok()
            val persistedDraft = source.library.saveDraft(draft.copy(form = form.copy(label = "Готовая форма cafe\u0301")), firstDraft.version, permit()).ok()
            assertEquals(2, persistedDraft.version)
            val retained = RetainedArchive(BoardId("source-retained"), "Архив cafe\u0301", 7, rawArchive.asset.id, RetainedReason.INVALID_ARCHIVE)
            source.library.retainArchive(retained, permit()).ok()
            val settings = PortableSettings(1.25f, true, "ru-RU", mapOf(document.id to BoardPresentation(false)))
            source.library.saveSettings(settings, source.library.listing.value.revision, permit()).ok()
            val sourceMessage = StoredMessage(0, 1, MessageDraft(listOf(MessagePart.Card(MessagePartId("message-only"),
                CardSelectionSnapshot(SelectionOrigin(document.id, published.revision, PageId("source-page"), CardId("source-recording")),
                    messageMarker, messageOnly.asset.id, SpeechSource.None))), mode = InteractionMode.DIRECT))
            source.messages.saveMessage(sourceMessage).ok()
            listOf(image, audio, formImage, formAudio, rawArchive, messageOnly).forEach { it.lease.close() }
            assertEquals(0, source.media.collectGarbage().ok().removedAssets)

            val sourceListing = source.library.listing.value
            val sourceSnapshot = source.library.snapshot().ok().also(closeables::add)
            val expected = data(sourceSnapshot)
            val expectedRoots = setOf(image.asset.id, audio.asset.id, formImage.asset.id, formAudio.asset.id, rawArchive.asset.id)
            assertEquals(listOf(published), expected.boards)
            assertEquals(listOf(persistedDraft), expected.drafts)
            assertEquals(listOf(retained), expected.retained)
            assertEquals(expectedRoots, sourceSnapshot.lease.assets)
            assertEquals(SnapshotCounts(1, 1, 1, 5, expected.assets.sumOf { it.byteCount }), expected.counts)
            assertFalse(messageOnly.asset.id in expected.assets.map { it.id })
            val output = BackupCodec(source.media, filePort, inspection, source.library).write(sourceSnapshot).ok()
            val backupBytes = filePort.source(output).ok().buffer().use { it.readByteString() }
            assertEquals(expected, data(sourceSnapshot)) // Real caller reader remains open/repeatable after write.
            val independent = sourceSnapshot.lease.retain(expectedRoots).ok().also(closeables::add)
            independent.close()
            assertEquals(sourceListing, source.library.listing.value)
            assertEquals(expected, data(source))
            assertEquals(sourceMessage, source.messages.message().ok())

            val zipEntries = mutableMapOf<String, ByteString>()
            CheckedZip.open(filePort, output, ZipProfile.BACKUP).visit { item, stream -> zipEntries[item.name] = stream.buffer().readByteString() }
            val expectedNames = setOf("manifest.json", "assets.jsonl", "boards/00000000.json", "drafts/00000000.json", "retained/00000000.json") + expectedRoots.map { "assets/${it.value}" }
            assertEquals(expectedNames, zipEntries.keys)
            assertFalse("assets/${messageOnly.asset.id.value}" in zipEntries)
            val manifest = decodeMetadata<BackupManifest>(zipEntries.getValue("manifest.json").toByteArray())
            assertEquals(sourceSnapshot.revision, manifest.revision)
            assertEquals(sourceSnapshot.counts, manifest.counts)
            assertEquals(settings, manifest.settings)
            val forbidden = listOf(messageMarker, credentialBytes.utf8(), messageOnly.asset.id.value, root.toString(), fs.canonicalize(root).toString())
            for (payload in zipEntries.values) for (value in forbidden) assertFalse(value in payload.utf8())
            assertEquals(1, filePort.temporaryCount); assertEquals(0, filePort.openHandles)
            sourceSnapshot.close()

            val oldImage = prepare(target, "SYNTHETIC_IMAGE_OLD_TARGET", MediaKind.IMAGE)
            val orphan = prepare(target, "SYNTHETIC_IMAGE_TARGET_ORPHAN", MediaKind.IMAGE)
            val oldDocument = BoardDocument(BoardId("old-target"), "Старая цель", listOf(BoardPage(PageId("old-page"), 1, 1,
                listOf(BoardCell(0, CellKind.CONTENT, CardDocument(CardId("old-card"), "old", oldImage.asset.id))))))
            target.library.addBoard(oldDocument, permit()).ok()
            val oldSettings = PortableSettings(0.9f, false, "en-US", mapOf(oldDocument.id to BoardPresentation(true)))
            target.library.saveSettings(oldSettings, target.library.listing.value.revision, permit()).ok()
            val oldMessage = StoredMessage(0, 1, MessageDraft(listOf(MessagePart.Text(MessagePartId("old-message"), "OLD_TARGET_MESSAGE")), mode = InteractionMode.DIRECT))
            target.messages.saveMessage(oldMessage).ok()
            oldImage.lease.close(); orphan.lease.close()
            val oldData = data(target)
            val before = target.library.listing.value
            assertNull(target.library.recovery().ok())
            val imported = BackupCodec(target.media, filePort, inspection, target.library).read(output).ok().also(closeables::add)
            assertEquals(sourceListing.revision, imported.revision)
            assertEquals(expected, data(imported))
            assertEquals(oldData, data(target))
            assertEquals(before, target.library.listing.value)
            assertEquals(oldMessage, target.messages.message().ok())
            assertContentEquals(backupBytes.toByteArray(), filePort.source(output).ok().buffer().use { it.readByteArray() })
            assertEquals(1, filePort.temporaryCount); assertEquals(0, filePort.openHandles)
            // Write and read each inspect the two image and two audio roots; ARCHIVE is never decoded.
            assertEquals(4, inspection.images); assertEquals(4, inspection.audio)
            var checks = 0
            val recovery = target.library.replaceLibrary(imported, before.revision, oldMessage.stamp,
                WritePermit(before.epoch) { checks++; OperationResult.Completed(Unit) }).ok()
            assertTrue(checks >= 2)
            imported.close() // GC below must rely on persistent refs, not the staging reader.
            val restored = target.library.listing.value
            assertEquals(before.epoch + 1, restored.epoch)
            assertTrue(restored.revision > before.revision)
            assertEquals(StoredMessage(restored.epoch, 0, MessageDraft()), target.messages.message().ok())
            assertEquals(expected, data(target))
            assertEquals(recovery, target.library.recovery().ok())
            assertEquals(before.revision, recovery.capturedLibraryRevision)
            assertEquals(1, recovery.boardCount); assertEquals(0, recovery.draftCount)
            assertEquals(oldImage.asset.byteCount, recovery.assetBytes)
            target.messages.saveMessage(oldMessage.copy(version = 999)).problem(ProblemCode.STALE_REQUEST)
            target.library.renameBoard(published.document.id, "stale", published.revision, permit(before.epoch)).problem(ProblemCode.STALE_REQUEST)
            assertEquals(restored, target.library.listing.value)
            assertEquals(StoredMessage(restored.epoch, 0, MessageDraft()), target.messages.message().ok())

            assertEquals(1, target.media.collectGarbage().ok().removedAssets)
            target.media.metadata(orphan.asset.id).problem(ProblemCode.MISSING_ASSET)
            target.media.metadata(messageOnly.asset.id).problem(ProblemCode.MISSING_ASSET)
            assertAssets(target, expected.assets + oldImage.asset, bytes)
            assertEquals(0, source.media.collectGarbage().ok().removedAssets)
            assertEquals(sourceMessage, source.messages.message().ok())
            assertEquals(expected, data(source)); assertEquals(sourceListing, source.library.listing.value)
            source.messages.saveMessage(StoredMessage(0, 2, MessageDraft())).ok()
            assertEquals(StoredMessage(0, 2, MessageDraft()), source.messages.message().ok())
            assertEquals(1, source.media.collectGarbage().ok().removedAssets)
            source.media.metadata(messageOnly.asset.id).problem(ProblemCode.MISSING_ASSET)
            assertAssets(source, expected.assets, bytes)
            assertEquals(expected, data(source)); assertEquals(sourceListing, source.library.listing.value)

            target.close()
            target = open(targetRoot) // Actual safe existing-schema probe, not a mock reopen.
            assertEquals(restored, target.library.listing.value)
            assertEquals(expected, data(target))
            assertEquals(recovery, target.library.recovery().ok())
            assertEquals(StoredMessage(restored.epoch, 0, MessageDraft()), target.messages.message().ok())
            assertAssets(target, expected.assets + oldImage.asset, bytes)
            val newRecovery = target.library.returnToRecovery(recovery.id, restored.revision, target.messages.message().ok().stamp, permit(restored.epoch)).ok()
            val returned = target.library.listing.value
            assertEquals(restored.epoch + 1, returned.epoch)
            assertTrue(returned.revision > restored.revision)
            assertEquals(oldData, data(target))
            assertEquals(StoredMessage(returned.epoch, 0, MessageDraft()), target.messages.message().ok())
            assertNotEquals(recovery.id, newRecovery.id)
            assertEquals(newRecovery, target.library.recovery().ok())
            assertEquals(restored.revision, newRecovery.capturedLibraryRevision)
            assertEquals(1, newRecovery.boardCount); assertEquals(1, newRecovery.draftCount)
            assertEquals(expected.counts.assetBytes, newRecovery.assetBytes)
            target.library.returnToRecovery(recovery.id, returned.revision, MessageStamp(returned.epoch, 0), permit(returned.epoch)).problem(ProblemCode.CONFLICT)
            target.messages.saveMessage(StoredMessage(restored.epoch, 999, MessageDraft())).problem(ProblemCode.STALE_REQUEST)
            target.library.renameBoard(oldDocument.id, "stale again", 1, permit(restored.epoch)).problem(ProblemCode.STALE_REQUEST)
            assertEquals(returned, target.library.listing.value)
            assertEquals(newRecovery, target.library.recovery().ok())
            assertEquals(0, target.media.collectGarbage().ok().removedAssets)
            assertAssets(target, expected.assets + oldImage.asset, bytes) // Current old board + the single recovery pin both libraries.
            assertEquals(credentialBytes, fs.read(credential) { readByteString() })
            assertEquals(backupBytes, filePort.source(output).ok().buffer().use { it.readByteString() })
            assertEquals(0, filePort.openHandles)
            assertEquals(1, filePort.temporaryCount)
            filePort.discard(output)
            assertEquals(0, filePort.temporaryCount)
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            var failure: Throwable? = null
            for (owned in closeables.asReversed()) try { owned.close() } catch (error: Throwable) { if (failure == null) failure = error }
            for (runtime in runtimes.asReversed()) try { runtime.close() } catch (error: Throwable) { if (failure == null) failure = error }
            try { files?.close() } catch (error: Throwable) { if (failure == null) failure = error }
            // Only our mustCreate UUID root, and only after every DB/reader/lease/FS handle closed.
            if (failure == null) try { fs.deleteRecursively(root) } catch (error: Throwable) { failure = error }
            if (failure != null) {
                if (primary != null) primary.addSuppressed(failure) else throw failure
            }
        }
    }
}
