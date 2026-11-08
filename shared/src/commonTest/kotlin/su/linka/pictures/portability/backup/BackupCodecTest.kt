package su.linka.pictures.portability.backup

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.linka.*
import su.linka.pictures.portability.zip.*
import kotlin.coroutines.*
import kotlin.test.*

class BackupCodecTest {
    private fun codec(rig: CodecTestRig, store: BackupTestStore, media: MediaStore = rig) = BackupCodec(media, rig, rig, store)
    private fun clean(rig: CodecTestRig, store: BackupTestStore, leases: Int = 0) {
        assertEquals(0, rig.openSources); assertEquals(0, rig.openSinks); assertEquals(leases, rig.openLeases)
        assertEquals(0, store.builders); assertTrue(rig.temporary.isEmpty())
    }
    private fun failed(result: OperationResult<*>, code: ProblemCode? = null) {
        val failure = assertIs<OperationResult.Failed>(result)
        if (code != null) assertEquals(code, failure.problem.code)
    }
    private suspend fun entries(rig: CodecTestRig, file: LocalFile): List<SyntheticEntry> {
        val result = mutableListOf<SyntheticEntry>()
        CheckedZip.open(rig, file, ZipProfile.BACKUP).visit { item, source -> result.add(SyntheticEntry(item.name, source.buffer().readByteArray())) }
        return result
    }
    private suspend fun fixture(rig: CodecTestRig, store: BackupTestStore): List<SyntheticEntry> {
        val snapshot = fullSnapshot(rig)
        val output = codec(rig, store).write(snapshot).value()
        val result = entries(rig, output)
        rig.discard(output); snapshot.close()
        clean(rig, store)
        rig.prepares = 0; rig.inspections = 0; rig.acquires = 0
        return result
    }
    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(toMutableMap().also { it[key] = value })
    private fun change(entries: List<SyntheticEntry>, name: String, edit: (ByteArray) -> ByteArray): List<SyntheticEntry> = entries.map { if (it.name == name) SyntheticEntry(name, edit(it.data)) else it }
    private fun header(entries: List<SyntheticEntry>, edit: (JsonObject) -> JsonObject) = change(entries, "manifest.json") { edit(boundedJson(it.decodeToString())).toString().encodeToByteArray() }
    private fun count(entries: List<SyntheticEntry>, field: String, delta: Long): List<SyntheticEntry> = header(entries) { root ->
        val counts = root.getValue("counts").jsonObject
        root.with("counts", counts.with(field, JsonPrimitive(counts.getValue(field).jsonPrimitive.long + delta)))
    }

    @Test fun emptySnapshotRoundTripOwnsReturnedReaderButNeverConsumesCaller() = runTest {
        val rig = CodecTestRig(); val store = BackupTestStore(rig); val source = TestSnapshot(rig)
        val output = codec(rig, store).write(source).value()
        assertFalse(source.closed); assertEquals(1, rig.openLeases)
        assertEquals(setOf("manifest.json", "assets.jsonl"), entries(rig, output).map { it.name }.toSet())
        val restored = codec(rig, store).read(output).value()
        assertEquals(source.counts, restored.counts); assertEquals(source.settings, restored.settings); assertEquals(source.revision, restored.revision)
        assertTrue(restored.boards().toList().isEmpty()); assertTrue(restored.assets().toList().isEmpty())
        assertEquals(0, rig.prepares); assertEquals(0, rig.inspections)
        rig.discard(output); source.close(); restored.close(); restored.close(); clean(rig, store)
    }

    @Test fun shippedBackup10ConvertsLegacyPageButtonsWithoutRelaxingUnknownFields() = runTest {
        val rig = CodecTestRig(); val store = BackupTestStore(rig)
        val old = header(fixture(rig, store)) { root ->
            root.with("version", JsonPrimitive("1.0")).with("settings",
                root.getValue("settings").jsonObject.with("boards", buildJsonObject {
                    put("board", buildJsonObject { put("showPageButtons", false) })
                }))
        }
        val restored = codec(rig, store).read(rig.input(syntheticZip(old))).value()
        assertEquals(PageNavigation.NONE, restored.settings.boards.getValue(BoardId("board")).navigation)
        assertFalse(restored.settings.boards.getValue(BoardId("board")).dragToCompose)
        restored.close(); clean(rig, store)
        val invalid = header(old) { root -> root.with("settings", root.getValue("settings").jsonObject.with("untrusted", JsonPrimitive(true))) }
        failed(codec(rig, store).read(rig.input(syntheticZip(invalid))), ProblemCode.INVALID_DATA)
        clean(rig, store)
    }

