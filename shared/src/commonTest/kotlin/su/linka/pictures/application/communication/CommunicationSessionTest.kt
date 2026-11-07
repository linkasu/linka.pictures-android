package su.linka.pictures.application.communication

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import kotlin.test.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommunicationSessionTest {
    private class Fixture(val test: TestScope) {
        val media = FakeMedia()
        val messages = FakeMessages()
        val library = FakeLibrary(media)
        val audio = FakeAudio()
        private var serial = 0
        fun id() = CommandId("command-${serial++}")
        lateinit var session: CommunicationSession
        init {
            media.roots = { messages.stored.draft.assetIds() + library.boards.values.flatMap { it.document.assetIds() } }
        }
        fun start(scope: CoroutineScope = test.backgroundScope) {
            session = CommunicationSession(library, messages, media, audio, IdSource { "id-${serial++}" }, scope)
            test.runCurrent()
        }
        fun send(command: CommunicationCommand) {
            session.dispatch(command)
            test.runCurrent()
        }
        fun board(name: String, label: String, style: InputStyle = InputStyle.CARDS, speech: SpeechSource = SpeechSource.Tts(label), mode: InteractionMode = InteractionMode.COMPOSE): PublishedBoard {
            val result = PublishedBoard(BoardDocument(BoardId(name), name, listOf(BoardPage(PageId(name), 1, 3, listOf(
                BoardCell(0, CellKind.CONTENT, CardDocument(CardId("card-$name"), label, speech = speech)),
                BoardCell(1, CellKind.SPACE), BoardCell(2, CellKind.EMPTY),
            ))), BoardBehavior(mode, style)), 1)
            library.boards[result.document.id] = result
            media.existing += result.document.assetIds()
            return result
        }
        fun open(name: String) = send(CommunicationCommand.OpenBoard(id(), BoardId(name)))
        fun select(name: String, position: Int = 0, command: CommandId = id()) = send(CommunicationCommand.Select(command, PageId(name), position))
        fun text(text: String) = send(CommunicationCommand.InsertText(id(), text))
        fun close() {
            session.close()
            test.runCurrent()
        }
    }

    @Test fun crossBoardSnapshotAndConsciousRepeatAreIndependentOfSources() = runTest {
        val f = Fixture(this)
        f.board("want", "Хочу")
        f.board("food", "кашу")
        f.start()
        f.open("want")
        val delivery = f.id()
        f.select("want", command = delivery)
        f.select("want", command = delivery)
        assertEquals("Хочу", f.session.state.value.renderedText)
        f.open("food")
        f.select("food")
        assertEquals("Хочу кашу", f.session.state.value.renderedText)
        val first = assertIs<MessagePart.Card>(f.session.state.value.message.parts.first())
        f.library.boards[BoardId("want")] = f.library.boards.getValue(BoardId("want")).let { old ->
            old.copy(revision = 2, document = old.document.copy(pages = old.document.pages.map { page ->
                page.copy(cells = page.cells.map { cell -> if (cell.card == null) cell else cell.copy(card = cell.card.copy(label = "Изменено")) })
            }))
        }
        f.open("want")
        assertEquals(first, f.session.state.value.message.parts.first())
        f.library.boards.clear()
        f.send(CommunicationCommand.Speak(f.id()))
        assertEquals("Хочу кашу", f.audio.played.single().plan.renderedText)
        f.close()
    }

    @Test fun literalRunsSurviveNavigationAndGraphemeBackspace() = runTest {
        val f = Fixture(this)
        f.board("want", "Хочу")
        f.board("keyboard", "м", InputStyle.LITERAL)
        f.start()
        f.open("want"); f.select("want")
        f.open("keyboard"); f.select("keyboard")
        f.text("ё")
        f.open("want"); f.text("д")
        assertEquals("Хочу мёд", f.session.state.value.renderedText)
        assertEquals(2, f.session.state.value.message.parts.size)
        for (grapheme in listOf("е\u0301", "👩🏽‍💻", "🇷🇺", "क्ष")) {
            f.text(grapheme)
            f.send(CommunicationCommand.Backspace(f.id()))
            assertEquals("Хочу мёд", f.session.state.value.renderedText)
        }
        f.send(CommunicationCommand.Backspace(f.id()))
        assertEquals("Хочу мё", f.session.state.value.renderedText)
        f.close()
    }

    @Test fun directKeepsDraftAndLatestRequestWinsWithNoHiddenClear() = runTest {
        val f = Fixture(this)
        f.board("want", "Хочу")
        f.start(); f.open("want"); f.select("want"); f.select("want")
        assertEquals("Хочу Хочу", f.session.state.value.renderedText)
        f.send(CommunicationCommand.SetMode(f.id(), InteractionMode.DIRECT))
        val draft = f.session.state.value.message.parts
        f.select("want")
        val old = f.audio.played.last()
        f.select("want")
        val latest = f.audio.played.last()
        f.audio.callback(old, AudioPhase.COMPLETED); runCurrent()
        assertEquals(latest.id, f.session.state.value.audio.requestId)
        f.send(CommunicationCommand.Backspace(f.id()))
        f.send(CommunicationCommand.Clear(f.id()))
        f.text("не вставлять")
        f.send(CommunicationCommand.Speak(f.id()))
        assertEquals(draft, f.session.state.value.message.parts)
        f.send(CommunicationCommand.SetMode(f.id(), InteractionMode.COMPOSE))
        assertEquals("Хочу Хочу", f.session.state.value.renderedText)
        f.close()
    }

    @Test fun navigationContinuesActualPlaybackButMutationsAndExitStop() = runTest {
        val f = Fixture(this)
        f.board("a", "Хочу"); f.board("b", "кашу")
        f.start(); f.open("a"); f.select("a")
        f.send(CommunicationCommand.Speak(f.id()))
        val playback = f.audio.played.single()
        val stopCount = f.audio.stopped.size
        f.open("b")
        f.send(CommunicationCommand.ChangePage(f.id(), PageId("b")))
        assertEquals(stopCount, f.audio.stopped.size)
        assertEquals(playback.id, f.session.state.value.audio.requestId)
        assertEquals("Хочу", playback.plan.renderedText)
        f.select("b")
        assertEquals(AudioPhase.IDLE, f.session.state.value.audio.phase)
        f.send(CommunicationCommand.Speak(f.id()))
        f.session.interrupt(); runCurrent()
        assertEquals("Хочу кашу", f.session.state.value.renderedText)
        f.audio.callback(playback, AudioPhase.PLAYING); runCurrent()
        assertEquals(AudioPhase.IDLE, f.session.state.value.audio.phase)
        f.send(CommunicationCommand.Speak(f.id()))
        f.send(CommunicationCommand.CloseBoard(f.id()))
        assertNull(f.session.state.value.board)
        assertEquals(AudioPhase.IDLE, f.session.state.value.audio.phase)
        assertEquals("Хочу кашу", f.session.state.value.renderedText)
        f.close()
    }

    @Test fun delayedSaveNeverBlocksUiAndOldSuccessCannotHideLatestFailure() = runTest {
        val f = Fixture(this)
        f.start()
        f.messages.delayed = true
        f.text("Хочу")
        val first = f.messages.pending.last()
        f.text(" кашу")
        assertEquals("Хочу кашу", f.session.state.value.renderedText)
        assertEquals(MessageSavePhase.SAVING, f.session.state.value.savePhase)
        first.result.complete(OperationResult.Completed(Unit)); runCurrent()
        assertEquals(MessageSavePhase.SAVING, f.session.state.value.savePhase)
        val second = f.messages.pending.last()
        assertNotSame(first, second)
        second.result.complete(OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))); runCurrent()
        assertEquals(MessageSavePhase.FAILED, f.session.state.value.savePhase)
        assertEquals(ProblemCode.IO_FAILURE, f.session.state.value.problem?.code)
        f.text("!")
        f.messages.pending.last().result.complete(OperationResult.Completed(Unit)); runCurrent()
        assertEquals(MessageSavePhase.SAVED, f.session.state.value.savePhase)
        f.close()
    }

    @Test fun clearHasOneUndoAndPersistentResumeNeverAutoplays() = runTest {
        val f = Fixture(this)
        f.start(); f.text("Привет")
        f.send(CommunicationCommand.Clear(f.id()))
        assertEquals("", renderMessage(f.messages.stored.draft))
        assertTrue(f.session.state.value.canUndoClear)
        f.send(CommunicationCommand.UndoClear(f.id()))
        assertEquals("Привет", f.session.state.value.renderedText)
        assertFalse(f.session.state.value.canUndoClear)
        f.text("!")
        f.send(CommunicationCommand.UndoClear(f.id()))
        assertEquals("Привет!", f.session.state.value.renderedText)
        f.send(CommunicationCommand.Speak(f.id()))
        f.close(); f.start()
        assertEquals("Привет!", f.session.state.value.renderedText)
        assertEquals(1, f.audio.played.size)
        assertEquals(AudioPhase.IDLE, f.session.state.value.audio.phase)
        f.send(CommunicationCommand.Clear(f.id()))
        f.close(); f.start()
        assertEquals("", f.session.state.value.renderedText)
        assertFalse(f.session.state.value.canUndoClear)
        f.close()
    }

    @Test fun gcPinsSurviveSourceDeletionClearUndoFailedSaveAndCloseUntilPendingSaveEnds() = runTest {
        val f = Fixture(this)
        val asset = AssetId("a".repeat(64))
        f.board("a", "Хочу", speech = SpeechSource.Recording(asset))
        f.start(); f.open("a")
        f.messages.delayed = true
        f.select("a")
        val selectedSave = f.messages.pending.last()
        f.send(CommunicationCommand.CloseBoard(f.id()))
        f.library.boards.clear()
        f.media.collectGarbage()
        assertTrue(asset in f.media.existing)
        f.send(CommunicationCommand.Clear(f.id()))
        f.media.collectGarbage()
        assertTrue(asset in f.media.existing)
        f.send(CommunicationCommand.UndoClear(f.id()))
        selectedSave.result.complete(OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))); runCurrent()
        f.messages.pending.last().result.complete(OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))); runCurrent()
        assertEquals(MessageSavePhase.FAILED, f.session.state.value.savePhase)
        f.media.collectGarbage()
        assertTrue(asset in f.media.existing)
        f.send(CommunicationCommand.SetMode(f.id(), InteractionMode.COMPOSE))
        f.close()
        f.media.collectGarbage()
        assertTrue(asset in f.media.existing)
        for (pending in f.messages.pending) pending.result.complete(OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)))
        runCurrent()
        f.media.collectGarbage()
        assertFalse(asset in f.media.existing)
        assertTrue(f.media.refs.values.all { it == 0 })
    }

    @Test fun epochSwitchRejectsLateSaveAndCannotResurrectOrSpeakOldMessage() = runTest {
        val f = Fixture(this)
        f.start(); f.messages.delayed = true; f.text("старое")
        val old = f.messages.pending.single()
        f.messages.stored = StoredMessage(1, 0, MessageDraft())
        f.library.listing.value = LibraryListing(1, 1, emptyList()); runCurrent()
        old.result.complete(OperationResult.Completed(Unit)); runCurrent()
        assertEquals(ProblemCode.STALE_REQUEST, f.session.state.value.problem?.code)
        f.text("новое")
        f.send(CommunicationCommand.Speak(f.id()))
        assertTrue(f.audio.played.isEmpty())
        assertEquals(StoredMessage(1, 0, MessageDraft()), f.messages.stored)
        f.close(); f.start()
        assertEquals("", f.session.state.value.renderedText)
        f.close()
    }

    @Test fun unavailableSpeechAndOverflowPreserveWholeVisibleMessage() = runTest {
        val f = Fixture(this)
        f.board("none", "не озвучено", speech = SpeechSource.None)
        f.start(); f.text("начало"); f.open("none"); f.select("none")
        val before = f.session.state.value.message
        f.send(CommunicationCommand.Speak(f.id()))
        assertEquals(ProblemCode.SPEECH_UNAVAILABLE, f.session.state.value.problem?.code)
        assertTrue(f.audio.played.isEmpty())
        assertEquals(before, f.session.state.value.message)
        f.text("x".repeat(65536))
        assertEquals(before, f.session.state.value.message)
        assertEquals(ProblemCode.LIMIT_EXCEEDED, f.session.state.value.problem?.code)
        f.close()
    }

    @Test fun navigationGenerationAndInitialDefaultModeDoNotOverrideExistingSession() = runTest {
        val f = Fixture(this)
        f.board("slow", "a")
        f.board("direct", "b", mode = InteractionMode.DIRECT)
        val delay = CompletableDeferred<Unit>()
        f.library.delayed[BoardId("slow")] = delay
        f.library.ignoresCancellation += BoardId("slow")
        f.start(); f.open("slow"); f.open("direct")
        delay.complete(Unit); runCurrent()
        assertEquals(BoardId("direct"), f.session.state.value.board?.document?.id)
        assertEquals(InteractionMode.DIRECT, f.session.state.value.message.mode)
        f.send(CommunicationCommand.SetMode(f.id(), InteractionMode.COMPOSE))
        f.open("direct")
        assertEquals(InteractionMode.COMPOSE, f.session.state.value.message.mode)
        f.close()
    }

    @Test fun rejectedRevisionDoesNotChooseModeAndMatchingRevisionOpensWithBoardDefault() = runTest {
        val f = Fixture(this)
        val board = f.board("direct", "Хочу", mode = InteractionMode.DIRECT)
        f.start()
        val before = f.session.state.value
        val leases = f.media.openLeases
        f.send(CommunicationCommand.OpenBoard(f.id(), board.document.id, expectedRevision = 2))
        assertEquals(before.copy(problem = Problem(ProblemCode.CONFLICT, "direct", 2, 1)), f.session.state.value)
        assertTrue(f.messages.attempts.isEmpty())
        assertEquals(leases, f.media.openLeases)
        f.send(CommunicationCommand.OpenBoard(f.id(), board.document.id, expectedRevision = board.revision))
        assertEquals(board, f.session.state.value.board)
        assertEquals(BoardCursor(board.document.id, PageId("direct")), f.session.state.value.message.cursor)
        assertEquals(InteractionMode.DIRECT, f.session.state.value.message.mode)
        assertNull(f.session.state.value.problem)
        f.close()
        assertEquals(0, f.media.openLeases)
    }

    @Test fun boardRevisionChangedDuringLoadPreservesPreviousStateAndLease() = runTest {
        val f = Fixture(this)
        val previousAsset = AssetId("a".repeat(64))
        val nextAsset = AssetId("b".repeat(64))
        f.board("previous", "Хочу", speech = SpeechSource.Recording(previousAsset))
        val next = f.board("next", "кашу", speech = SpeechSource.Recording(nextAsset))
        f.messages.stored = StoredMessage(0, 7, MessageDraft(
            parts = listOf(MessagePart.Text(MessagePartId("text"), "фраза")),
            mode = InteractionMode.DIRECT,
            cursor = BoardCursor(BoardId("previous"), PageId("previous"), 1),
        ))
        f.start()
        val before = f.session.state.value
        val stored = f.messages.stored
        val attempts = f.messages.attempts.size
        val leases = f.media.openLeases
        val delay = CompletableDeferred<Unit>()
        f.library.delayed[next.document.id] = delay
        f.send(CommunicationCommand.OpenBoard(f.id(), next.document.id, expectedRevision = next.revision))
        f.library.boards[next.document.id] = next.copy(revision = 2)
        delay.complete(Unit); runCurrent()
        assertEquals(before.copy(problem = Problem(ProblemCode.CONFLICT, "next", 1, 2)), f.session.state.value)
        assertEquals(stored, f.messages.stored)
        assertEquals(attempts, f.messages.attempts.size)
        assertEquals(leases, f.media.openLeases)
        assertEquals(1, f.media.refs.getValue(previousAsset))
        assertEquals(0, f.media.refs.getValue(nextAsset))
        f.select("previous")
        assertEquals("Хочу", f.audio.played.single().plan.renderedText)
        f.close()
        assertEquals(0, f.media.openLeases)
    }

    @Test fun awaitedSameBoardReloadCompletesOnlyAfterLoadAndFailureKeepsPreviousState() = runTest {
        val f = Fixture(this)
        val board = f.board("a", "Хочу")
        f.start(); f.open("a")
        val before = f.session.state.value
        val leases = f.media.openLeases
        val delay = CompletableDeferred<Unit>()
        f.library.delayed[board.document.id] = delay
        val reload = async { f.session.openBoard(board.document.id, expectedRevision = board.revision) }
        runCurrent()
        assertFalse(reload.isCompleted)
        assertEquals(before, f.session.state.value)
        delay.complete(Unit); runCurrent()
        assertEquals(OperationResult.Completed(board), reload.await())
        assertEquals(board, f.session.state.value.board)
        assertEquals(leases, f.media.openLeases)

        val loaded = f.session.state.value
        val stored = f.messages.stored
        val attempts = f.messages.attempts.size
        f.library.boards.remove(board.document.id)
        val failed = async { f.session.openBoard(board.document.id, expectedRevision = board.revision) }
        runCurrent()
        assertEquals(OperationResult.Failed(Problem(ProblemCode.NOT_FOUND)), failed.await())
        assertEquals(loaded.copy(problem = Problem(ProblemCode.NOT_FOUND)), f.session.state.value)
        assertEquals(stored, f.messages.stored)
        assertEquals(attempts, f.messages.attempts.size)
        assertEquals(leases, f.media.openLeases)
        f.close()
        assertEquals(0, f.media.openLeases)
    }

    @Test fun cancelledAwaiterCannotOpenQueuedOrLoadedBoard() = runTest {
        for (cancelBeforeCommand in listOf(true, false)) {
            val f = Fixture(this)
            val board = f.board("a", "Хочу")
            f.start()
            val before = f.session.state.value
            val leases = f.media.openLeases
            val opened = async(start = CoroutineStart.UNDISPATCHED) { f.session.openBoard(board.document.id) }
            if (cancelBeforeCommand) opened.cancel()
            else f.library.beforeReadResult = { opened.cancel() }
            runCurrent()
            assertTrue(opened.isCancelled)
            assertEquals(before, f.session.state.value)
            assertTrue(f.messages.attempts.isEmpty())
            assertEquals(leases, f.media.openLeases)
            f.close()
            assertEquals(0, f.media.openLeases)
        }
    }

    @Test fun awaitedOpenSettlesWhenSessionScopeWasAlreadyCancelled() = runTest {
        val f = Fixture(this)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scope.cancel()
        f.start(scope)
        val opened = async { f.session.openBoard(BoardId("a")) }
        runCurrent()
        assertTrue(opened.isCompleted)
        assertEquals(OperationResult.Cancelled, opened.await())
        assertNull(f.session.state.value.board)
    }

    @Test fun rejectedPendingOpenSettlesWithoutWaitingForNonCancellableRead() = runTest {
        for (rejection in listOf("superseded", "closed", "epoch", "unconfirmed")) {
            val f = Fixture(this)
            val board = f.board("slow", "Хочу")
            f.board("next", "кашу")
            val delay = CompletableDeferred<Unit>()
            f.library.delayed[board.document.id] = delay
            f.library.ignoresCancellation += board.document.id
            f.start()
            val opened = async { f.session.openBoard(board.document.id) }
            runCurrent()
            assertFalse(opened.isCompleted)
            when (rejection) {
                "superseded" -> f.open("next")
                "closed" -> f.close()
                "epoch" -> f.library.listing.value = LibraryListing(1, 1, emptyList())
                else -> f.library.listing.value = f.library.listing.value.copy(
                    refreshProblem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN),
                )
            }
            runCurrent()
            assertTrue(opened.isCompleted, rejection)
            assertEquals(when (rejection) {
                "epoch" -> OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
                "unconfirmed" -> OperationResult.UnconfirmedCommit
                else -> OperationResult.Cancelled
            }, opened.await(), rejection)
            val before = f.session.state.value
            delay.complete(Unit); runCurrent()
            assertEquals(before, f.session.state.value)
            f.close()
            assertEquals(0, f.media.openLeases)
        }
    }

    @Test fun boundedDeliveryWindowAndEmptySpaceCells() = runTest {
        val f = Fixture(this)
        f.board("literal", "a", InputStyle.LITERAL)
        f.start(); f.open("literal")
        f.select("literal", 2)
        assertEquals("", f.session.state.value.renderedText)
        f.select("literal", 1)
        assertEquals(" ", f.session.state.value.renderedText)
        val old = f.id()
        f.send(CommunicationCommand.InsertText(old, "a"))
        repeat(1024) { f.text("b") }
        f.send(CommunicationCommand.InsertText(old, "a"))
        assertEquals(" a" + "b".repeat(1024) + "a", f.session.state.value.renderedText)
        f.close()
    }

    @Test fun loadFailureNeverOverwritesStoredMessage() = runTest {
        val f = Fixture(this)
        f.messages.loadFailure = Problem(ProblemCode.IO_FAILURE)
        f.start(); f.text("не заменять")
        assertEquals(MessageSavePhase.FAILED, f.session.state.value.savePhase)
        assertTrue(f.messages.attempts.isEmpty())
        f.close()
    }

    @Test fun composePlayingMutationMatrixAndDirectLiteral() = runTest {
        for (action in listOf("backspace", "clear", "mode", "text", "stop")) {
            val f = Fixture(this)
            f.board("a", "Хочу")
            f.start(); f.open("a"); f.select("a")
            f.send(CommunicationCommand.Speak(f.id()))
            val played = f.audio.played.single()
            val command = when (action) {
                "backspace" -> CommunicationCommand.Backspace(f.id())
                "clear" -> CommunicationCommand.Clear(f.id())
                "mode" -> CommunicationCommand.SetMode(f.id(), InteractionMode.DIRECT)
                "text" -> CommunicationCommand.InsertText(f.id(), ", мир")
                else -> CommunicationCommand.Stop(f.id())
            }
            f.send(command)
            assertEquals(AudioPhase.IDLE, f.session.state.value.audio.phase, action)
            assertTrue(played.id in f.audio.stopped, action)
            assertEquals(when (action) { "backspace", "clear" -> ""; "text" -> "Хочу, мир"; else -> "Хочу" }, f.session.state.value.renderedText)
            f.audio.callback(played, AudioPhase.COMPLETED); runCurrent()
            assertEquals(AudioPhase.IDLE, f.session.state.value.audio.phase)
            f.close()
        }
        val f = Fixture(this)
        f.board("literal", "мёд", InputStyle.LITERAL, mode = InteractionMode.DIRECT)
        f.start(); f.open("literal"); f.select("literal")
        assertEquals("мёд", f.audio.played.single().plan.renderedText)
        assertEquals("", f.session.state.value.renderedText)
        f.select("literal", 2)
        assertEquals(1, f.audio.played.size)
        f.close()
    }

    @Test fun closedStateCannotBeChangedBySuccessfulSaveOrLateAudioCallback() = runTest {
        val f = Fixture(this)
        f.start(); f.messages.delayed = true; f.text("фраза")
        f.send(CommunicationCommand.Speak(f.id()))
        val audio = f.audio.played.single()
        f.close()
        val closed = f.session.state.value
        f.messages.pending.single().result.complete(OperationResult.Completed(Unit))
        f.audio.callback(audio, AudioPhase.PLAYING); runCurrent()
        assertEquals(closed, f.session.state.value)
        assertEquals("фраза", renderMessage(f.messages.stored.draft))
        assertTrue(f.media.refs.values.all { it == 0 })
    }

    @Test fun explicitTextBeforeOpeningBoardKeepsComposeAndPartLimitDoesNotTruncate() = runTest {
        val f = Fixture(this)
        f.board("direct", "a", mode = InteractionMode.DIRECT)
        f.start(); f.text("Хочу"); f.open("direct")
        assertEquals(InteractionMode.COMPOSE, f.session.state.value.message.mode)
        repeat(4095) { f.select("direct") }
        val before = f.session.state.value.message
        assertEquals(4096, before.parts.size)
        f.select("direct")
        assertEquals(before, f.session.state.value.message)
        assertEquals(ProblemCode.LIMIT_EXCEEDED, f.session.state.value.problem?.code)
        f.close()
    }

    @Test fun slowSaveCoalescesFiveHundredKeysIntoOneLatestWriteAndBoundedPins() = runTest {
        val f = Fixture(this)
        val asset = AssetId("a".repeat(64))
        f.board("a", "Хочу", speech = SpeechSource.Recording(asset))
        f.start(); f.open("a")
        f.messages.attempts.clear()
        f.messages.delayed = true
        f.select("a")
        repeat(500) {
            f.text("x")
            assertTrue(f.media.refs.getValue(asset) <= 4, "Only board/current/active/latest may pin the asset")
        }
        val latest = f.session.state.value.message
        assertEquals("Хочу " + "x".repeat(500), f.session.state.value.renderedText)
        assertEquals(1, f.messages.attempts.size)
        assertEquals(1, f.messages.pending.size)
        f.messages.pending.single().result.complete(OperationResult.Completed(Unit)); runCurrent()
        assertEquals(2, f.messages.attempts.size)
        assertEquals(latest, f.messages.attempts.last().draft)
        assertTrue(f.messages.attempts.last().version - f.messages.attempts.first().version >= 500)
        assertEquals(MessageSavePhase.SAVING, f.session.state.value.savePhase)
        f.messages.pending.last().result.complete(OperationResult.Completed(Unit)); runCurrent()
        assertEquals(latest, f.messages.stored.draft)
        assertEquals(MessageSavePhase.SAVED, f.session.state.value.savePhase)
        f.close()
        assertEquals(0, f.media.openLeases)
    }

    @Test fun unconfirmedSaveFreezesEvenSupersededVersionWithoutSendingPendingOrReplaying() = runTest {
        for (superseded in listOf(false, true)) {
            val f = Fixture(this)
            val asset = AssetId("a".repeat(64))
            f.board("a", "Хочу", speech = SpeechSource.Recording(asset))
            f.start(); f.open("a")
            f.messages.attempts.clear()
            f.messages.delayed = true
            f.select("a")
            if (superseded) {
                f.send(CommunicationCommand.CloseBoard(f.id()))
                f.text("мёд")
            }
            val latest = f.session.state.value.message
            f.send(CommunicationCommand.Speak(f.id()))
            val playing = f.audio.played.single()
            f.library.boards.clear()
            f.messages.pending.single().result.complete(OperationResult.UnconfirmedCommit); runCurrent()
            val frozen = f.session.state.value
            assertEquals(MessageSavePhase.UNCONFIRMED, frozen.savePhase)
            assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN, frozen.problem?.code)
            assertEquals(latest, frozen.message)
            assertEquals(AudioPhase.IDLE, frozen.audio.phase)
            assertFalse(frozen.canUndoClear)
            assertEquals(if (superseded) 1 else 2, f.media.refs.getValue(asset))
            f.media.collectGarbage()
            assertTrue(asset in f.media.existing)
            f.text("запрещено")
            f.send(CommunicationCommand.Clear(f.id()))
            f.send(CommunicationCommand.UndoClear(f.id()))
            f.send(CommunicationCommand.SetMode(f.id(), InteractionMode.DIRECT))
            f.send(CommunicationCommand.OpenBoard(f.id(), BoardId("a")))
            f.send(CommunicationCommand.Speak(f.id()))
            f.audio.callback(playing, AudioPhase.PLAYING); runCurrent()
            assertEquals(frozen, f.session.state.value)
            assertEquals(1, f.messages.attempts.size)
            assertEquals(1, f.audio.played.size)
            f.close()
            assertEquals(0, f.media.openLeases)
            assertEquals(1, f.messages.attempts.size)
        }
    }

    @Test fun listingQuarantineWithoutEpochChangeFreezesUndoAndOldSuccessCannotUnfreeze() = runTest {
        val f = Fixture(this)
        f.start(); f.text("фраза")
        f.messages.delayed = true
        f.send(CommunicationCommand.Clear(f.id()))
        f.text("новая")
        assertTrue(f.session.state.value.canUndoClear)
        val latest = f.session.state.value.message
        val attempts = f.messages.attempts.size
        f.send(CommunicationCommand.Speak(f.id()))
        f.library.listing.value = f.library.listing.value.copy(refreshProblem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
        runCurrent()
        assertEquals(MessageSavePhase.UNCONFIRMED, f.session.state.value.savePhase)
        assertEquals(latest, f.session.state.value.message)
        assertEquals(AudioPhase.IDLE, f.session.state.value.audio.phase)
        assertFalse(f.session.state.value.canUndoClear)
        f.messages.pending.single().result.complete(OperationResult.Completed(Unit)); runCurrent()
        f.library.listing.value = f.library.listing.value.copy(refreshProblem = null); runCurrent()
        f.text("не продолжать")
        f.send(CommunicationCommand.UndoClear(f.id()))
        f.send(CommunicationCommand.Speak(f.id()))
        assertEquals(MessageSavePhase.UNCONFIRMED, f.session.state.value.savePhase)
        assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN, f.session.state.value.problem?.code)
        assertEquals(latest, f.session.state.value.message)
        assertEquals(attempts, f.messages.attempts.size)
        assertEquals(1, f.audio.played.size)
        f.close()
        assertEquals(0, f.media.openLeases)
    }

    @Test fun closeFlushesOnlyActiveAndLatestPendingThenReleasesAllPins() = runTest {
        val f = Fixture(this)
        f.start(); f.messages.delayed = true
        f.text("a")
        repeat(500) { f.text("b") }
        val latest = f.session.state.value.message
        f.close()
        val closed = f.session.state.value
        assertEquals(1, f.messages.attempts.size)
        f.messages.pending.single().result.complete(OperationResult.Completed(Unit)); runCurrent()
        assertEquals(2, f.messages.attempts.size)
        f.messages.pending.last().result.complete(OperationResult.Completed(Unit)); runCurrent()
        assertEquals(latest, f.messages.stored.draft)
        assertEquals(closed, f.session.state.value)
        assertEquals(0, f.media.openLeases)
        f.text("после close")
        assertEquals(2, f.messages.attempts.size)
    }

    @Test fun unconfirmedDuringCloseDiscardsLatestPendingAndReleasesPins() = runTest {
        val f = Fixture(this)
        f.start(); f.messages.delayed = true
        f.text("a"); f.text("b")
        f.close()
        f.messages.pending.single().result.complete(OperationResult.UnconfirmedCommit); runCurrent()
        assertEquals(MessageSavePhase.UNCONFIRMED, f.session.state.value.savePhase)
        assertEquals("ab", f.session.state.value.renderedText)
        assertEquals(1, f.messages.attempts.size)
        assertEquals(0, f.media.openLeases)
    }

    @Test fun cancellationBeforeSaveCoroutineStartsReleasesRetainWithoutWriting() = runTest {
        val f = Fixture(this)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        f.board("a", "Хочу", speech = SpeechSource.Recording(AssetId("a".repeat(64))))
        f.start(scope); f.open("a"); f.select("a")
        f.messages.attempts.clear()
        var retained = 0
        f.media.onRetain = { if (++retained == 2) scope.cancel() }
        f.text("мёд")
        assertEquals(2, retained)
        assertEquals(0, f.media.openLeases)
        assertTrue(f.messages.attempts.isEmpty())
    }

    @Test fun parentCancellationWithActiveAndPendingReleasesBothAndIgnoresLateResult() = runTest {
        val f = Fixture(this)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        f.start(scope); f.messages.delayed = true
        f.text("a"); f.text("b")
        scope.cancel(); runCurrent()
        assertEquals(0, f.media.openLeases)
        val closed = f.session.state.value
        f.messages.pending.single().result.complete(OperationResult.Completed(Unit)); runCurrent()
        assertEquals(closed, f.session.state.value)
        assertEquals(1, f.messages.attempts.size)
    }

    @Test fun restoredCursorKeepsValidFirstCellButRejectsOutOfRangeOrDifferentPage() = runTest {
        for ((pageId, firstCell, expected) in listOf(Triple("a", 1, 1), Triple("a", 3, 0), Triple("deleted", 1, 0))) {
            val f = Fixture(this)
            f.board("a", "Хочу")
            f.messages.stored = StoredMessage(0, 7, MessageDraft(cursor = BoardCursor(BoardId("a"), PageId(pageId), firstCell)))
            f.start()
            assertEquals(BoardCursor(BoardId("a"), PageId("a"), expected), f.session.state.value.message.cursor)
            assertTrue(f.audio.played.isEmpty())
            f.close()
        }
    }

    @Test fun startupQuarantineIsUnconfirmedEvenIfMessageReadFails() = runTest {
        for (readFails in listOf(false, true)) {
            val f = Fixture(this)
            f.board("a", "Хочу")
            val message = MessageDraft(
                parts = listOf(MessagePart.Text(MessagePartId("text"), "фраза")),
                cursor = BoardCursor(BoardId("a"), PageId("a"), 1),
            )
            f.messages.stored = StoredMessage(0, 7, message)
            if (readFails) f.messages.loadFailure = Problem(ProblemCode.IO_FAILURE)
            f.library.listing.value = f.library.listing.value.copy(refreshProblem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
            f.start()
            assertEquals(MessageSavePhase.UNCONFIRMED, f.session.state.value.savePhase)
            assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN, f.session.state.value.problem?.code)
            assertNull(f.session.state.value.board)
            assertTrue(f.messages.attempts.isEmpty())
            assertTrue(f.audio.played.isEmpty())
            if (!readFails) assertEquals(message, f.session.state.value.message)
            f.close()
            assertEquals(0, f.media.openLeases)
        }
    }

    @Test fun boardResultCannotPublishOrSaveWhileQuarantineObserverEventIsStillQueued() = runTest {
        val f = Fixture(this)
        f.board("a", "Хочу")
        f.start()
        f.library.beforeReadResult = {
            f.library.listing.value = f.library.listing.value.copy(refreshProblem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
        }
        f.open("a")
        assertEquals(MessageSavePhase.UNCONFIRMED, f.session.state.value.savePhase)
        assertNull(f.session.state.value.board)
        assertNull(f.session.state.value.message.cursor)
        assertTrue(f.messages.attempts.isEmpty())
        f.close()
        assertEquals(0, f.media.openLeases)
    }
}
