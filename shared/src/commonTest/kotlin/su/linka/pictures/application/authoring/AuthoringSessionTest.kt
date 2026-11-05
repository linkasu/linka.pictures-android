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

class AuthoringSessionTest {
    private class ControlledLibrary(private val real: LibraryStore) : LibraryStore by real {
        data class Save(val draft: EditorDraft, val expected: Long?, val permit: WritePermit,
            val reply: CompletableDeferred<OperationResult<Unit>> = CompletableDeferred())
        val saves = Channel<Save>(Channel.UNLIMITED)
        var delayed = false
        var publishResult: OperationResult<PublishedBoard>? = null
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

    private class Fixture(val runtime: StorageRuntime) {
        val native = AuthoringNativeFakes()
        val library = ControlledLibrary(runtime.library)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val serial = MutableStateFlow(0)
        private fun id() = "authoring-${serial.updateAndGet { it + 1 }}"
        val session = AuthoringSession(library, runtime.media, native, native, native, native, native, IdSource { id() }, scope)
        fun send(action: AuthoringAction) { assertTrue(session.send(AuthoringCommand(CommandId(id()), "owner", null, action))) }
        suspend fun await(predicate: (AuthoringState) -> Boolean): AuthoringState = withContext(Dispatchers.Default) {
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
        suspend fun finish() { scope.cancel(); scope.coroutineContext[Job]!!.join() }
    }

    private suspend fun fresh(block: suspend (Fixture) -> Unit) {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "p11-authoring-${kotlin.random.Random.nextLong()}"
        fs.createDirectory(root, mustCreate = true)
        val runtime = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(root, fs, BundledSQLiteDriver())).value
        val fixture = Fixture(runtime)
        try { block(fixture) } finally { fixture.finish(); runtime.close(); fs.deleteRecursively(root) }
    }

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
}