    @Test fun fullInternalRoundTripPreservesRevisionFormsTtsRetainedSettingsAndOnlyExplicitRoots() = runTest {
        val rig = CodecTestRig(); val store = BackupTestStore(rig); val source = fullSnapshot(rig)
        val messageOnly = rig.asset("PRIVATE_MESSAGE_ONLY".encodeToByteArray(), MediaKind.AUDIO, "audio/wav")
        rig.bytes["private/credentials"] = "SECRET_PASSWORD_TOKEN".encodeToByteArray()
        val initialAssets = rig.assets.toMap()
        val output = codec(rig, store).write(source).value()
        assertFalse(source.closed); assertEquals(1, rig.openLeases)
        val actual = rig.raw(output).copyOf()
        val zipEntries = entries(rig, output)
        assertEquals(5, zipEntries.single { it.name == "assets.jsonl" }.data.decodeToString().trimEnd('\n').lines().size)
        assertFalse(zipEntries.any { messageOnly.id.value in it.name || "private" in it.name })
        val metadata = zipEntries.filter { !it.name.startsWith("assets/") }.joinToString { it.data.decodeToString() }
        for (forbidden in listOf("PRIVATE_MESSAGE_ONLY", "SECRET_PASSWORD_TOKEN", "\"epoch\"", "\"password\"", "\"undo\"", "\"message\"")) assertFalse(forbidden in metadata)
        val imported = codec(rig, store).read(output).value()
        assertContentEquals(actual, rig.raw(output))
        assertEquals(source.boardValues, imported.boards().toList())
        assertEquals(source.draftValues, imported.drafts().toList())
        assertEquals(source.retainedValues, imported.retained().toList())
        assertEquals(source.assetValues, imported.assets().toList())
        assertEquals(source.settings, imported.settings); assertEquals(91, imported.revision)
        assertEquals(17, imported.boards().toList().single().revision)
        assertEquals(23, imported.drafts().toList().single().version)
        assertIs<SpeechSource.Tts>(imported.drafts().toList().single().draft.document.pages.single().cells.single().card!!.speech)
        assertEquals(source.lease.assets, imported.lease.assets)
        assertEquals(5, rig.prepares); assertEquals(8, rig.inspections) // Four image/audio per direction; retained bytes are not decoded.
        assertEquals(initialAssets, rig.assets)
        rig.discard(output); source.close(); assertEquals(1, rig.openLeases)
        assertEquals(5, imported.assets().toList().size)
        imported.close(); clean(rig, store)
    }

