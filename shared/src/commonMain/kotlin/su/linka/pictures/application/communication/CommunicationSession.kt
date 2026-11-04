package su.linka.pictures.application.communication

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.Problem
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.text.dropLastGrapheme

class CommunicationSession(
    private val library: LibraryStore,
    private val messages: MessageStore,
    private val media: MediaStore,
    private val audio: AudioPort,
    private val ids: IdSource,
    scope: CoroutineScope,
) {
    private val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val events = Channel<() -> Unit>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(CommunicationState())
    val state: StateFlow<CommunicationState> = mutableState.asStateFlow()
    private val seen = linkedSetOf<CommandId>()
    private data class PendingSave(val value: StoredMessage, val lease: MediaLease)
    private var activeSave: Job? = null
    private var pendingSave: PendingSave? = null
    private var boardLease: MediaLease? = null
    private var messageLease: MediaLease = MessageLease(emptyList())
    private var undo: MessageDraft? = null
    private var undoLease: MediaLease? = null
    private var navigation = 0L
    private var boardJob: Job? = null
    private var request: RequestId? = null
    private val owner = ids.next()
    private var ready = false
    private var closing = false
    private var stale = false
    private var unconfirmed = false
    private var modeChosen = false

    init {
        sessionScope.launch {
            try {
                restore()
                for (event in events) event()
            } finally {
                closing = true
                ready = false
                discardPendingSave()
                events.close()
                while (true) (events.tryReceive().getOrNull() ?: break).invoke()
                audio.stop(owner)
                boardLease?.close()
                messageLease.close()
                undoLease?.close()
                sessionScope.cancel()
            }
        }
        sessionScope.launch {
            audio.state.collect { value ->
                events.trySend {
                    if (!closing && value.owner?.id == owner && value.requestId == request && request != null) {
                        mutableState.value = state.value.copy(audio = value)
                        if (value.phase in terminalPhases) request = null
                    }
                }
            }
        }
        sessionScope.launch {
            library.listing.collect { listing ->
                events.trySend {
                    if (listing.refreshProblem?.code == ProblemCode.COMMIT_OUTCOME_UNKNOWN) freezeUnconfirmed()
                    else if (ready) checkLibrary()
                }
            }
        }
    }

    fun dispatch(command: CommunicationCommand) {
        events.trySend {
            if (ready && !closing) checkLibrary()
            if (!closing && ready && !stale && !unconfirmed && seen.add(command.id)) {
                if (seen.size > 1024) seen.remove(seen.first())
                reduce(command)
            }
        }
    }

    fun interrupt() {
        events.trySend { if (!closing) stop() }
    }

    fun close() {
        events.trySend {
            if (!closing) {
                checkLibrary()
                closing = true
                navigation++
                boardJob?.cancel()
                stop()
                finishClosingIfIdle()
            }
        }
    }

    private suspend fun restore() {
        when (val loaded = operation { messages.message() }) {
            is OperationResult.Completed -> {
                val stored = loaded.value
                when (val acquired = operation { media.acquire(stored.draft.assetIds()) }) {
                    is OperationResult.Completed -> {
                        messageLease.close()
                        messageLease = acquired.value
                        mutableState.value = CommunicationState(
                            message = stored.draft, stamp = stored.stamp,
                            renderedText = renderMessage(stored.draft), savePhase = MessageSavePhase.SAVED,
                        )
                        ready = true
                        modeChosen = stored.version != 0L || stored.draft != MessageDraft()
                        checkLibrary()
                        if (!stale && !unconfirmed) stored.draft.cursor?.let { open(it.boardId, it.pageId) }
                    }
                    is OperationResult.Failed -> failLoad(acquired.problem)
                    OperationResult.Cancelled -> failLoad(Problem(ProblemCode.INTERRUPTED))
                    OperationResult.UnconfirmedCommit -> freezeUnconfirmed()
                }
            }
            is OperationResult.Failed -> failLoad(loaded.problem)
            OperationResult.Cancelled -> failLoad(Problem(ProblemCode.INTERRUPTED))
            OperationResult.UnconfirmedCommit -> freezeUnconfirmed()
        }
    }

    private fun failLoad(problem: Problem) {
        checkLibrary()
        if (!unconfirmed) mutableState.value = state.value.copy(savePhase = MessageSavePhase.FAILED, problem = problem)
    }

    private fun invalidateEpoch() {
        if (unconfirmed) return
        stale = true
        discardPendingSave()
        navigation++
        boardJob?.cancel()
        stop()
        boardLease?.close()
        boardLease = null
        undoLease?.close()
        undoLease = null
        undo = null
        mutableState.value = state.value.copy(
            board = null, canUndoClear = false, savePhase = MessageSavePhase.FAILED,
            problem = Problem(ProblemCode.STALE_REQUEST),
        )
    }

    private fun checkLibrary() {
        if (unconfirmed) return
        val listing = library.listing.value
        if (listing.refreshProblem?.code == ProblemCode.COMMIT_OUTCOME_UNKNOWN) freezeUnconfirmed()
        else if (!closing && ready && listing.epoch != state.value.stamp.epoch) invalidateEpoch()
    }

    private fun freezeUnconfirmed() {
        if (unconfirmed) return
        unconfirmed = true
        navigation++
        boardJob?.cancel()
        stop()
        discardPendingSave()
        mutableState.value = state.value.copy(
            canUndoClear = false, savePhase = MessageSavePhase.UNCONFIRMED,
            problem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN),
        )
    }

    private fun discardPendingSave() {
        pendingSave?.lease?.close()
        pendingSave = null
    }

    private fun finishClosingIfIdle() {
        if (closing && activeSave == null && pendingSave == null) events.close()
    }

    private fun reduce(command: CommunicationCommand) {
        when (command) {
            is CommunicationCommand.OpenBoard -> open(command.boardId, command.pageId)
            is CommunicationCommand.ChangePage -> {
                val board = state.value.board ?: return
                if (board.document.pages.none { it.id == command.pageId }) return
                change(state.value.message.copy(cursor = BoardCursor(board.document.id, command.pageId)))
            }
            is CommunicationCommand.SetMode -> {
                stop()
                modeChosen = true
                change(state.value.message.copy(mode = command.mode))
            }
            is CommunicationCommand.Select -> select(command)
            is CommunicationCommand.InsertText -> if (state.value.message.mode == InteractionMode.COMPOSE) appendText(command.text)
            is CommunicationCommand.Backspace -> if (state.value.message.mode == InteractionMode.COMPOSE) {
                val parts = state.value.message.parts
                val last = parts.lastOrNull() ?: return
                stop()
                val prefix = parts.dropLast(1)
                val next = when (last) {
                    is MessagePart.Card -> prefix
                    is MessagePart.Text -> dropLastGrapheme(last.text).let { text ->
                        if (text.isEmpty()) prefix else prefix + last.copy(text = text)
                    }
                }
                change(state.value.message.copy(parts = next))
            }
            is CommunicationCommand.Clear -> if (state.value.message.mode == InteractionMode.COMPOSE) clear()
            is CommunicationCommand.UndoClear -> if (state.value.message.mode == InteractionMode.COMPOSE) {
                val previous = undo ?: return
                stop()
                // Undo restores content, not an obsolete navigation cursor or interaction mode.
                if (change(state.value.message.copy(parts = previous.parts))) {
                    undo = null
                    undoLease?.close()
                    undoLease = null
                    mutableState.value = state.value.copy(canUndoClear = false)
                }
            }
            is CommunicationCommand.Speak -> if (request != null) stop()
                else if (state.value.message.mode == InteractionMode.COMPOSE) play(state.value.message, messageLease, AudioPurpose.COMMUNICATION)
            is CommunicationCommand.Stop -> stop()
            is CommunicationCommand.CloseBoard -> {
                navigation++
                boardJob?.cancel()
                stop()
                boardLease?.close()
                boardLease = null
                mutableState.value = state.value.copy(board = null)
                change(state.value.message.copy(cursor = null))
            }
        }
    }

    private fun open(id: BoardId, pageId: PageId?) {
        val generation = ++navigation
        boardJob?.cancel()
        boardJob = sessionScope.launch {
            val result = operation { library.board(id) }
            var transferred = false
            try {
                ensureActive()
                transferred = events.trySend {
                    checkLibrary()
                    if (closing || stale || unconfirmed || generation != navigation) {
                        if (result is OperationResult.Completed) result.value.lease.close()
                    } else when (result) {
                        is OperationResult.Completed -> {
                            val value = result.value
                            if (value.epoch != state.value.stamp.epoch) {
                                value.lease.close()
                                invalidateEpoch()
                            } else {
                                val page = value.board.document.pages.firstOrNull { it.id == pageId }
                                    ?: value.board.document.pages.first()
                                boardLease?.close()
                                boardLease = value.lease
                                mutableState.value = state.value.copy(board = value.board)
                                val mode = if (modeChosen) state.value.message.mode else value.board.document.behavior.defaultMode
                                modeChosen = true
                                val previous = state.value.message.cursor
                                val firstCell = previous?.takeIf {
                                    it.boardId == id && it.pageId == page.id && it.firstCell < page.capacity
                                }?.firstCell ?: 0
                                change(state.value.message.copy(cursor = BoardCursor(id, page.id, firstCell), mode = mode))
                            }
                        }
                        is OperationResult.Failed -> report(result.problem)
                        OperationResult.Cancelled -> Unit
                        OperationResult.UnconfirmedCommit -> freezeUnconfirmed()
                    }
                }.isSuccess
            } finally {
                if (!transferred && result is OperationResult.Completed) result.value.lease.close()
            }
        }
    }

    private fun select(command: CommunicationCommand.Select) {
        val current = state.value
        val board = current.board ?: return
        if (current.message.cursor?.pageId != command.pageId) return
        val page = board.document.pages.firstOrNull { it.id == command.pageId } ?: return
        val cell = page.cells.firstOrNull { it.position == command.position } ?: return
        if (cell.kind == CellKind.EMPTY) return
        val card = cell.card
        val part = if (cell.kind == CellKind.SPACE || board.document.behavior.inputStyle == InputStyle.LITERAL) {
            MessagePart.Text(MessagePartId(ids.next()), if (cell.kind == CellKind.SPACE) " " else card!!.label)
        } else MessagePart.Card(
            MessagePartId(ids.next()), CardSelectionSnapshot(
                SelectionOrigin(board.document.id, board.revision, page.id, card!!.id), card.label, card.image, card.speech,
            ),
        )
        if (current.message.mode == InteractionMode.DIRECT) {
            stop()
            play(MessageDraft(parts = listOf(part)), boardLease ?: return, AudioPurpose.DIRECT)
        } else when (part) {
            is MessagePart.Text -> appendText(part.text)
            is MessagePart.Card -> {
                stop()
                change(current.message.copy(parts = current.message.parts + part))
            }
        }
    }

    private fun appendText(text: String) {
        if (text.isEmpty()) return
        stop()
        val message = state.value.message
        val last = message.parts.lastOrNull()
        val parts = if (last is MessagePart.Text) message.parts.dropLast(1) + last.copy(text = last.text + text)
            else message.parts + MessagePart.Text(MessagePartId(ids.next()), text)
        change(message.copy(parts = parts))
    }

    private fun clear() {
        stop()
        val previous = state.value.message
        when (val retained = messageLease.retain(previous.assetIds())) {
            is OperationResult.Completed -> {
                undoLease?.close()
                undoLease = retained.value
                undo = previous
                change(previous.copy(parts = emptyList()))
            }
            is OperationResult.Failed -> report(retained.problem)
            OperationResult.Cancelled -> Unit
            OperationResult.UnconfirmedCommit -> freezeUnconfirmed()
        }
    }

    private fun change(next: MessageDraft): Boolean {
        val rendered = renderMessage(next)
        if (next.parts.size > 4096 || rendered.length > 65536 || state.value.stamp.version == Long.MAX_VALUE) {
            report(Problem(ProblemCode.LIMIT_EXCEEDED))
            return false
        }
        val sources = listOfNotNull(messageLease, boardLease, undoLease)
        when (val retained = MessageLease.retainFrom(next.assetIds(), sources)) {
            is OperationResult.Completed -> {
                val previous = messageLease
                messageLease = retained.value
                previous.close()
                val stored = StoredMessage(state.value.stamp.epoch, state.value.stamp.version + 1, next)
                mutableState.value = state.value.copy(
                    message = next, stamp = stored.stamp, renderedText = rendered,
                    canUndoClear = undo != null, savePhase = MessageSavePhase.SAVING, problem = null,
                )
                modeChosen = true
                save(stored)
                return true
            }
            is OperationResult.Failed -> report(retained.problem)
            OperationResult.Cancelled -> Unit
            OperationResult.UnconfirmedCommit -> freezeUnconfirmed()
        }
        return false
    }

    private fun save(stored: StoredMessage) {
        val retained = messageLease.retain(stored.draft.assetIds())
        if (retained is OperationResult.UnconfirmedCommit) {
            freezeUnconfirmed()
            return
        }
        if (retained !is OperationResult.Completed) {
            mutableState.value = state.value.copy(savePhase = MessageSavePhase.FAILED)
            if (retained is OperationResult.Failed) report(retained.problem)
            return
        }
        val save = PendingSave(stored, retained.value)
        if (activeSave == null) startSave(save)
        else {
            discardPendingSave()
            pendingSave = save
        }
    }

    private fun startSave(save: PendingSave) {
        var result: OperationResult<Unit> = OperationResult.Cancelled
        val job = sessionScope.launch(start = CoroutineStart.LAZY) {
            result = operation { messages.saveMessage(save.value) }
        }
        activeSave = job
        job.invokeOnCompletion {
            // Completion also owns cleanup if cancellation occurs before the coroutine starts.
            save.lease.close()
            events.trySend { saved(save.value, result) }
        }
        job.start()
    }

    private fun saved(stored: StoredMessage, result: OperationResult<Unit>) {
        activeSave = null
        checkLibrary()
        // Unknown durability is session-wide, even when a newer visible version superseded this save.
        if (result == OperationResult.UnconfirmedCommit) freezeUnconfirmed()
        else if (!closing && !stale && !unconfirmed && stored.stamp == state.value.stamp) when (result) {
            is OperationResult.Completed -> mutableState.value = state.value.copy(savePhase = MessageSavePhase.SAVED)
            is OperationResult.Failed -> {
                if (result.problem.code == ProblemCode.STALE_REQUEST) invalidateEpoch()
                else mutableState.value = state.value.copy(savePhase = MessageSavePhase.FAILED, problem = result.problem)
            }
            OperationResult.Cancelled -> mutableState.value = state.value.copy(
                savePhase = MessageSavePhase.FAILED, problem = Problem(ProblemCode.INTERRUPTED),
            )
            OperationResult.UnconfirmedCommit -> freezeUnconfirmed()
        }
        val next = pendingSave
        pendingSave = null
        if (next != null) {
            if (stale || unconfirmed) next.lease.close() else startSave(next)
        }
        finishClosingIfIdle()
    }

    private fun play(message: MessageDraft, lease: MediaLease, purpose: AudioPurpose) {
        if (message.parts.isEmpty()) return
        when (val planned = speechPlan(message)) {
            is OperationResult.Completed -> when (val retained = lease.retain(message.assetIds())) {
                is OperationResult.Completed -> {
                    val id = RequestId(ids.next())
                    request = id
                    val audioOwner = AudioOwner(owner, purpose)
                    mutableState.value = state.value.copy(audio = AudioState(id, audioOwner, AudioPhase.PREPARING), problem = null)
                    audio.play(PlaybackRequest(id, audioOwner, planned.value, retained.value))
                }
                is OperationResult.Failed -> report(retained.problem)
                OperationResult.Cancelled -> Unit
                OperationResult.UnconfirmedCommit -> freezeUnconfirmed()
            }
            is OperationResult.Failed -> report(planned.problem)
            OperationResult.Cancelled -> Unit
            OperationResult.UnconfirmedCommit -> freezeUnconfirmed()
        }
    }

    private fun stop() {
        val previous = request
        request = null
        audio.stop(owner, previous)
        mutableState.value = state.value.copy(audio = AudioState(phase = AudioPhase.IDLE))
    }

    private fun report(problem: Problem) {
        mutableState.value = state.value.copy(problem = problem)
    }

    private suspend fun <T> operation(block: suspend () -> OperationResult<T>): OperationResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
    }

    private companion object {
        val terminalPhases = setOf(AudioPhase.COMPLETED, AudioPhase.CANCELLED, AudioPhase.INTERRUPTED, AudioPhase.FAILED)
    }
}
