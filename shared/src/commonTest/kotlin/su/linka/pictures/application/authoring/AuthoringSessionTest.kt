package su.linka.pictures.application.authoring

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.storage.StorageRuntime
import kotlin.test.*
import kotlin.coroutines.CoroutineContext
import okio.Source
import okio.Path
import su.linka.pictures.integration.CoreBackupFiles
import su.linka.pictures.portability.backup.BackupCodec

class AuthoringSessionTest {
    private class ControlledLibrary(private val real: LibraryStore, private val leases: AuthoringLeaseProbe) : LibraryStore by real {
        data class Save(val draft: EditorDraft, val expected: Long?, val permit: WritePermit,
            val reply: CompletableDeferred<OperationResult<Unit>> = CompletableDeferred())
        val saves = Channel<Save>(Channel.UNLIMITED)
        var delayed = false
        var publishResult: OperationResult<PublishedBoard>? = null
        var listingOverride: StateFlow<LibraryListing>? = null
        override val listing get() = listingOverride ?: real.listing
        var readGate: CompletableDeferred<Unit>? = null
        val readEntered = CompletableDeferred<Unit>()
        override suspend fun board(id: BoardId): OperationResult<LeasedBoard> {
            val result = when (val value = real.board(id)) {
                is OperationResult.Completed -> OperationResult.Completed(value.value.copy(lease = leases.own(value.value.lease)))
                else -> value
            }
            readEntered.complete(Unit)
            readGate?.let { withContext(NonCancellable) { it.await() } }
            return result
        }
        override suspend fun draft(id: DraftId): OperationResult<LeasedDraft> = when (val value = real.draft(id)) {
            is OperationResult.Completed -> OperationResult.Completed(value.value.copy(lease = leases.own(value.value.lease)))
            else -> value
        }
        override suspend fun saveDraft(value: EditorDraft, expectedVersion: Long?, permit: WritePermit): OperationResult<StoredDraft> {
            if (delayed) {
                val save = Save(value, expectedVersion, permit)
                saves.send(save)
                when (val reply = save.reply.await()) {
                    is OperationResult.Completed -> Unit
                    is OperationResult.Failed -> return reply
                    OperationResult.Cancelled -> return OperationResult.Cancelled
                    OperationResult.UnconfirmedCommit -> return OperationResult.UnconfirmedCommit
                }
            }
            return real.saveDraft(value, expectedVersion, permit)
        }
        override suspend fun publishDraft(id: DraftId, expectedDraftVersion: Long, expectedBoardRevision: Long?, permit: WritePermit) =
            publishResult ?: real.publishDraft(id, expectedDraftVersion, expectedBoardRevision, permit)
    }