    @Test fun malformedNamespacesVersionsUnknownFieldsCountsHashesAndAssetRowsFailBeforePrepareOrBuilder() = runTest {
        val rig = CodecTestRig(); val store = BackupTestStore(rig); val good = fixture(rig, store)
        val firstAsset = good.first { it.name.startsWith("assets/") }
        val rows = good.single { it.name == "assets.jsonl" }.data.decodeToString().trimEnd('\n').lines()
        val unused = rig.asset(byteArrayOf(77), MediaKind.ARCHIVE, "application/octet-stream")
        val extraRows = change(good, "assets.jsonl") { (it.decodeToString() + backupJson.encodeToString(MediaAsset.serializer(), unused) + "\n").encodeToByteArray() } + SyntheticEntry("assets/${unused.id.value}", byteArrayOf(77))
        val duplicateRows = change(good, "assets.jsonl") { (rows.toMutableList().also { it[1] = it[0] }.joinToString("\n") + "\n").encodeToByteArray() }
        val wrongKind = change(good, "assets.jsonl") {
            (rows.toMutableList().also { it[0] = boundedJson(it[0]).with("kind", JsonPrimitive("ARCHIVE")).toString() }.joinToString("\n") + "\n").encodeToByteArray()
        }
        val wrongSize = change(good, "assets.jsonl") {
            (rows.toMutableList().also { it[0] = boundedJson(it[0]).with("byteCount", JsonPrimitive(9)).toString() }.joinToString("\n") + "\n").encodeToByteArray()
        }
        val unknownRowField = change(good, "assets.jsonl") {
            (rows.toMutableList().also { it[0] = boundedJson(it[0]).with("path", JsonPrimitive("forbidden")).toString() }.joinToString("\n") + "\n").encodeToByteArray()
        }
        val board = good.single { it.name == "boards/00000000.json" }
        val draft = good.single { it.name == "drafts/00000000.json" }
        val retained = good.single { it.name == "retained/00000000.json" }
        val absentAssets = header(good.filter { !it.name.startsWith("assets/") }.map { if (it.name == "assets.jsonl") SyntheticEntry(it.name, byteArrayOf()) else it }) { root ->
            root.with("counts", root.getValue("counts").jsonObject.with("assets", JsonPrimitive(0)).with("assetBytes", JsonPrimitive(0)))
        }
        val cases = listOf(
            header(good) { it.with("version", JsonPrimitive("2.0")) },
            header(good) { it.with("epoch", JsonPrimitive(987)) },
            header(good) { it.with("settings", it.getValue("settings").jsonObject.with("token", JsonPrimitive("forbidden"))) },
            count(good, "boards", 1), count(good, "assetBytes", 1),
            good.filter { it.name != firstAsset.name },
            change(good, firstAsset.name) { byteArrayOf(99, 98) },
            good + SyntheticEntry("private/message.json", byteArrayOf(1)),
            good + SyntheticEntry("assets/NOT_A_HASH", byteArrayOf(1)),
            good + SyntheticEntry("boards/00000009.json", board.data),
            count(good + SyntheticEntry("boards/00000001.json", board.data), "boards", 1),
            count(good + SyntheticEntry("drafts/00000001.json", draft.data), "drafts", 1),
            change(good, retained.name) { boundedJson(it.decodeToString()).with("id", JsonPrimitive("board")).toString().encodeToByteArray() },
            duplicateRows, wrongKind, wrongSize, unknownRowField, absentAssets,
            count(count(extraRows, "assets", 1), "assetBytes", 1),
            change(good, board.name) { boundedJson(it.decodeToString()).with("unknownMandatory", JsonPrimitive(true)).toString().encodeToByteArray() },
        )
        for (bad in cases) {
            val original = syntheticZip(bad)
            val input = rig.input(original)
            val result = codec(rig, store).read(input)
            failed(result)
            assertEquals(0, rig.prepares); assertEquals(0, rig.inspections); assertEquals(0, store.created)
            assertContentEquals(original, rig.raw(input)); clean(rig, store)
        }
        val unsupported = codec(rig, store).read(rig.input(syntheticZip(cases.first())))
        failed(unsupported, ProblemCode.UNSUPPORTED_VERSION); clean(rig, store)
    }

    @Test fun metadataJsonAndJsonlFileLineDepthStringAndKeyBoundsAreEnforced() = runTest {
        val rig = CodecTestRig(); val store = BackupTestStore(rig); val good = fixture(rig, store)
        val board = good.single { it.name == "boards/00000000.json" }
        val hugeLine = change(good, "assets.jsonl") { ByteArray(CONFIG_LIMIT.toInt() + 1) { ' '.code.toByte() } }
        val largeRows = good.map { if (it.name == "assets.jsonl") SyntheticEntry(it.name, byteArrayOf(), method = 8, size = JSONL_LIMIT + 1) else it }
        val largeDoc = good.map { if (it.name == board.name) SyntheticEntry(it.name, byteArrayOf(), method = 8, size = CONFIG_LIMIT + 1) else it }
        val longKey = header(good) { it.with("x".repeat(257), JsonNull) }
        val deep = change(good, board.name) { bytes ->
            val root = boundedJson(bytes.decodeToString()); val document = root.getValue("document").jsonObject
            root.with("document", document.with("extensionsJson", JsonPrimitive("{\"nested\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}"))).toString().encodeToByteArray()
        }
        val longString = change(good, board.name) { bytes ->
            val root = boundedJson(bytes.decodeToString()); root.with("document", root.getValue("document").jsonObject.with("name", JsonPrimitive("x".repeat(65537)))).toString().encodeToByteArray()
        }
        for (bad in listOf(hugeLine, largeRows, largeDoc, longKey, deep, longString)) {
            failed(codec(rig, store).read(rig.input(syntheticZip(bad))), ProblemCode.LIMIT_EXCEEDED)
            assertEquals(0, rig.prepares); assertEquals(0, store.created); clean(rig, store)
        }
    }

