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
    private val saves = mutableSetOf<Job>()
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
    private var modeChosen = false

    init {
        sessionScope.launch {
            try {
                restore()
                for (event in events) event()
            } finally {
                closing = true
                ready = false
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
                    if (!closing && ready && listing.epoch != state.value.stamp.epoch) invalidateEpoch()
                }
            }
        }
    }

    fun dispatch(command: CommunicationCommand) {
        events.trySend {
            if (ready && !closing && library.listing.value.epoch != state.value.stamp.epoch) invalidateEpoch()
            if (!closing && ready && !stale && seen.add(command.id)) {
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
                closing = true
                navigation++
                boardJob?.cancel()
                stop()
                val pending = saves.toList()
                sessionScope.launch {
                    pending.joinAll()
                    events.trySend { events.close() }
                }
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
                        if (stored.epoch != library.listing.value.epoch) invalidateEpoch()
                        else stored.draft.cursor?.let { open(it.boardId, it.pageId) }
                    }
                    is OperationResult.Failed -> failLoad(acquired.problem)
                    OperationResult.Cancelled -> failLoad(Problem(ProblemCode.INTERRUPTED))
                }
            }
            is OperationResult.Failed -> failLoad(loaded.problem)
            OperationResult.Cancelled -> failLoad(Problem(ProblemCode.INTERRUPTED))
        }
    }

    private fun failLoad(problem: Problem) {
        mutableState.value = state.value.copy(savePhase = MessageSavePhase.FAILED, problem = problem)
    }

    private fun invalidateEpoch() {
        stale = true
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
                    if (closing || stale || generation != navigation) {
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
                                change(state.value.message.copy(cursor = BoardCursor(id, page.id), mode = mode))
                            }
                        }
                        is OperationResult.Failed -> report(result.problem)
                        OperationResult.Cancelled -> Unit
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
        }
        return false
    }

    private fun save(stored: StoredMessage) {
        val retained = messageLease.retain(stored.draft.assetIds())
        if (retained !is OperationResult.Completed) {
            mutableState.value = state.value.copy(savePhase = MessageSavePhase.FAILED)
            if (retained is OperationResult.Failed) report(retained.problem)
            return
        }
        val lease = retained.value
        val job = sessionScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = operation { messages.saveMessage(stored) }
                events.trySend {
                    if (!closing && !stale && stored.stamp == state.value.stamp) when (result) {
                        is OperationResult.Completed -> mutableState.value = state.value.copy(savePhase = MessageSavePhase.SAVED)
                        is OperationResult.Failed -> {
                            if (result.problem.code == ProblemCode.STALE_REQUEST) invalidateEpoch()
                            else mutableState.value = state.value.copy(savePhase = MessageSavePhase.FAILED, problem = result.problem)
                        }
                        OperationResult.Cancelled -> mutableState.value = state.value.copy(
                            savePhase = MessageSavePhase.FAILED, problem = Problem(ProblemCode.INTERRUPTED),
                        )
                    }
                }
            } finally {
                lease.close()
            }
        }
        saves += job
        job.invokeOnCompletion {
            // Also covers cancellation before the lazy coroutine enters its try/finally.
            lease.close()
            events.trySend { saves.remove(job) }
        }
        job.start()
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
            }
            is OperationResult.Failed -> report(planned.problem)
            OperationResult.Cancelled -> Unit
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