    private class ControlledMedia(private val real: MediaStore, private val leases: AuthoringLeaseProbe) : MediaStore by real {
        var prepareGate: CompletableDeferred<Unit>? = null
        val prepared = CompletableDeferred<Unit>()
        override suspend fun prepare(source: Source, kind: MediaKind, mediaType: String): OperationResult<PreparedMedia> {
            val result = when (val value = real.prepare(source, kind, mediaType)) {
                is OperationResult.Completed -> OperationResult.Completed(value.value.copy(lease = leases.own(value.value.lease)))
                else -> value
            }
            prepared.complete(Unit)
            prepareGate?.let { withContext(NonCancellable) { it.await() } }
            return result
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val tasks = Channel<Runnable>(Channel.UNLIMITED)
        override fun dispatch(context: CoroutineContext, block: Runnable) { check(tasks.trySend(block).isSuccess) }
        fun runAll() { while (true) (tasks.tryReceive().getOrNull() ?: return).run() }
        suspend fun until(predicate: () -> Boolean) = withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (!predicate()) { runAll(); yield() } }
        }
    }

    private class Fixture(val runtime: StorageRuntime, private val dispatcher: CoroutineDispatcher, val root: Path) {
        val native = AuthoringNativeFakes()
        val leases = AuthoringLeaseProbe()
        val library = ControlledLibrary(runtime.library, leases)
        val media = ControlledMedia(runtime.media, leases)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val serial = MutableStateFlow(0)
        private fun id() = "authoring-${serial.updateAndGet { it + 1 }}"
        val session = AuthoringSession(library, media, native, native, native, native, native, IdSource { id() }, scope)
        var capability: AssistantCapability? = null
        fun send(action: AuthoringAction) { assertTrue(session.send(AuthoringCommand(CommandId(id()), "owner", capability, action))) }
        suspend fun await(predicate: (AuthoringState) -> Boolean): AuthoringState = withContext(Dispatchers.Default) {
            if (dispatcher is QueuedDispatcher) dispatcher.until { predicate(session.state.value) }
            withTimeout(10_000) { session.state.first(predicate) }
        }
        suspend fun saved(): AuthoringState = await { it.savePhase == DraftSavePhase.SAVED }
        suspend fun nextSave(): ControlledLibrary.Save = withContext(Dispatchers.Default) { withTimeout(10_000) { library.saves.receive() } }
        suspend fun start() { send(AuthoringAction.NewBoard()); saved() }
        fun details(name: String) { send(AuthoringAction.Details(name, "Описание", BoardBehavior())) }
        suspend fun open(position: Int = 0) {
            val page = session.state.value.draft!!.document.pages.first().id
            send(AuthoringAction.OpenCard(page, position)); await { it.draft?.form is EditorForm.Card }
        }
        suspend fun finish() {
            scope.cancel()
            if (dispatcher is QueuedDispatcher) dispatcher.until { scope.coroutineContext[Job]!!.isCompleted }
            scope.coroutineContext[Job]!!.join()
        }
    }

    private suspend fun fresh(dispatcher: CoroutineDispatcher = Dispatchers.Default, block: suspend (Fixture) -> Unit) {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "p11-authoring-${kotlin.random.Random.nextLong()}"
        fs.createDirectory(root, mustCreate = true)
        val runtime = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(root, fs, BundledSQLiteDriver())).value
        val fixture = Fixture(runtime, dispatcher, root)
        try { block(fixture) } finally { fixture.finish(); runtime.close(); fs.deleteRecursively(root) }
    }

    @Test fun kindsAutosaveHiddenMediaBackupAndResumeAfterActualRuntimeReopen() = runTest {
        for (kind in listOf(CellKind.SPACE, CellKind.EMPTY)) fresh { f ->
            f.start(); f.open()
            f.send(AuthoringAction.ChangeCard("Скрытая подпись", "Скрытая речь", SpeechChoice.TTS, false))
            f.await { (it.draft?.form as? EditorForm.Card)?.label == "Скрытая подпись" }
            f.send(AuthoringAction.PickImage)
            f.await { (it.draft?.form as? EditorForm.Card)?.image != null && !it.mediaBusy }
            f.send(AuthoringAction.PickAudio)
            f.await { (it.draft?.form as? EditorForm.Card)?.recording != null && !it.mediaBusy }
            val content = f.session.state.value.draft!!.form as EditorForm.Card
            val document = f.session.state.value.draft!!.document
            f.send(AuthoringAction.ChangeCardKind(kind))
            val saved = f.await { (it.draft?.form as? EditorForm.Card)?.kind == kind && it.savePhase == DraftSavePhase.SAVED }.draft!!
            assertEquals(content.copy(kind = kind), saved.form)
            assertEquals(document, saved.document)
            val roots = setOf(content.image!!, content.recording!!)
            assertEquals(roots, saved.assetIds())
            assertEquals(0, assertIs<OperationResult.Completed<GarbageCollectionReport>>(f.runtime.media.collectGarbage()).value.removedAssets)
            val filesRoot = f.root / "backup-files"
            FileSystem.SYSTEM.createDirectory(filesRoot)
            CoreBackupFiles(FileSystem.SYSTEM, filesRoot).use { files ->
                val snapshot = assertIs<OperationResult.Completed<SnapshotReader>>(f.runtime.library.snapshot()).value
                try {
                    assertEquals(roots, snapshot.lease.assets)
                    val codec = BackupCodec(f.runtime.media, files, f.native, f.runtime.library)
                    val output = assertIs<OperationResult.Completed<TemporaryFile>>(codec.write(snapshot)).value
                    val imported = assertIs<OperationResult.Completed<SnapshotReader>>(codec.read(output)).value
                    try {
                        assertEquals(listOf(saved), imported.drafts().toList().map { it.draft })
                        assertEquals(roots, imported.lease.assets)
                    } finally { imported.close() }
                } finally { snapshot.close() }
            }
            f.finish(); f.runtime.close()
            val reopened = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(f.root, FileSystem.SYSTEM, BundledSQLiteDriver())).value
            val resumed = Fixture(reopened, Dispatchers.Default, f.root)
            try {
                resumed.send(AuthoringAction.ResumeDraft(saved.id))
                assertEquals(saved, resumed.await { it.phase == AuthoringPhase.EDITING }.draft)
                assertEquals(0, resumed.native.recordCalls.value)
                resumed.send(AuthoringAction.ChangeCardKind(CellKind.CONTENT))
                assertEquals(content, resumed.await { (it.draft?.form as? EditorForm.Card)?.kind == CellKind.CONTENT && it.savePhase == DraftSavePhase.SAVED }.draft!!.form)
                resumed.send(AuthoringAction.CancelCard)
                assertEquals(document, resumed.await { it.draft?.form == null && it.savePhase == DraftSavePhase.SAVED }.draft!!.document)
            } finally { resumed.finish(); reopened.close() }
        }
    }

    @Test fun everySourceKindOpensExactlyAndExplicitSetContentHasOneCorrectSnapshot() = runTest {
        for (kind in CellKind.entries) fresh { f ->
            val card = if (kind == CellKind.CONTENT) CardDocument(CardId("source"), "Подпись", extensionsJson = "{\"card\":1}") else null
            val doc = BoardDocument(BoardId("source"), "Источник", listOf(BoardPage(PageId("page"), 1, 2,
                listOf(BoardCell(0, kind, card, "{\"cell\":1}")))))
            assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, WritePermit(0) { OperationResult.Completed(Unit) }))
            f.send(AuthoringAction.EditBoard(doc.id)); f.saved(); f.open(); f.saved()
            assertEquals(kind, (f.session.state.value.draft!!.form as EditorForm.Card).kind)
            f.send(AuthoringAction.CancelCard)
            assertEquals(doc, f.await { it.draft?.form == null && it.savePhase == DraftSavePhase.SAVED }.draft!!.document)
            f.library.delayed = true
            f.send(AuthoringAction.SetCell(PageId("page"), 0, CellKind.CONTENT))
            val opened = f.nextSave()
            assertEquals(CellKind.CONTENT, (opened.draft.form as EditorForm.Card).kind)
            assertEquals(doc, opened.draft.document)
            opened.reply.complete(OperationResult.Completed(Unit)); f.saved()
            assertTrue(f.library.saves.tryReceive().isFailure)
        }
    }

    @Test fun saveEveryKindIsAtomicPreservesMetadataAndDoesNotPublishOrChangeMessageBeforeApply() = runTest {
        for (kind in CellKind.entries) fresh { f ->
            val original = CardDocument(CardId("source"), "Исходная", extensionsJson = "{\"card\":1}")
            val doc = BoardDocument(BoardId("source"), "Источник", listOf(BoardPage(PageId("page"), 1, 1,
                listOf(BoardCell(0, CellKind.CONTENT, original, "{\"cell\":1}")))))
            val permit = WritePermit(0) { OperationResult.Completed(Unit) }
            assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, permit))
            val message = StoredMessage(0, 1, MessageDraft(listOf(MessagePart.Card(MessagePartId("message"),
                CardSelectionSnapshot(SelectionOrigin(doc.id, 1, PageId("page"), original.id), original.label, null, original.speech)))))
            assertIs<OperationResult.Completed<Unit>>(f.runtime.messages.saveMessage(message))
            f.send(AuthoringAction.EditBoard(doc.id)); f.saved(); f.open()
            f.send(AuthoringAction.ChangeCard("", "", if (kind == CellKind.CONTENT) SpeechChoice.NONE else SpeechChoice.RECORDING, true))
            val beforeKind = f.await { (it.draft?.form as? EditorForm.Card)?.label == "" && it.savePhase == DraftSavePhase.SAVED }.durableVersion!!
            f.send(AuthoringAction.ChangeCardKind(kind))
            f.await { (it.draft?.form as? EditorForm.Card)?.kind == kind && it.savePhase == DraftSavePhase.SAVED && it.durableVersion!! > beforeKind }
            f.library.delayed = true
            f.send(AuthoringAction.SaveCard)
            val save = f.nextSave()
            assertNull(save.draft.form)
            val cell = save.draft.document.pages.single().cells.single()
            assertEquals(kind, cell.kind); assertEquals("{\"cell\":1}", cell.extensionsJson)
            if (kind == CellKind.CONTENT) {
                assertEquals(original.id, cell.card!!.id)
                assertEquals(original.extensionsJson, cell.card.extensionsJson)
                assertEquals("", cell.card.label)
            } else assertNull(cell.card)
            val before = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(save.draft.id)).value
            assertNotNull(before.draft.draft.form); assertEquals(doc, before.draft.draft.document); before.lease.close()
            save.reply.complete(OperationResult.Completed(Unit)); f.saved()
            assertTrue(f.library.saves.tryReceive().isFailure)
            val published = assertIs<OperationResult.Completed<LeasedBoard>>(f.runtime.library.board(doc.id)).value
            assertEquals(doc, published.board.document); published.lease.close()
            assertEquals(message, assertIs<OperationResult.Completed<StoredMessage>>(f.runtime.messages.message()).value)
            f.send(AuthoringAction.Apply)
            assertEquals(kind, f.await { it.phase == AuthoringPhase.APPLIED }.published!!.document.pages[0].cells[0].kind)
            assertEquals(message, assertIs<OperationResult.Completed<StoredMessage>>(f.runtime.messages.message()).value)
        }
    }

    @Test fun nonContentCommandsRejectBeforeAnyMediaOrAudioIoAndFinishCannotRestartRecording() = runTest {
        for (kind in listOf(CellKind.SPACE, CellKind.EMPTY)) fresh { f ->
            f.start(); f.open()
            f.send(AuthoringAction.ChangeCard("Текст", "Речь", SpeechChoice.TTS, false))
            f.send(AuthoringAction.ChangeCardKind(kind))
            f.await { (it.draft?.form as? EditorForm.Card)?.kind == kind && it.savePhase == DraftSavePhase.SAVED }
            val before = f.session.state.value.draft
            for (action in listOf(AuthoringAction.PickImage, AuthoringAction.PickAudio, AuthoringAction.Record,
                AuthoringAction.ImageFromText("Текст"), AuthoringAction.Synthesize, AuthoringAction.Preview)) f.send(action)
            f.send(AuthoringAction.FinishRecording); f.send(AuthoringAction.CancelMedia)
            // A serial actor sentinel proves all preceding commands have been handled.
            f.details("Барьер")
            f.await { it.draft?.document?.name == "Барьер" && it.savePhase == DraftSavePhase.SAVED }
            assertEquals(ProblemCode.INVALID_DATA, f.session.state.value.problem?.code)
            assertEquals(before!!.form, f.session.state.value.draft!!.form)
            assertEquals(0, f.native.mediaIoCalls.value)
            assertEquals(0, f.native.recordCalls.value); assertTrue(f.native.played.isEmpty())
            assertFalse(f.session.state.value.mediaBusy)
        }
    }

    @Test fun kindSwitchCancelsPendingPickerAndDiscardsLateOwnedTemporaryWithoutAdoption() = runTest { fresh { f ->
        f.start(); f.open()
        val pending = CompletableDeferred<OperationResult<TemporaryFile>>()
        f.native.pickResult = pending; f.native.ignorePickCancellation = true
        f.send(AuthoringAction.PickImage); f.await { it.mediaBusy }
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.pickEntered.await() } }
        f.send(AuthoringAction.ChangeCardKind(CellKind.SPACE))
        f.await { (it.draft?.form as? EditorForm.Card)?.kind == CellKind.SPACE && !it.mediaBusy }
        val file = f.native.temporary(); pending.complete(OperationResult.Completed(file))
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.discarded.first { file.path in it } } }
        assertNull((f.session.state.value.draft!!.form as EditorForm.Card).image)
        f.send(AuthoringAction.ChangeCardKind(CellKind.CONTENT))
        f.await { (it.draft?.form as? EditorForm.Card)?.kind == CellKind.CONTENT && it.savePhase == DraftSavePhase.SAVED }
        assertNull((f.session.state.value.draft!!.form as EditorForm.Card).image)
        f.finish(); assertEquals(0, f.leases.active.value)
    } }

    @Test fun kindSwitchCancelsRecordingAndDoesNotResumeItOnReturnToContent() = runTest { fresh { f ->
        f.start(); f.open(); f.native.recording = CompletableDeferred()
        f.send(AuthoringAction.Record); f.await { it.audio.phase == AudioPhase.RECORDING }
        f.send(AuthoringAction.ChangeCardKind(CellKind.EMPTY))
        f.await { (it.draft?.form as? EditorForm.Card)?.kind == CellKind.EMPTY && !it.mediaBusy }
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.recordingCancelled.await() } }
        f.send(AuthoringAction.FinishRecording)
        f.send(AuthoringAction.ChangeCardKind(CellKind.CONTENT))
        f.await { (it.draft?.form as? EditorForm.Card)?.kind == CellKind.CONTENT && it.savePhase == DraftSavePhase.SAVED }
        assertNull((f.session.state.value.draft!!.form as EditorForm.Card).recording)
        assertEquals(1, f.native.recordCalls.value)
        assertEquals(AudioPhase.IDLE, f.session.state.value.audio.phase)
    } }

    @Test fun cancellingEveryChangedFormKindLeavesOriginalDocumentExact() = runTest {
        for (kind in CellKind.entries) fresh { f ->
            val card = CardDocument(CardId("cancel"), "Не менять", extensionsJson = "{\"card\":7}")
            val document = BoardDocument(BoardId("cancel"), "Источник", listOf(BoardPage(PageId("page"), 1, 1,
                listOf(BoardCell(0, CellKind.CONTENT, card, "{\"cell\":8}")))))
            assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(document, WritePermit(0) { OperationResult.Completed(Unit) }))
            f.send(AuthoringAction.EditBoard(document.id)); f.saved(); f.open()
            f.send(AuthoringAction.ChangeCard("Не сохранять", "", SpeechChoice.RECORDING, false))
            f.send(AuthoringAction.ChangeCardKind(kind)); f.send(AuthoringAction.CancelCard)
            val cancelled = f.await { it.draft?.form == null && it.savePhase == DraftSavePhase.SAVED }
            assertEquals(document, cancelled.draft!!.document)
            val stored = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(cancelled.draft.id)).value
            assertEquals(document, stored.draft.draft.document); assertNull(stored.draft.draft.form); stored.lease.close()
        }
    }

    @Test fun kindCommandDropsPendingReauthenticationAdoptionButDoesNotBypassPausedGate() = runTest { fresh { f ->
        f.capability = prepareUnauthorizedPickerResult(f)
        assertEquals(1, f.leases.active.value)
        f.send(AuthoringAction.ChangeCardKind(CellKind.SPACE))
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.leases.active.first { it == 0 } } }
        assertEquals(AuthoringPhase.PAUSED, f.session.state.value.phase)
        assertEquals(CellKind.CONTENT, (f.session.state.value.draft!!.form as EditorForm.Card).kind)
        f.send(AuthoringAction.Resume); f.await { it.phase == AuthoringPhase.EDITING }
        f.send(AuthoringAction.ChangeCardKind(CellKind.SPACE))
        f.await { (it.draft?.form as? EditorForm.Card)?.kind == CellKind.SPACE && it.savePhase == DraftSavePhase.SAVED }
        assertNull((f.session.state.value.draft!!.form as EditorForm.Card).image)
        assertEquals(1, assertIs<OperationResult.Completed<GarbageCollectionReport>>(f.runtime.media.collectGarbage()).value.removedAssets)
    } }

    @Test fun conflictAllowsKindEditsButCannotApplyAndUnknownCommitIgnoresKindCommands() = runTest { fresh { f ->
        f.start(); f.open(); f.saved()
        f.library.delayed = true
        f.send(AuthoringAction.ChangeCardKind(CellKind.SPACE))
        f.nextSave().reply.complete(OperationResult.Failed(Problem(ProblemCode.CONFLICT)))
        f.await { it.phase == AuthoringPhase.CONFLICT }
        f.send(AuthoringAction.ChangeCardKind(CellKind.EMPTY))
        val conflicted = f.nextSave()
        assertEquals(CellKind.EMPTY, (conflicted.draft.form as EditorForm.Card).kind)
        conflicted.reply.complete(OperationResult.Completed(Unit)); f.saved()
        f.send(AuthoringAction.CancelCard)
        val closingForm = f.nextSave()
        closingForm.reply.complete(OperationResult.Completed(Unit))
        f.await { it.draft?.form == null && it.savePhase == DraftSavePhase.SAVED }
        f.send(AuthoringAction.Apply); f.await { it.problem?.code == ProblemCode.CONFLICT }
        f.open()
        f.nextSave().reply.complete(OperationResult.UnconfirmedCommit)
        val frozen = f.await { it.phase == AuthoringPhase.UNCONFIRMED }
        f.send(AuthoringAction.ChangeCardKind(CellKind.SPACE))
        f.finish(); assertEquals(frozen, f.session.state.value)
        assertTrue(f.library.saves.tryReceive().isFailure)
    } }

    @Test fun createFormSaveAndApplyUseRealSqliteAndDistinctLabelSpeech() = runTest { fresh { f ->
        f.start(); f.open()
        f.send(AuthoringAction.ChangeCard("Каша", "Хочу кашу", SpeechChoice.TTS, false))
        f.await { (it.draft?.form as? EditorForm.Card)?.label == "Каша" }
        f.send(AuthoringAction.Apply)
        f.await { it.problem?.code == ProblemCode.UNFINISHED_FORM }
        f.send(AuthoringAction.SaveCard)
        val saved = f.await { it.draft?.form == null && it.savePhase == DraftSavePhase.SAVED }
        val id = saved.draft!!.id
        val leased = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(id)).value
        assertEquals("Хочу кашу", assertIs<SpeechSource.Tts>(leased.draft.draft.document.pages[0].cells[0].card!!.speech).text)
        leased.lease.close()
        f.send(AuthoringAction.Apply)
        val applied = f.await { it.phase == AuthoringPhase.APPLIED }
        assertEquals("Каша", applied.published!!.document.pages[0].cells[0].card!!.label)
        assertIs<OperationResult.Failed>(f.runtime.library.draft(id))
    } }

    @Test fun coalescingKeepsImmediateStateAndApplyWaitsForLatestDurableSnapshot() = runTest { fresh { f ->
        f.start(); f.library.delayed = true
        f.details("Первое")
        val first = f.nextSave()
        f.details("Второе"); f.details("Последнее")
        f.await { it.draft?.document?.name == "Последнее" }
        assertEquals("Первое", first.draft.document.name)
        f.send(AuthoringAction.Apply)
        first.reply.complete(OperationResult.Completed(Unit))
        val latest = f.nextSave()
        assertEquals("Последнее", latest.draft.document.name)
        assertEquals(first.expected!! + 1, latest.expected)
        assertNull(f.session.state.value.published)
        latest.reply.complete(OperationResult.Completed(Unit))
        assertEquals("Последнее", f.await { it.phase == AuthoringPhase.APPLIED }.published!!.document.name)
        assertTrue(f.library.saves.tryReceive().isFailure)
    } }

    @Test fun ioFailureCloseAndRetryNeverClaimUnsavedChangesAreDurable() = runTest { fresh { f ->
        f.start(); f.library.delayed = true
        f.details("Не записано")
        f.nextSave().reply.complete(OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)))
        f.await { it.savePhase == DraftSavePhase.FAILED }
        f.send(AuthoringAction.Close)
        f.await { it.problem?.code == ProblemCode.UNFINISHED_FORM }
        assertEquals("Не записано", f.session.state.value.draft!!.document.name)
        f.send(AuthoringAction.RetrySave)
        f.nextSave().reply.complete(OperationResult.Completed(Unit))
        f.saved()
        f.send(AuthoringAction.Close)
        f.await { it.phase == AuthoringPhase.CLOSED }
    } }

    @Test fun backgroundInvalidatesAlreadyQueuedWritesAndRequiresExplicitResume() = runTest { fresh { f ->
        f.start(); f.library.delayed = true
        f.details("Сохранить после входа")
        val save = f.nextSave()
        f.send(AuthoringAction.Background)
        assertIs<OperationResult.Failed>(save.permit.check())
        save.reply.complete(OperationResult.Completed(Unit))
        f.await { it.phase == AuthoringPhase.PAUSED && it.savePhase == DraftSavePhase.FAILED }
        f.native.authorized = false
        f.send(AuthoringAction.Resume)
        f.await { it.problem?.code == ProblemCode.UNAUTHORIZED }
        assertEquals(AuthoringPhase.PAUSED, f.session.state.value.phase)
        f.native.authorized = true
        f.send(AuthoringAction.Resume)
        f.nextSave().reply.complete(OperationResult.Completed(Unit))
        f.saved()
    } }

    @Test fun unknownCommitQuarantinesWithoutRetryOrApply() = runTest { fresh { f ->
        f.start(); f.library.delayed = true
        f.details("Неизвестно")
        f.nextSave().reply.complete(OperationResult.UnconfirmedCommit)
        val unknown = f.await { it.phase == AuthoringPhase.UNCONFIRMED }
        f.send(AuthoringAction.RetrySave); f.send(AuthoringAction.Apply); f.details("Запрещено")
        // Commands are ordered; awaiting a sentinel is not possible in a frozen session, so inspect no-effect ports after cancellation.
        f.finish()
        assertEquals(unknown, f.session.state.value)
        assertTrue(f.library.saves.tryReceive().isFailure)
    } }

    @Test fun changedAndDeletedBoardsConflictWithoutLosingDurableDraft() = runTest { fresh { f ->
        val doc = BoardDocument(BoardId("source"), "Источник", listOf(BoardPage(PageId("page"), 1, 1, emptyList())))
        val permit = WritePermit(0) { OperationResult.Completed(Unit) }
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, permit))
        f.send(AuthoringAction.EditBoard(doc.id)); f.saved()
        val draft = f.session.state.value.draft!!
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.renameBoard(doc.id, "Чужая правка", 1, permit))
        f.send(AuthoringAction.Apply); f.await { it.phase == AuthoringPhase.CONFLICT }
        val retained = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(draft.id)).value
        assertEquals("Источник", retained.draft.draft.document.name); retained.lease.close()
        assertIs<OperationResult.Completed<Unit>>(f.runtime.library.deleteBoard(doc.id, 2, permit))
        f.send(AuthoringAction.Apply)
        assertEquals(AuthoringPhase.CONFLICT, f.session.state.value.phase)
    } }

    @Test fun resumeDraftRetainsOpenFormAndDoesNotUseSourceCardAsTypedSnapshot() = runTest { fresh { f ->
        f.start(); f.open()
        f.send(AuthoringAction.ChangeCard("Набрано", "Отдельная речь", SpeechChoice.NONE, false))
        val saved = f.await { (it.draft?.form as? EditorForm.Card)?.label == "Набрано" && it.savePhase == DraftSavePhase.SAVED }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val resumed = AuthoringSession(f.runtime.library, f.runtime.media, f.native, f.native, f.native, f.native,
                f.native, IdSource { "resumed" }, scope)
            resumed.send(AuthoringCommand(CommandId("resume"), "other-owner", null, AuthoringAction.ResumeDraft(saved.draft!!.id)))
            val state = withContext(Dispatchers.Default) { withTimeout(10_000) { resumed.state.first { it.phase == AuthoringPhase.EDITING } } }
            assertEquals(saved.draft, state.draft)
            assertEquals(saved.durableVersion, state.durableVersion)
            assertNull(state.draft!!.document.pages[0].cells.firstOrNull()?.card)
        } finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    } }

    @Test fun uniformOneByOneOverflowPreservesEveryCellAndOpaqueMetadata() = runTest { fresh { f ->
        val cells = listOf(BoardCell(0, CellKind.CONTENT, CardDocument(CardId("c"), "X", extensionsJson = "{\"c\":1}")),
            BoardCell(1, CellKind.EMPTY, extensionsJson = "{\"empty\":true}"), BoardCell(2, CellKind.SPACE))
        val doc = BoardDocument(BoardId("resize"), "Сетка", listOf(BoardPage(PageId("page"), 1, 3, cells, "{\"page\":1}")), extensionsJson = "{\"root\":1}")
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, WritePermit(0) { OperationResult.Completed(Unit) }))
        f.send(AuthoringAction.EditBoard(doc.id)); f.saved()
        f.send(AuthoringAction.ResizeGrid(PageId("page"), 1, 1))
        val resized = f.await { it.draft?.document?.pages?.size == 3 && it.savePhase == DraftSavePhase.SAVED }.draft!!.document
        assertTrue(resized.pages.all { it.rows == 1 && it.columns == 1 })
        assertEquals(cells.map { it.copy(position = 0) }, resized.pages.flatMap { it.cells })
        assertEquals(doc.extensionsJson, resized.extensionsJson)
        assertEquals(doc.pages[0].extensionsJson, resized.pages[0].extensionsJson)
        f.send(AuthoringAction.RemovePage(resized.pages[2].id))
        val after = f.await { it.draft?.document != resized && it.savePhase == DraftSavePhase.SAVED }.draft!!.document
        assertEquals(cells.map { it.kind }.sortedBy { it.name }, after.pages.flatMap { it.cells }.map { it.kind }.sortedBy { it.name })
    } }

    @Test fun pageLimitRejectsOverflowWithoutTruncation() = runTest { fresh { f ->
        val pages = (0 until 256).map { i -> BoardPage(PageId("p$i"), 1, 2,
            if (i == 0) listOf(BoardCell(1, CellKind.SPACE)) else emptyList()) }
        val doc = BoardDocument(BoardId("limit"), "Предел", pages)
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, WritePermit(0) { OperationResult.Completed(Unit) }))
        f.send(AuthoringAction.EditBoard(doc.id)); f.saved()
        f.send(AuthoringAction.ResizeGrid(pages[0].id, 1, 1))
        f.await { it.problem?.code == ProblemCode.LIMIT_EXCEEDED }
        assertEquals(doc, f.session.state.value.draft!!.document)
    } }

    @Test fun ownedMediaSurvivesGarbageCollectionAndCancelRemovesOnlyFormReference() = runTest { fresh { f ->
        f.start(); f.open(); f.send(AuthoringAction.PickImage)
        val withImage = f.await { (it.draft?.form as? EditorForm.Card)?.image != null && it.savePhase == DraftSavePhase.SAVED }
        val image = (withImage.draft!!.form as EditorForm.Card).image!!
        assertEquals(0, assertIs<OperationResult.Completed<GarbageCollectionReport>>(f.runtime.media.collectGarbage()).value.removedAssets)
        assertTrue(f.native.discarded.value.size >= 2)
        f.send(AuthoringAction.CancelCard)
        f.await { it.draft?.form == null && it.savePhase == DraftSavePhase.SAVED }
        assertEquals(1, assertIs<OperationResult.Completed<GarbageCollectionReport>>(f.runtime.media.collectGarbage()).value.removedAssets)
        assertIs<OperationResult.Failed>(f.runtime.media.metadata(image))
    } }

    @Test fun lateCancelledPickerCannotOverwriteChangedFormAndOwnTemporaryIsDiscarded() = runTest { fresh { f ->
        f.start(); f.open()
        val pending = CompletableDeferred<OperationResult<TemporaryFile>>()
        f.native.pickResult = pending; f.native.ignorePickCancellation = true
        f.send(AuthoringAction.PickImage); f.await { it.mediaBusy }
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.pickEntered.await() } }
        f.send(AuthoringAction.ChangeCard("Новая форма", "", SpeechChoice.NONE, true))
        f.await { !it.mediaBusy && (it.draft?.form as? EditorForm.Card)?.label == "Новая форма" }
        val file = f.native.temporary()
        pending.complete(OperationResult.Completed(file))
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.discarded.first { file.path in it } } }
        assertNull((f.session.state.value.draft!!.form as EditorForm.Card).image)
    } }

    @Test fun recordFinishSynthesisPreviewAndSourceIoFailureUseExistingPorts() = runTest { fresh { f ->
        f.start(); f.open()
        f.native.recording = CompletableDeferred()
        f.send(AuthoringAction.Record)
        f.await { it.audio.phase == AudioPhase.RECORDING }
        f.send(AuthoringAction.FinishRecording)
        f.await { (it.draft?.form as? EditorForm.Card)?.recording != null && !it.mediaBusy }
        f.send(AuthoringAction.Preview); f.await { it.audio.phase == AudioPhase.PLAYING }
        assertIs<SpeechSegment.Recording>(f.native.played.last().plan.segments.single())
        f.send(AuthoringAction.ChangeCard("Текст", "", SpeechChoice.TTS, true))
        f.await { (it.draft?.form as? EditorForm.Card)?.label == "Текст" }
        val old = (f.session.state.value.draft!!.form as EditorForm.Card).recording
        f.send(AuthoringAction.Synthesize)
        f.await { (it.draft?.form as? EditorForm.Card)?.recording != old && !it.mediaBusy }
        f.native.throwOnSource = true
        f.send(AuthoringAction.PickAudio)
        f.await { it.problem?.code == ProblemCode.IO_FAILURE && !it.mediaBusy }
    } }

    @Test fun sourceCardFormStartsFromActualSourceAndKeepsLanguageAndPassiveFields() = runTest { fresh { f ->
        val card = CardDocument(CardId("original"), "Подпись", speech = SpeechSource.Tts("Речь", "ru-RU"), extensionsJson = "{\"opaque\":42}")
        val doc = BoardDocument(BoardId("original"), "Источник", listOf(BoardPage(PageId("page"), 1, 1,
            listOf(BoardCell(0, CellKind.CONTENT, card, "{\"cell\":42}")))))
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, WritePermit(0) { OperationResult.Completed(Unit) }))
        f.send(AuthoringAction.EditBoard(doc.id)); f.saved(); f.open()
        val form = f.session.state.value.draft!!.form as EditorForm.Card
        assertEquals("Подпись", form.label); assertEquals("Речь", form.speechText)
        assertFalse(form.useLabelForSpeech); assertEquals(card.id, form.cardId)
        f.send(AuthoringAction.ChangeCard("Другая подпись", "Другая речь", SpeechChoice.TTS, false))
        f.await { (it.draft?.form as? EditorForm.Card)?.label == "Другая подпись" }
        f.send(AuthoringAction.SaveCard)
        val changed = f.await { it.draft?.form == null && it.savePhase == DraftSavePhase.SAVED }.draft!!.document.pages[0].cells[0]
        assertEquals(card.id, changed.card!!.id)
        assertEquals(card.extensionsJson, changed.card.extensionsJson)
        assertEquals("{\"cell\":42}", changed.extensionsJson)
        assertEquals(SpeechSource.Tts("Другая речь", "ru-RU"), changed.card.speech)
    } }

    @Test fun inlineGridFirstKeystrokeCreatesOnlyOneSnapshotWithoutResizing() = runTest { fresh { f ->
        f.start()
        val draft = f.session.state.value.draft!!
        f.library.delayed = true
        f.send(AuthoringAction.ChangeGrid("", "3", draft.document.pages[0].id))
        val save = f.nextSave()
        assertEquals(draft.copy(form = EditorForm.Grid(draft.document.pages[0].id, "", "3")), save.draft)
        save.reply.complete(OperationResult.Completed(Unit)); f.saved()
        assertTrue(f.library.saves.tryReceive().isFailure)
        f.send(AuthoringAction.ChangeGrid("2", "4", draft.document.pages[0].id))
        val edited = f.nextSave()
        assertEquals(save.draft.copy(form = EditorForm.Grid(draft.document.pages[0].id, "2", "4")), edited.draft)
        edited.reply.complete(OperationResult.Completed(Unit)); f.saved()
        assertTrue(f.library.saves.tryReceive().isFailure)
    } }

    @Test fun inlineGridValidAndInvalidTextResumeAfterActualSqliteCloseReopen() = runTest {
        for (rows in listOf("2", "", "not a number", "99999999999999999999")) fresh { f ->
            f.start()
            val page = f.session.state.value.draft!!.document.pages[0]
            f.send(AuthoringAction.ChangeGrid(rows, "4", page.id))
            val saved = f.await { it.draft?.form == EditorForm.Grid(page.id, rows, "4") && it.savePhase == DraftSavePhase.SAVED }.draft!!
            assertEquals(page, saved.document.pages[0])
            f.finish(); f.runtime.close()
            val reopened = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(f.root, FileSystem.SYSTEM, BundledSQLiteDriver())).value
            val resumed = Fixture(reopened, Dispatchers.Default, f.root)
            try {
                resumed.send(AuthoringAction.ResumeDraft(saved.id))
                assertEquals(saved, resumed.await { it.phase == AuthoringPhase.EDITING }.draft)
            } finally { resumed.finish(); reopened.close() }
        }
    }

    @Test fun inlineGridDoesNotReplaceAnotherPageOrCardFormAndLegacyNoFormIsNoop() = runTest {
        val dispatcher = QueuedDispatcher()
        fresh(dispatcher) { f ->
            f.start()
            val original = f.session.state.value.draft!!
            f.send(AuthoringAction.ChangeGrid("1", "1"))
            dispatcher.runAll()
            assertEquals(original, f.session.state.value.draft)
            f.send(AuthoringAction.ChangeGrid("1", "1", PageId("missing")))
            f.await { it.problem?.code == ProblemCode.INVALID_DATA }
            assertEquals(original, f.session.state.value.draft)
            f.send(AuthoringAction.AddPage(2, 2))
            val document = f.await { it.draft?.document?.pages?.size == 2 && it.savePhase == DraftSavePhase.SAVED }.draft!!.document
            val page = document.pages[0]
            f.send(AuthoringAction.ChangeGrid("2", "3", page.id))
            val grid = f.await { it.draft?.form is EditorForm.Grid && it.savePhase == DraftSavePhase.SAVED }.draft!!
            f.send(AuthoringAction.ChangeGrid("4", "4", document.pages[1].id))
            f.await { it.problem?.code == ProblemCode.UNFINISHED_FORM }
            assertEquals(grid, f.session.state.value.draft)
            f.send(AuthoringAction.CancelGrid)
            f.await { it.draft?.form == null && it.savePhase == DraftSavePhase.SAVED }
            f.open(); f.saved()
            val card = f.session.state.value.draft!!
            f.send(AuthoringAction.ChangeGrid("5", "5", page.id))
            dispatcher.runAll()
            assertEquals(ProblemCode.UNFINISHED_FORM, f.session.state.value.problem?.code)
            assertEquals(card, f.session.state.value.draft)
        }
    }

    @Test fun inlineGridRejectsUnsafeTextBeforeSavingButAllowsUnfinishedNumericInput() = runTest {
        val dispatcher = QueuedDispatcher()
        fresh(dispatcher) { f ->
            f.start()
            val original = f.session.state.value.draft!!
            val page = original.document.pages[0].id
            for ((text, code) in listOf("\u0000" to ProblemCode.INVALID_DATA, "\uD800" to ProblemCode.INVALID_DATA,
                "1".repeat(65537) to ProblemCode.LIMIT_EXCEEDED)) {
                f.send(AuthoringAction.ChangeGrid(text, "3", page))
                dispatcher.runAll()
                assertEquals(code, f.session.state.value.problem?.code)
                assertEquals(original, f.session.state.value.draft)
            }
            f.send(AuthoringAction.ChangeGrid("-", "3", page))
            f.await { (it.draft?.form as? EditorForm.Grid)?.rowsText == "-" && it.savePhase == DraftSavePhase.SAVED }
            assertEquals(original.document, f.session.state.value.draft!!.document)
        }
    }

    @Test fun inlineGridSaveValidatesBoundsAndOverflowWithoutDroppingForm() = runTest {
        for ((rows, columns, code) in listOf(Triple("0", "1", ProblemCode.LIMIT_EXCEEDED), Triple("65", "1", ProblemCode.LIMIT_EXCEEDED),
            Triple("1", "-1", ProblemCode.LIMIT_EXCEEDED), Triple("2147483648", "1", ProblemCode.INVALID_DATA))) fresh { f ->
            f.start()
            val original = f.session.state.value.draft!!
            f.send(AuthoringAction.ChangeGrid(rows, columns, original.document.pages[0].id))
            val saved = f.await { it.draft?.form is EditorForm.Grid && it.savePhase == DraftSavePhase.SAVED }.draft!!
            f.send(AuthoringAction.SaveGrid)
            f.await { it.problem?.code == code }
            assertEquals(saved, f.session.state.value.draft)
            assertEquals(original.document, saved.document)
        }
    }

    @Test fun inlineGridCancelAndAtomicSavePreserveCellsMetadataAndPublishedDocument() = runTest { fresh { f ->
        val cells = (0..3).map { BoardCell(it, CellKind.CONTENT, CardDocument(CardId("card-$it"), "Карточка $it",
            extensionsJson = "{\"card\":$it}"), "{\"cell\":$it}") }
        val doc = BoardDocument(BoardId("board"), "Источник", listOf(BoardPage(PageId("page"), 2, 2, cells, extensionsJson = "{\"page\":1}")))
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, WritePermit(0) { OperationResult.Completed(Unit) }))
        val card = cells[3].card!!
        val message = StoredMessage(0, 1, MessageDraft(listOf(MessagePart.Card(MessagePartId("selected"),
            CardSelectionSnapshot(SelectionOrigin(doc.id, 1, doc.pages[0].id, card.id), card.label, null, card.speech)))))
        assertIs<OperationResult.Completed<Unit>>(f.runtime.messages.saveMessage(message))
        f.send(AuthoringAction.EditBoard(doc.id)); f.saved()
        f.send(AuthoringAction.ChangeGrid("1", "1", doc.pages[0].id))
        f.await { it.draft?.form is EditorForm.Grid && it.savePhase == DraftSavePhase.SAVED }
        f.send(AuthoringAction.CancelGrid)
        assertEquals(doc, f.await { it.draft?.form == null && it.savePhase == DraftSavePhase.SAVED }.draft!!.document)
        f.send(AuthoringAction.ChangeGrid("1", "1", doc.pages[0].id))
        val before = f.await { it.draft?.form is EditorForm.Grid && it.savePhase == DraftSavePhase.SAVED }.durableVersion!!
        f.library.delayed = true
        f.send(AuthoringAction.SaveGrid)
        val save = f.nextSave()
        assertEquals(before, save.expected)
        assertNull(save.draft.form)
        val changed = save.draft.document
        assertEquals(4, changed.pages.size)
        assertEquals(cells.map { it.copy(position = 0) }, changed.pages.flatMap { it.cells })
        assertEquals(doc.pages[0].extensionsJson, changed.pages[0].extensionsJson)
        val stored = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(save.draft.id)).value
        assertEquals(doc, stored.draft.draft.document); assertIs<EditorForm.Grid>(stored.draft.draft.form); stored.lease.close()
        save.reply.complete(OperationResult.Completed(Unit))
        assertEquals(before + 1, f.await { it.savePhase == DraftSavePhase.SAVED }.durableVersion)
        assertTrue(f.library.saves.tryReceive().isFailure)
        val published = assertIs<OperationResult.Completed<LeasedBoard>>(f.runtime.library.board(doc.id)).value
        assertEquals(doc, published.board.document); published.lease.close()
        assertEquals(message, assertIs<OperationResult.Completed<StoredMessage>>(f.runtime.messages.message()).value)
    } }

    @Test fun inlineGridCannotStartOrSaveWithoutApplicationAuthorization() = runTest { fresh { f ->
        f.start()
        val original = f.session.state.value.draft!!
        val page = original.document.pages[0].id
        f.native.authorized = false
        f.send(AuthoringAction.ChangeGrid("1", "1", page))
        f.await { it.phase == AuthoringPhase.PAUSED && it.problem?.code == ProblemCode.UNAUTHORIZED }
        assertEquals(original, f.session.state.value.draft)
        f.native.authorized = true; f.send(AuthoringAction.Resume)
        f.await { it.phase == AuthoringPhase.EDITING }
        f.send(AuthoringAction.ChangeGrid("1", "1", page))
        val saved = f.await { it.draft?.form is EditorForm.Grid && it.savePhase == DraftSavePhase.SAVED }.draft!!
        f.native.authorized = false; f.send(AuthoringAction.SaveGrid)
        f.await { it.phase == AuthoringPhase.PAUSED }
        assertEquals(saved, f.session.state.value.draft)
    } }

    @Test fun inlineGridBlocksStructuralCommandsAndApplyUntilExplicitCompletion() = runTest {
        val dispatcher = QueuedDispatcher()
        fresh(dispatcher) { f ->
            f.start()
            val page = f.session.state.value.draft!!.document.pages[0]
            f.send(AuthoringAction.ChangeGrid("2", "2", page.id))
            val saved = f.await { it.draft?.form is EditorForm.Grid && it.savePhase == DraftSavePhase.SAVED }.draft!!
            for (action in listOf(AuthoringAction.Apply, AuthoringAction.AddPage(),
                AuthoringAction.ResizeGrid(page.id, 1, 1), AuthoringAction.OpenCard(page.id, 0))) {
                f.send(action); dispatcher.runAll()
                assertEquals(ProblemCode.UNFINISHED_FORM, f.session.state.value.problem?.code)
                assertEquals(saved, f.session.state.value.draft)
                assertNull(f.session.state.value.published)
            }
        }
    }

    @Test fun inlineGridPageOverflowLimitRejectsWholeSaveWithoutTruncation() = runTest { fresh { f ->
        val first = BoardPage(PageId("page-0"), 1, 2, listOf(BoardCell(1, CellKind.SPACE, extensionsJson = "{\"keep\":1}")))
        val pages = listOf(first) + (1..255).map { BoardPage(PageId("page-$it"), 1, 1, emptyList()) }
        val doc = BoardDocument(BoardId("board"), "Источник", pages)
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, WritePermit(0) { OperationResult.Completed(Unit) }))
        f.send(AuthoringAction.EditBoard(doc.id)); f.saved()
        f.send(AuthoringAction.ChangeGrid("1", "1", first.id))
        val saved = f.await { it.draft?.form is EditorForm.Grid && it.savePhase == DraftSavePhase.SAVED }.draft!!
        f.send(AuthoringAction.SaveGrid)
        f.await { it.problem?.code == ProblemCode.LIMIT_EXCEEDED }
        assertEquals(saved, f.session.state.value.draft)
        assertEquals(doc, saved.document)
    } }

    @Test fun inlineGridSaveStillChecksApplicationPermitAtDurableBoundary() = runTest { fresh { f ->
        f.start()
        val page = f.session.state.value.draft!!.document.pages[0]
        f.send(AuthoringAction.ChangeGrid("1", "1", page.id))
        val saved = f.await { it.draft?.form is EditorForm.Grid && it.savePhase == DraftSavePhase.SAVED }.draft!!
        f.library.delayed = true
        f.send(AuthoringAction.SaveGrid)
        val save = f.nextSave()
        f.native.authorized = false
        save.reply.complete(OperationResult.Completed(Unit))
        f.await { it.phase == AuthoringPhase.PAUSED && it.savePhase == DraftSavePhase.FAILED }
        val retained = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(saved.id)).value
        assertEquals(saved, retained.draft.draft); retained.lease.close()
    } }

    @Test fun gridFormIsDurableAndValidationDoesNotDropUnappliedText() = runTest { fresh { f ->
        f.start()
        val page = f.session.state.value.draft!!.document.pages[0]
        f.send(AuthoringAction.OpenGrid(page.id))
        f.await { it.draft?.form is EditorForm.Grid }
        f.send(AuthoringAction.ChangeGrid("not a number", "1"))
        f.await { (it.draft?.form as? EditorForm.Grid)?.rowsText == "not a number" && it.savePhase == DraftSavePhase.SAVED }
        f.send(AuthoringAction.SaveGrid)
        f.await { it.problem?.code == ProblemCode.INVALID_DATA }
        assertEquals(page, f.session.state.value.draft!!.document.pages[0])
        assertEquals("not a number", (f.session.state.value.draft!!.form as EditorForm.Grid).rowsText)
        f.send(AuthoringAction.ChangeGrid("1", "1"))
        f.await { (it.draft?.form as? EditorForm.Grid)?.rowsText == "1" }
        f.send(AuthoringAction.SaveGrid)
        f.await { it.draft?.form == null && it.draft?.document?.pages?.get(0)?.capacity == 1 && it.savePhase == DraftSavePhase.SAVED }
    } }

    @Test fun discardIsExplicitWhileDeletingRemovesOnlyNamedDraft() = runTest { fresh { f ->
        f.start()
        val draft = f.session.state.value.draft!!
        f.send(AuthoringAction.DeleteDraft)
        f.await { it.phase == AuthoringPhase.CLOSED }
        assertEquals(ProblemCode.NOT_FOUND, assertIs<OperationResult.Failed>(f.runtime.library.draft(draft.id)).problem.code)
    } }

    @Test fun forceDiscardDropsFailedLocalEditsButKeepsLastDurableDraft() = runTest { fresh { f ->
        f.start()
        val durable = f.session.state.value.draft!!
        f.library.delayed = true; f.details("Не сохранять")
        f.nextSave().reply.complete(OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)))
        f.await { it.savePhase == DraftSavePhase.FAILED }
        f.send(AuthoringAction.ForceDiscard); f.await { it.phase == AuthoringPhase.CLOSED }
        val stored = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(durable.id)).value
        assertEquals(durable, stored.draft.draft); stored.lease.close()
    } }

    @Test fun unconfirmedPublishCannotBeRepeatedAndKeepsDraftForRecovery() = runTest { fresh { f ->
        f.start(); val draft = f.session.state.value.draft!!
        f.library.publishResult = OperationResult.UnconfirmedCommit
        f.send(AuthoringAction.Apply); f.await { it.phase == AuthoringPhase.UNCONFIRMED }
        assertEquals(DraftSavePhase.UNCONFIRMED, f.session.state.value.savePhase)
        val retained = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(draft.id)).value
        assertEquals(draft, retained.draft.draft); retained.lease.close()
    } }

    @Test fun revokeCancelsRecordingAndForeignOwnerCannotInvalidateSession() = runTest { fresh { f ->
        f.start(); f.open(); f.native.recording = CompletableDeferred()
        f.send(AuthoringAction.Record); f.await { it.audio.phase == AudioPhase.RECORDING }
        assertFalse(f.session.send(AuthoringCommand(CommandId("foreign"), "foreign", null, AuthoringAction.Revoke)))
        f.send(AuthoringAction.Revoke); f.await { it.phase == AuthoringPhase.PAUSED && !it.mediaBusy }
        assertNull((f.session.state.value.draft!!.form as EditorForm.Card).recording)
        assertEquals(AudioPhase.IDLE, f.session.state.value.audio.phase)
    } }

    @Test fun boundsRejectMediaAndKeepExistingFormUnchanged() = runTest { fresh { f ->
        f.start(); f.open()
        f.native.imageSize = ImageInfo(8193, 1, "image/png")
        val form = f.session.state.value.draft!!.form
        f.send(AuthoringAction.PickImage)
        f.await { it.problem?.code == ProblemCode.LIMIT_EXCEEDED && !it.mediaBusy }
        assertEquals(form, f.session.state.value.draft!!.form)
        f.native.audioDuration = 600001
        f.send(AuthoringAction.PickAudio)
        f.await { it.problem?.code == ProblemCode.LIMIT_EXCEEDED && !it.mediaBusy }
        assertEquals(form, f.session.state.value.draft!!.form)
    } }

    @Test fun backgroundBetweenMediaAuthorizationAndWorkerRegistrationCannotStartRecording() = runTest { fresh { f ->
        f.start(); f.open(); f.saved()
        f.native.recording = CompletableDeferred()
        f.native.beforeGuard = { f.send(AuthoringAction.Background) }
        f.send(AuthoringAction.Record)
        f.await { it.phase == AuthoringPhase.PAUSED }
        f.finish()
        assertEquals(0, f.native.recordCalls.value)
    } }

    @Test fun deletedSourceBoardCannotBeResurrectedByApply() = runTest { fresh { f ->
        val doc = BoardDocument(BoardId("deleted"), "Удалённая", listOf(BoardPage(PageId("page"), 1, 1, emptyList())))
        val permit = WritePermit(0) { OperationResult.Completed(Unit) }
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, permit))
        f.send(AuthoringAction.EditBoard(doc.id)); f.saved()
        val draft = f.session.state.value.draft!!
        assertIs<OperationResult.Completed<Unit>>(f.runtime.library.deleteBoard(doc.id, 1, permit))
        f.send(AuthoringAction.Apply); f.await { it.phase == AuthoringPhase.CONFLICT }
        assertEquals(ProblemCode.CONFLICT, f.session.state.value.problem?.code)
        assertTrue(f.runtime.library.listing.value.items.isEmpty())
        val retained = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(draft.id)).value
        assertEquals(draft, retained.draft.draft); retained.lease.close()
    } }

    @Test fun pageReorderingAndCrossPageCellSwapPreserveIdentitiesAndEmptyMetadata() = runTest { fresh { f ->
        val card = CardDocument(CardId("move"), "Переместить", extensionsJson = "{\"card\":1}")
        val a = BoardPage(PageId("a"), 1, 2, listOf(BoardCell(0, CellKind.CONTENT, card)))
        val b = BoardPage(PageId("b"), 1, 2, listOf(BoardCell(1, CellKind.EMPTY, extensionsJson = "{\"empty\":1}")))
        val doc = BoardDocument(BoardId("move"), "Порядок", listOf(a, b))
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, WritePermit(0) { OperationResult.Completed(Unit) }))
        f.send(AuthoringAction.EditBoard(doc.id)); f.saved()
        f.send(AuthoringAction.MovePage(b.id, 0))
        f.await { it.draft?.document?.pages?.first()?.id == b.id }
        f.send(AuthoringAction.MoveCell(a.id, 0, b.id, 1))
        val swapped = f.await { it.draft?.document?.pages?.first()?.cells?.firstOrNull()?.card?.id == card.id && it.savePhase == DraftSavePhase.SAVED }.draft!!.document
        assertEquals(card, swapped.pages[0].cells.single().card)
        assertEquals("{\"empty\":1}", swapped.pages[1].cells.single().extensionsJson)
        f.send(AuthoringAction.SetCell(a.id, 0, CellKind.SPACE))
        f.await { it.draft?.document?.pages?.get(1)?.cells?.single()?.kind == CellKind.SPACE && it.savePhase == DraftSavePhase.SAVED }
        assertEquals("{\"empty\":1}", f.session.state.value.draft!!.document.pages[1].cells.single().extensionsJson)
    } }

    @Test fun authorizationFailurePermanentlyInvalidatesOldPermitEvenIfGateLaterOpens() = runTest { fresh { f ->
        f.start(); f.open(); f.saved()
        f.library.delayed = true
        f.send(AuthoringAction.ChangeCard("Новый текст", "", SpeechChoice.TTS, true))
        val old = f.nextSave()
        f.native.authorized = false
        f.send(AuthoringAction.Preview)
        f.await { it.phase == AuthoringPhase.PAUSED && it.problem?.code == ProblemCode.UNAUTHORIZED }
        f.native.authorized = true
        assertEquals(ProblemCode.UNAUTHORIZED, assertIs<OperationResult.Failed>(old.permit.check()).problem.code)
        old.reply.complete(OperationResult.Completed(Unit))
        f.await { it.phase == AuthoringPhase.PAUSED && it.savePhase == DraftSavePhase.FAILED }
        f.send(AuthoringAction.Resume)
        f.nextSave().reply.complete(OperationResult.Completed(Unit))
        f.saved()
    } }

    @Test fun resumedLatestSnapshotSurvivesLateUnauthorizedCompletionFromOldGeneration() = runTest { fresh { f ->
        f.start(); f.library.delayed = true
        f.details("Сохранить после возобновления")
        val old = f.nextSave()
        f.send(AuthoringAction.Background); f.await { it.phase == AuthoringPhase.PAUSED }
        f.send(AuthoringAction.Resume); f.await { it.phase == AuthoringPhase.EDITING }
        old.reply.complete(OperationResult.Completed(Unit))
        val fresh = f.nextSave()
        assertEquals(old.draft, fresh.draft)
        assertEquals(old.expected, fresh.expected)
        assertIs<OperationResult.Completed<Unit>>(fresh.permit.check())
        fresh.reply.complete(OperationResult.Completed(Unit))
        assertEquals(AuthoringPhase.EDITING, f.saved().phase)
    } }

    @Test fun ownerCancellationDrainsBufferedLoadedLeaseBeforeActorCanAdoptIt() = runTest {
        val dispatcher = QueuedDispatcher()
        fresh(dispatcher) { f ->
            dispatcher.runAll()
            val sessionJob = f.scope.coroutineContext[Job]!!.children.single()
            val baseline = sessionJob.children.toSet()
            val doc = BoardDocument(BoardId("queued"), "Очередь", listOf(BoardPage(PageId("page"), 1, 1, emptyList())))
            assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, WritePermit(0) { OperationResult.Completed(Unit) }))
            val gate = CompletableDeferred<Unit>()
            f.library.readGate = gate
            f.send(AuthoringAction.EditBoard(doc.id))
            dispatcher.until { f.library.readEntered.isCompleted }
            val worker = (sessionJob.children.toSet() - baseline).single()
            // Resume the waiting actor with a command but do not execute that dispatcher task.
            // The completed load below is therefore buffered, not adopted by the actor.
            f.send(AuthoringAction.RetrySave)
            gate.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) { worker.join() } }
            assertEquals(1, f.leases.active.value)
            f.finish()
            assertEquals(0, f.leases.active.value)
            assertEquals(1, f.leases.closes.value)
        }
    }

    @Test fun ownerCancellationDrainsBufferedPreparedMediaAndReleasesGcPin() = runTest {
        val dispatcher = QueuedDispatcher()
        fresh(dispatcher) { f ->
            dispatcher.runAll()
            f.send(AuthoringAction.NewBoard())
            dispatcher.until { f.session.state.value.savePhase == DraftSavePhase.SAVED }
            val page = f.session.state.value.draft!!.document.pages[0].id
            f.send(AuthoringAction.OpenCard(page, 0))
            dispatcher.until { f.session.state.value.draft?.form is EditorForm.Card && f.session.state.value.savePhase == DraftSavePhase.SAVED }
            val sessionJob = f.scope.coroutineContext[Job]!!.children.single()
            val baseline = sessionJob.children.toSet()
            val gate = CompletableDeferred<Unit>()
            f.media.prepareGate = gate
            f.send(AuthoringAction.PickImage)
            dispatcher.until { f.media.prepared.isCompleted }
            val worker = (sessionJob.children.toSet() - baseline).single()
            f.send(AuthoringAction.RetrySave)
            gate.complete(Unit)
            withContext(Dispatchers.Default) { withTimeout(10_000) { worker.join() } }
            assertEquals(1, f.leases.active.value)
            f.finish()
            assertEquals(0, f.leases.active.value)
            assertEquals(1, f.leases.closes.value)
            assertEquals(1, assertIs<OperationResult.Completed<GarbageCollectionReport>>(f.runtime.media.collectGarbage()).value.removedAssets)
        }
    }

    @Test fun deliveredLoadedLeaseIsAdoptedUntilOwnerExitAndNeverClosedTwice() = runTest { fresh { f ->
        val doc = BoardDocument(BoardId("adopted"), "Доставлено", listOf(BoardPage(PageId("page"), 1, 1, emptyList())))
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(doc, WritePermit(0) { OperationResult.Completed(Unit) }))
        f.send(AuthoringAction.EditBoard(doc.id)); f.saved()
        assertEquals(1, f.leases.active.value)
        f.finish()
        assertEquals(0, f.leases.active.value)
        assertNull(f.session.state.value.cleanupProblem)
    } }

    @Test fun throwingSaveLeaseCleanupStillWakesNextSaveAndAllowsClose() = runTest { fresh { f ->
        f.start(); f.open(); f.send(AuthoringAction.PickImage)
        f.await { (it.draft?.form as? EditorForm.Card)?.image != null && it.savePhase == DraftSavePhase.SAVED }
        f.leases.failClose.value = true
        f.library.delayed = true
        f.send(AuthoringAction.ChangeCard("Первый", "", SpeechChoice.NONE, true))
        val first = f.nextSave()
        f.send(AuthoringAction.ChangeCard("Последний", "", SpeechChoice.NONE, true))
        f.await { (it.draft?.form as? EditorForm.Card)?.label == "Последний" }
        first.reply.complete(OperationResult.Completed(Unit))
        val latest = f.nextSave()
        latest.reply.complete(OperationResult.Completed(Unit))
        val saved = f.await { it.savePhase == DraftSavePhase.SAVED && (it.draft?.form as? EditorForm.Card)?.label == "Последний" }
        assertEquals(ProblemCode.IO_FAILURE, saved.cleanupProblem?.code)
        assertNull(saved.problem)
        f.native.throwOnStop = true
        f.send(AuthoringAction.Close)
        f.await { it.phase == AuthoringPhase.CLOSED }
        f.finish()
        assertEquals(0, f.leases.active.value)
        assertTrue(f.native.stopCalls.value > 0)
    } }

    @Test fun ownerCleanupAttemptsLivePendingAndActivePinsEvenWhenAllClosesAndStopThrow() = runTest { fresh { f ->
        f.start(); f.open(); f.send(AuthoringAction.PickImage)
        f.await { (it.draft?.form as? EditorForm.Card)?.image != null && it.savePhase == DraftSavePhase.SAVED }
        f.library.delayed = true
        f.send(AuthoringAction.ChangeCard("Активный", "", SpeechChoice.NONE, true))
        f.nextSave()
        f.send(AuthoringAction.ChangeCard("Pending", "", SpeechChoice.NONE, true))
        f.await { (it.draft?.form as? EditorForm.Card)?.label == "Pending" }
        assertTrue(f.leases.active.value >= 3)
        f.leases.failClose.value = true
        f.native.throwOnStop = true
        val stops = f.native.stopCalls.value
        f.finish()
        assertEquals(0, f.leases.active.value)
        assertTrue(f.native.stopCalls.value > stops)
        assertEquals(ProblemCode.IO_FAILURE, f.session.state.value.cleanupProblem?.code)
    } }

    @Test fun discardCleanupFailurePreservesPrimaryErrorAndBothImageHandlesAreAttempted() = runTest { fresh { f ->
        f.start(); f.open()
        f.native.throwOnDiscard = true
        f.native.imageSize = ImageInfo(8193, 1, "image/png")
        f.send(AuthoringAction.PickImage)
        val failed = f.await { !it.mediaBusy && it.problem?.code == ProblemCode.LIMIT_EXCEEDED }
        assertEquals(ProblemCode.IO_FAILURE, failed.cleanupProblem?.code)
        assertNull((failed.draft!!.form as EditorForm.Card).image)
        val discarded = f.native.discarded.value.size
        f.native.imageSize = ImageInfo(100, 100, "image/png")
        f.send(AuthoringAction.PickImage)
        f.await { (it.draft?.form as? EditorForm.Card)?.image != null && it.savePhase == DraftSavePhase.SAVED }
        assertEquals(discarded + 2, f.native.discarded.value.size)
        f.finish()
        assertEquals(0, f.leases.active.value)
    } }

    @Test fun stopFailureCannotPreventMediaCancellationOrStrandPausedActor() = runTest { fresh { f ->
        f.start(); f.open(); f.native.recording = CompletableDeferred()
        f.send(AuthoringAction.Record); f.await { it.audio.phase == AudioPhase.RECORDING }
        f.native.throwOnStop = true
        f.send(AuthoringAction.Background)
        f.await { it.phase == AuthoringPhase.PAUSED && !it.mediaBusy }
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.recordingCancelled.await() } }
        assertEquals(ProblemCode.IO_FAILURE, f.session.state.value.cleanupProblem?.code)
        f.native.throwOnStop = false
        f.send(AuthoringAction.Resume); f.await { it.phase == AuthoringPhase.EDITING }
        f.send(AuthoringAction.CancelCard)
        f.await { it.draft?.form == null && it.savePhase == DraftSavePhase.SAVED }
    } }

    @Test fun unknownCommitIsNotReplacedByCleanupFailure() = runTest { fresh { f ->
        f.start(); f.open(); f.send(AuthoringAction.PickImage)
        f.await { (it.draft?.form as? EditorForm.Card)?.image != null && it.savePhase == DraftSavePhase.SAVED }
        f.library.delayed = true
        f.send(AuthoringAction.ChangeCard("Неизвестно", "", SpeechChoice.NONE, true))
        val save = f.nextSave()
        f.leases.failClose.value = true
        save.reply.complete(OperationResult.UnconfirmedCommit)
        val frozen = f.await { it.phase == AuthoringPhase.UNCONFIRMED }
        assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN, frozen.problem?.code)
        assertEquals(ProblemCode.IO_FAILURE, frozen.cleanupProblem?.code)
        f.finish()
        assertEquals(0, f.leases.active.value)
    } }

    @Test fun dedupRetainsRecentCommandsButEvictsIdsBeyond1024UserIntents() = runTest { fresh { f ->
        f.start()
        val original = AuthoringCommand(CommandId("old-command"), "owner", null,
            AuthoringAction.Details("Исходное", null, BoardBehavior()))
        assertTrue(f.session.send(original))
        f.await { it.draft?.document?.name == "Исходное" }
        f.details("Текущее"); f.await { it.draft?.document?.name == "Текущее" }
        assertTrue(f.session.send(original))
        // Sentinel command proves the preceding duplicate has been processed without replacing content.
        f.send(AuthoringAction.OpenGrid(f.session.state.value.draft!!.document.pages[0].id))
        assertEquals("Текущее", f.await { it.draft?.form is EditorForm.Grid }.draft!!.document.name)
        f.send(AuthoringAction.CancelGrid)
        repeat(1025) { f.details("Команда $it") }
        f.await { it.draft?.document?.name == "Команда 1024" }
        assertTrue(f.session.send(original))
        f.await { it.draft?.document?.name == "Исходное" }
        f.details("После повтора"); f.await { it.draft?.document?.name == "После повтора" }
        assertTrue(f.session.send(original))
        f.send(AuthoringAction.OpenGrid(f.session.state.value.draft!!.document.pages[0].id))
        assertEquals("После повтора", f.await { it.draft?.form is EditorForm.Grid }.draft!!.document.name)
    } }

    private suspend fun prepareUnauthorizedPickerResult(f: Fixture): AssistantCapability {
        val old = AssistantCapability("old", "owner")
        val fresh = AssistantCapability("fresh", "owner")
        f.capability = old
        f.native.requireCapability = true
        f.native.acceptedCapability = old
        f.start(); f.open(); f.saved()
        val result = CompletableDeferred<OperationResult<TemporaryFile>>()
        f.native.pickResult = result
        f.send(AuthoringAction.PickImage)
        f.await { it.mediaBusy }
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.pickEntered.await() } }
        f.native.acceptedCapability = fresh // Core gate invalidation, NOT real Background/Revoke.
        result.complete(OperationResult.Completed(f.native.temporary()))
        f.await { it.phase == AuthoringPhase.PAUSED && it.problem?.code == ProblemCode.UNAUTHORIZED }
        return fresh
    }

    @Test fun pickerGateInvalidationWaitsForNewCapabilityAndExplicitResumeBeforePublishingAssetRefs() = runTest { fresh { f ->
        val fresh = prepareUnauthorizedPickerResult(f)
        val before = f.session.state.value
        assertNull((before.draft!!.form as EditorForm.Card).image)
        val stored = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(before.draft.id)).value
        assertTrue(stored.draft.draft.assetIds().isEmpty()); stored.lease.close()
        assertEquals(1, f.leases.active.value)
        assertEquals(0, assertIs<OperationResult.Completed<GarbageCollectionReport>>(f.runtime.media.collectGarbage()).value.removedAssets)
        // Reopening access alone does not replay the result; the old capability still cannot resume.
        f.send(AuthoringAction.Resume)
        f.await { it.phase == AuthoringPhase.PAUSED && it.problem?.code == ProblemCode.UNAUTHORIZED }
        assertNull((f.session.state.value.draft!!.form as EditorForm.Card).image)
        f.capability = fresh
        f.send(AuthoringAction.Resume)
        val adopted = f.await { it.phase == AuthoringPhase.EDITING && (it.draft?.form as? EditorForm.Card)?.image != null &&
            it.savePhase == DraftSavePhase.SAVED }
        val durable = assertIs<OperationResult.Completed<LeasedDraft>>(f.runtime.library.draft(before.draft.id)).value
        assertEquals(adopted.draft!!.assetIds(), durable.draft.draft.assetIds()); durable.lease.close()
        f.send(AuthoringAction.Resume)
        f.send(AuthoringAction.Background); f.await { it.phase == AuthoringPhase.PAUSED }
        assertEquals(adopted.durableVersion, f.session.state.value.durableVersion)
        f.finish()
        assertEquals(0, f.leases.active.value)
    } }

    @Test fun realBackgroundDuringReauthenticationDiscardsPreparedPinAndResumeCannotAdoptIt() = runTest { fresh { f ->
        val fresh = prepareUnauthorizedPickerResult(f)
        assertEquals(1, f.leases.active.value)
        val stops = f.native.stopCalls.value
        f.send(AuthoringAction.Background)
        // An unauthorized command is an actor barrier after Background, without granting access.
        f.send(AuthoringAction.CancelMedia)
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.leases.active.first { it == 0 } } }
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.stopCalls.first { it >= stops + 2 } } }
        assertEquals(1, assertIs<OperationResult.Completed<GarbageCollectionReport>>(f.runtime.media.collectGarbage()).value.removedAssets)
        f.capability = fresh
        f.send(AuthoringAction.Resume); f.await { it.phase == AuthoringPhase.EDITING }
        assertNull((f.session.state.value.draft!!.form as EditorForm.Card).image)
    } }

    @Test fun closeDuringReauthenticationReleasesOnlyPendingOwnedAsset() = runTest { fresh { f ->
        f.capability = prepareUnauthorizedPickerResult(f)
        assertEquals(1, f.leases.active.value)
        f.send(AuthoringAction.Close); f.await { it.phase == AuthoringPhase.CLOSED }
        assertEquals(0, f.leases.active.value)
        assertEquals(1, assertIs<OperationResult.Completed<GarbageCollectionReport>>(f.runtime.media.collectGarbage()).value.removedAssets)
    } }

    @Test fun completedRecordingAfterPermissionGateInvalidationAdoptsWithoutRestartingRecording() = runTest { fresh { f ->
        val old = AssistantCapability("old-mic", "owner")
        val fresh = AssistantCapability("fresh-mic", "owner")
        f.native.requireCapability = true; f.native.acceptedCapability = old; f.capability = old
        f.start(); f.open(); f.saved()
        f.native.recording = CompletableDeferred()
        f.send(AuthoringAction.Record); f.await { it.audio.phase == AudioPhase.RECORDING }
        f.native.acceptedCapability = fresh
        f.native.recording!!.complete(OperationResult.Completed(f.native.temporary()))
        f.await { it.phase == AuthoringPhase.PAUSED }
        assertNull((f.session.state.value.draft!!.form as EditorForm.Card).recording)
        assertEquals(1, f.leases.active.value)
        f.capability = fresh; f.send(AuthoringAction.Resume)
        f.await { it.phase == AuthoringPhase.EDITING && (it.draft?.form as? EditorForm.Card)?.recording != null &&
            it.savePhase == DraftSavePhase.SAVED }
        assertEquals(1, f.native.recordCalls.value)
        f.finish(); assertEquals(0, f.leases.active.value)
    } }

    @Test fun ownerCancellationDuringReauthenticationReleasesPendingAssetEvenIfCloseThrows() = runTest { fresh { f ->
        prepareUnauthorizedPickerResult(f)
        assertEquals(1, f.leases.active.value)
        f.leases.failClose.value = true
        f.finish()
        assertEquals(0, f.leases.active.value)
        assertEquals(ProblemCode.IO_FAILURE, f.session.state.value.cleanupProblem?.code)
        assertEquals(ProblemCode.UNAUTHORIZED, f.session.state.value.problem?.code)
        assertEquals(1, assertIs<OperationResult.Completed<GarbageCollectionReport>>(f.runtime.media.collectGarbage()).value.removedAssets)
    } }

    @Test fun changedLibraryEpochRejectsPendingAdoptionAndReleasesItsPin() = runTest { fresh { f ->
        f.capability = prepareUnauthorizedPickerResult(f)
        val prior = f.library.listing.value
        f.library.listingOverride = MutableStateFlow(prior.copy(epoch = prior.epoch + 1))
        f.send(AuthoringAction.Resume)
        f.await { it.phase == AuthoringPhase.CONFLICT && it.problem?.code == ProblemCode.STALE_REQUEST }
        assertEquals(0, f.leases.active.value)
        assertNull((f.session.state.value.draft!!.form as EditorForm.Card).image)
    } }

    @Test fun realBackgroundSentAfterQueuedResumeWinsBeforePendingResultCanBeAdopted() = runTest {
        val dispatcher = QueuedDispatcher()
        fresh(dispatcher) { f ->
            f.capability = prepareUnauthorizedPickerResult(f)
            val version = f.session.state.value.durableVersion
            val stops = f.native.stopCalls.value
            f.send(AuthoringAction.Resume) // Deliberately leave the actor's continuation queued.
            f.send(AuthoringAction.Background)
            dispatcher.until { f.leases.active.value == 0 && f.native.stopCalls.value >= stops + 2 &&
                f.session.state.value.phase == AuthoringPhase.PAUSED }
            assertNull((f.session.state.value.draft!!.form as EditorForm.Card).image)
            assertEquals(version, f.session.state.value.durableVersion)
            assertEquals(1, assertIs<OperationResult.Completed<GarbageCollectionReport>>(f.runtime.media.collectGarbage()).value.removedAssets)
        }
    }
}