    @Test fun incompleteOrInconsistentSnapshotFlowsNeverProduceABackupOrConsumeCaller() = runTest {
        for (fault in 0..6) {
            val rig = CodecTestRig(); val store = BackupTestStore(rig); val original = fullSnapshot(rig)
            val source = when (fault) {
                0 -> TestSnapshot(rig, original.boardValues + original.boardValues, original.draftValues, original.retainedValues, original.assetValues)
                1 -> TestSnapshot(rig, original.boardValues, original.draftValues, original.retainedValues, original.assetValues.dropLast(1))
                2 -> TestSnapshot(rig, original.boardValues, original.draftValues, original.retainedValues, original.assetValues + original.assetValues.first())
                3 -> TestSnapshot(rig, original.boardValues, original.draftValues, original.retainedValues, original.assetValues, counts = original.counts.copy(boards = 2))
                6 -> TestSnapshot(rig, original.boardValues, original.draftValues, original.retainedValues, original.assetValues + rig.asset("PRIVATE_MESSAGE_ONLY".encodeToByteArray(), MediaKind.AUDIO, "audio/wav"))
                else -> TestSnapshot(rig, original.boardValues, original.draftValues, original.retainedValues, original.assetValues)
            }
            original.close()
            if (fault == 4) source.beforeEmit = { name, _ -> if (name == "assets") throw SnapshotReadException(Problem(ProblemCode.PERMISSION_DENIED)) }
            if (fault == 5) source.secondAssets = source.assetValues.reversed()
            val result = codec(rig, store).write(source)
            failed(result, if (fault == 4) ProblemCode.PERMISSION_DENIED else null)
            assertFalse(source.closed); clean(rig, store, leases = 1)
            source.close(); clean(rig, store)
        }
    }

    @Test fun prepareBuilderAndSealOutcomesRemainDistinctAndCleanOnlyOwnedResources() = runTest {
        for (point in 0..4) for (outcome in listOf(OperationResult.Cancelled, OperationResult.UnconfirmedCommit, OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED)))) {
            val rig = CodecTestRig(); val store = BackupTestStore(rig); val good = fixture(rig, store)
            when (point) {
                0 -> store.createOutcome = outcome
                1 -> { store.failAddAt = 1; store.addOutcome = outcome }
                2 -> { store.failAddAt = 4; store.addOutcome = outcome }
                3 -> { rig.failPrepareAt = 2; rig.prepareOutcome = outcome }
                4 -> store.sealOverride = { _, _, _ -> outcome }
            }
            assertEquals(outcome, codec(rig, store).read(rig.input(syntheticZip(good))))
            assertEquals(5, rig.assets.size); clean(rig, store)
        }
    }

    @Test fun inspectionFailureAndCorruptOrUnreadableSourcesNeverHandOffAReader() = runTest {
        for (fault in 0..3) {
            val rig = CodecTestRig(); val store = BackupTestStore(rig); val good = fixture(rig, store)
            when (fault) {
                0 -> rig.inspectionOutcome = OperationResult.Failed(Problem(ProblemCode.UNSUPPORTED_MEDIA))
                1 -> rig.badHash = true
                2 -> rig.imageInfo = ImageInfo(8193, 1, "image/png")
                3 -> rig.failSinkWrites = true
            }
            failed(codec(rig, store).read(rig.input(syntheticZip(good))))
            clean(rig, store)
        }
    }

    @Test fun writeIoUnknownCancellationAndCleanupPreserveCallerAndPrimaryOutcome() = runTest {
        for (fault in 0..6) {
            val rig = CodecTestRig(); val store = BackupTestStore(rig); val snapshot = fullSnapshot(rig)
            val media = if (fault == 1) object : MediaStore by rig {
                override fun source(id: AssetId, lease: MediaLease): OperationResult<Source> = OperationResult.UnconfirmedCommit
            } else rig
            when (fault) {
                0 -> rig.failSinkWrites = true
                1 -> rig.failDiscardAfterRemoval = true
                2 -> snapshot.beforeEmit = { _, _ -> throw CancellationException("synthetic flow cancellation") }
                3 -> rig.failDiscardAfterRemoval = true
                4 -> rig.failSourcePath = snapshot.assetValues.first().id.value
                5 -> rig.inspectionOutcome = OperationResult.Failed(Problem(ProblemCode.UNSUPPORTED_MEDIA))
                6 -> rig.bytes[snapshot.assetValues.first().id.value] = byteArrayOf(99, 98)
            }
            if (fault == 2) assertFailsWith<CancellationException> { codec(rig, store, media).write(snapshot) }
            else {
                val result = codec(rig, store, media).write(snapshot)
                if (fault == 1) assertEquals(OperationResult.UnconfirmedCommit, result) else failed(result)
            }
            assertFalse(snapshot.closed); clean(rig, store, leases = 1)
            snapshot.close(); clean(rig, store)
        }
    }

    @Test fun cleanupFaultsDoNotMaskUnconfirmedAndCloseSealedReaderBeforeFailedHandoff() = runTest {
        for (unknown in listOf(false, true)) {
            val rig = CodecTestRig(); val store = BackupTestStore(rig); val good = fixture(rig, store)
            rig.failDiscardAfterRemoval = true
            if (unknown) { rig.failPrepareAt = 2; rig.prepareOutcome = OperationResult.UnconfirmedCommit }
            val result = codec(rig, store).read(rig.input(syntheticZip(good)))
            if (unknown) assertEquals(OperationResult.UnconfirmedCommit, result) else { failed(result, ProblemCode.IO_FAILURE); assertTrue(store.returned!!.closed) }
            clean(rig, store)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun lateNonCooperativeSealAfterCancellationClosesReturnedReaderAndEveryPartialLease() = runTest {
        val rig = CodecTestRig(); val store = BackupTestStore(rig); val good = fixture(rig, store)
        var pending: Continuation<OperationResult<SnapshotReader>>? = null
        var builder: BackupTestStore.Builder? = null
        var revision = 0L; var settings = PortableSettings()
        store.sealOverride = { own, rev, prefs -> builder = own; revision = rev; settings = prefs; suspendCoroutine { pending = it } }
        val bytes = syntheticZip(good); val input = rig.input(bytes)
        var handedOff = false; var cancelled = false
        val owner = launch {
            try { codec(rig, store).read(input); handedOff = true } catch (error: CancellationException) { cancelled = true; throw error }
        }
        runCurrent(); assertNotNull(pending); owner.cancel()
        val lateReader = builder!!.reader(revision, settings)
        pending!!.resume(OperationResult.Completed(lateReader))
        owner.join()
        assertTrue(cancelled); assertFalse(handedOff); assertTrue(lateReader.closed)
        assertEquals(5, rig.assets.size); assertContentEquals(bytes, rig.raw(input)); clean(rig, store)
    }

    @Test fun retainedArchiveBudgetIsNotLinkaMediaBudgetAndNeverInvokesAnArchiveDecoder() {
        val rig = CodecTestRig()
        val id = AssetId("a".repeat(64))
        val refs = BackupReferences()
        refs.retained(RetainedArchive(BoardId("raw"), "raw", 1, id, RetainedReason.INVALID_ARCHIVE))
        refs.asset(MediaAsset(id, MediaKind.ARCHIVE, "application/octet-stream", MEDIA_LIMIT + 1))
        refs.asset(MediaAsset(id, MediaKind.ARCHIVE, "application/octet-stream", BACKUP_LIMIT))
        assertEquals(BACKUP_LIMIT, ZipProfile.BACKUP.entryBytes("assets/${id.value}"))
        assertEquals(MEDIA_LIMIT, ZipProfile.LINKA.entryBytes("assets/${id.value}"))
        assertEquals(0, rig.inspections)
        assertFailsWith<CodecFailure> { refs.asset(MediaAsset(id, MediaKind.ARCHIVE, "application/octet-stream", BACKUP_LIMIT + 1)) }
        checkCounts(SnapshotCounts(1, 0, 0, 59997, BACKUP_LIMIT))
        assertFailsWith<CodecFailure> { checkCounts(SnapshotCounts(1, 0, 0, 59998, BACKUP_LIMIT)) }
        assertFailsWith<CodecFailure> { checkCounts(SnapshotCounts(0, 0, 0, 0, Long.MAX_VALUE)) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun nonCooperativePrepareCannotLeakItsLateLeaseAfterCancellation() = runTest {
        val rig = CodecTestRig(); val store = BackupTestStore(rig); val good = fixture(rig, store)
        var pending: Continuation<OperationResult<PreparedMedia>>? = null
        var prepared: OperationResult<PreparedMedia>? = null
        val lateMedia = object : MediaStore by rig {
            override suspend fun prepare(source: Source, kind: MediaKind, mediaType: String): OperationResult<PreparedMedia> {
                prepared = rig.prepare(source, kind, mediaType)
                return suspendCoroutine { pending = it }
            }
        }
        var handedOff = false; var cancelled = false
        val owner = launch {
            try { codec(rig, store, lateMedia).read(rig.input(syntheticZip(good))); handedOff = true }
            catch (error: CancellationException) { cancelled = true; throw error }
        }
        runCurrent(); assertNotNull(pending); assertEquals(1, rig.openLeases)
        owner.cancel(); pending!!.resume(prepared!!); owner.join()
        assertTrue(cancelled); assertFalse(handedOff); assertNull(store.returned)
        assertEquals(5, rig.assets.size); clean(rig, store)
    }

    @Test fun sourceReadAndDecoderOrSealCancellationKeepProblemsDistinctAndCleanPartials() = runTest {
        for (point in 0..2) {
            val rig = CodecTestRig(); val store = BackupTestStore(rig); val good = fixture(rig, store)
            val input = rig.input(syntheticZip(good))
            when (point) {
                0 -> rig.failSourcePath = input.path
                1 -> rig.onInspection = { throw CancellationException("synthetic inspection cancellation") }
                2 -> store.sealOverride = { _, _, _ -> throw CancellationException("synthetic seal cancellation") }
            }
            if (point == 0) failed(codec(rig, store).read(input), ProblemCode.IO_FAILURE)
            else assertFailsWith<CancellationException> { codec(rig, store).read(input) }
            assertNull(store.returned); assertEquals(5, rig.assets.size); clean(rig, store)
        }
    }

    @Test fun aSealedReaderWithWrongHeaderIsClosedRatherThanPublished() = runTest {
        val rig = CodecTestRig(); val store = BackupTestStore(rig); val good = fixture(rig, store)
        store.sealOverride = { builder, revision, settings -> OperationResult.Completed(builder.reader(revision + 1, settings)) }
        failed(codec(rig, store).read(rig.input(syntheticZip(good))), ProblemCode.INVALID_DATA)
        assertTrue(store.returned!!.closed); clean(rig, store)
    }

    @Test fun jsonlAcceptsAnExactBoundedLineAndTheLastRowWithoutNewline() = runTest {
        val rig = CodecTestRig(); val store = BackupTestStore(rig); val good = fixture(rig, store)
        val bounded = change(good, "assets.jsonl") { bytes ->
            val lines = bytes.decodeToString().trimEnd('\n').lines().toMutableList()
            lines[0] += " ".repeat(CONFIG_LIMIT.toInt() - lines[0].encodeToByteArray().size)
            lines.joinToString("\n").encodeToByteArray()
        }
        val result = codec(rig, store).read(rig.input(syntheticZip(bounded))).value()
        assertEquals(5, result.assets().toList().size)
        result.close(); clean(rig, store)
    }
}
