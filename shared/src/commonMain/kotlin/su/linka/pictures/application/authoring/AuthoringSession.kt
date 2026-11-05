package su.linka.pictures.application.authoring

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import su.linka.pictures.application.*
import su.linka.pictures.application.communication.MessageLease
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.text.isNativeText

/** One owner, one actor. Only immutable snapshots cross into worker effects. */
class AuthoringSession(
    private val library: LibraryStore,
    private val media: MediaStore,
    private val audio: AudioPort,
    private val files: PlatformFilesPort,
    private val images: ImagePreparationPort,
    private val inspection: MediaInspectionPort,
    private val gate: AssistantGate,
    private val ids: IdSource,
    scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(AuthoringState())
    val state: StateFlow<AuthoringState> = mutableState.asStateFlow()
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val sessionScope = CoroutineScope(scope.coroutineContext + job)
    private val events = Channel<() -> Unit>(Channel.UNLIMITED)
    // Invalidate before queuing background/revoke, including callbacks already queued on the actor.
    private val lifecycle = MutableStateFlow(0L)
    private var resumedAt = 0L
    private var owner: String? = null
    private var capability: AssistantCapability? = null
    private var lease: MediaLease = MessageLease(emptyList())
    private var revision = 0L
    private var durableRevision = -1L
    private var durable: StoredDraft? = null
    private var activeSave = false
    private var pendingSave: Save? = null
    private var applyRequested = false
    private var loading = 0L
    private var mediaGeneration = 0L
    private var mediaJob: Job? = null
    private var request: RequestId? = null
    private var activeMutation = false
    private val seen = mutableSetOf<CommandId>()

    private data class Save(val draft: EditorDraft, val revision: Long, val lease: MediaLease, val permit: WritePermit)

    init {
        sessionScope.launch {
            try { for (event in events) event() }
            finally {
                lease.close()
                pendingSave?.lease?.close()
                mediaJob?.cancel()
                owner?.let { audio.stop(it) }
            }
        }
        sessionScope.launch {
            audio.state.collect { value ->
                events.trySend {
                    if (value.owner?.id == owner && value.requestId == request && resumedAt == lifecycle.value) {
                        mutableState.value = state.value.copy(audio = value)
                    }
                }
            }
        }
    }

    fun send(command: AuthoringCommand): Boolean {
        if (state.value.ownerId != null && state.value.ownerId != command.ownerId) return false
        if (command.action == AuthoringAction.Background || command.action == AuthoringAction.Revoke) {
            lifecycle.update { it + 1 }
            audio.stop(command.ownerId)
        }
        return events.trySend { handle(command) }.isSuccess
    }

    private fun handle(command: AuthoringCommand) {
        if (state.value.phase == AuthoringPhase.CLOSED) return
        if (owner != null && command.ownerId != owner) { report(ProblemCode.UNAUTHORIZED); return }
        if (!seen.add(command.id)) return
        val action = command.action
        if (state.value.phase == AuthoringPhase.UNCONFIRMED) return
        if (action == AuthoringAction.Background || action == AuthoringAction.Revoke) {
            pause(); return
        }
        val protected = when (action) {
            is AuthoringAction.NewBoard -> ProtectedAction.CREATE_BOARD
            AuthoringAction.Apply -> ProtectedAction.APPLY_DRAFT
            AuthoringAction.DeleteDraft -> ProtectedAction.DELETE_DRAFT
            else -> ProtectedAction.EDIT_BOARD
        }
        when (val checked = gate.check(protected, command.capability, command.ownerId)) {
            is OperationResult.Completed -> Unit
            else -> { failure(checked); return }
        }
        if (owner == null) {
            owner = command.ownerId
            mutableState.value = state.value.copy(ownerId = owner)
        }
        capability = command.capability
        if (action == AuthoringAction.Resume) {
            if (state.value.phase != AuthoringPhase.PAUSED) return
            resumedAt = lifecycle.value
            mutableState.value = state.value.copy(phase = AuthoringPhase.EDITING, problem = null)
            if (revision != durableRevision) queueSave()
            return
        }
        if (state.value.phase == AuthoringPhase.PAUSED || resumedAt != lifecycle.value) {
            report(ProblemCode.UNAUTHORIZED); return
        }
        if (action == AuthoringAction.Close || action == AuthoringAction.ForceDiscard) {
            if (activeSave || activeMutation || (action == AuthoringAction.Close && revision != durableRevision)) {
                report(ProblemCode.UNFINISHED_FORM); return
            }
            close(); return
        }
        if (activeMutation || state.value.phase == AuthoringPhase.APPLIED) { report(ProblemCode.STALE_REQUEST); return }
        when (action) {
            is AuthoringAction.NewBoard -> {
                if (!canOpen()) return
                if (!validText(action.name)) return
                val board = BoardDocument(BoardId(ids.next()), action.name,
                    listOf(BoardPage(PageId(ids.next()), 3, 3, emptyList())))
                initialize(EditorDraft(DraftId(ids.next()), null, null, board), library.listing.value.epoch, null)
            }
            is AuthoringAction.EditBoard -> if (canOpen()) load {
                when (val result = library.board(action.boardId)) {
                    is OperationResult.Completed -> OperationResult.Completed(Loaded(
                        EditorDraft(DraftId(ids.next()), action.boardId, result.value.board.revision, result.value.board.document),
                        result.value.epoch, null, result.value.lease))
                    else -> result.asFailure()
                }
            }
            is AuthoringAction.ResumeDraft -> if (canOpen()) load {
                when (val result = library.draft(action.draftId)) {
                    is OperationResult.Completed -> OperationResult.Completed(Loaded(result.value.draft.draft,
                        result.value.epoch, result.value.draft, result.value.lease))
                    else -> result.asFailure()
                }
            }
            else -> {
                val draft = state.value.draft ?: return
                if (state.value.epoch != library.listing.value.epoch) { conflict(ProblemCode.STALE_REQUEST); return }
                when (action) {
                    is AuthoringAction.Details -> if (validText(action.name) && validText(action.description.orEmpty())) {
                        change(draft.copy(document = draft.document.copy(name = action.name,
                            description = action.description, behavior = action.behavior)))
                    }
                    is AuthoringAction.AddPage -> if (gridValid(action.rows, action.columns)) {
                        if (draft.document.pages.size == 256) report(ProblemCode.LIMIT_EXCEEDED)
                        else changeDocument(draft.document.copy(pages = draft.document.pages +
                            BoardPage(PageId(ids.next()), action.rows, action.columns, emptyList())))
                    }
                    is AuthoringAction.MovePage -> {
                        val pages = draft.document.pages.toMutableList()
                        val index = pages.indexOfFirst { it.id == action.pageId }
                        if (index < 0 || action.index !in pages.indices) { report(ProblemCode.INVALID_DATA); return }
                        pages.add(action.index, pages.removeAt(index))
                        changeDocument(draft.document.copy(pages = pages.toList()))
                    }
                    is AuthoringAction.RemovePage -> removePage(action.pageId)
                    is AuthoringAction.ResizeGrid -> resize(action)
                    is AuthoringAction.OpenGrid -> {
                        if (draft.form != null) { report(ProblemCode.UNFINISHED_FORM); return }
                        val page = draft.document.pages.firstOrNull { it.id == action.pageId } ?: return
                        change(draft.copy(form = EditorForm.Grid(page.id, page.rows.toString(), page.columns.toString())))
                    }
                    is AuthoringAction.ChangeGrid -> {
                        val form = draft.form as? EditorForm.Grid ?: return
                        if (validText(action.rowsText) && validText(action.columnsText)) {
                            change(draft.copy(form = form.copy(rowsText = action.rowsText, columnsText = action.columnsText)))
                        }
                    }
                    AuthoringAction.SaveGrid -> {
                        val form = draft.form as? EditorForm.Grid ?: return
                        val rows = form.rowsText.toIntOrNull()
                        val columns = form.columnsText.toIntOrNull()
                        if (rows == null || columns == null) { report(ProblemCode.INVALID_DATA); return }
                        resize(AuthoringAction.ResizeGrid(form.pageId, rows, columns), fromForm = true)
                    }
                    AuthoringAction.CancelGrid -> change(draft.copy(form = null))
                    is AuthoringAction.SetCell -> setCell(action)
                    is AuthoringAction.MoveCell -> moveCell(action)
                    is AuthoringAction.OpenCard -> openCard(action)
                    is AuthoringAction.ChangeCard -> {
                        val form = draft.form as? EditorForm.Card ?: return
                        if (validText(action.label) && validText(action.speechText)) {
                            cancelMedia()
                            change(draft.copy(form = form.copy(label = action.label, speechText = action.speechText,
                                speechChoice = action.speechChoice, useLabelForSpeech = action.useLabelForSpeech)))
                        }
                    }
                    AuthoringAction.SaveCard -> saveCard()
                    AuthoringAction.CancelCard -> { cancelMedia(); change(draft.copy(form = null)) }
                    AuthoringAction.RemoveImage -> {
                        val form = draft.form as? EditorForm.Card ?: return
                        cancelMedia(); change(draft.copy(form = form.copy(image = null)))
                    }
                    AuthoringAction.PickImage -> startMedia { preparePickedImage() }
                    is AuthoringAction.ImageFromText -> if (validText(action.text)) startMedia {
                        prepareImageResult(images.imageFromText(TextImageRequest(action.text)))
                    }
                    AuthoringAction.PickAudio -> startMedia { prepareAudio(files.pick(PickedFileKind.AUDIO, owner!!)) }
                    AuthoringAction.Record -> {
                        val id = RequestId(ids.next())
                        startMedia(id) { prepareAudio(audio.record(RecordingRequest(id, AudioOwner(owner!!, AudioPurpose.RECORDING)))) }
                    }
                    AuthoringAction.Synthesize -> {
                        val form = draft.form as? EditorForm.Card ?: return
                        val text = if (form.useLabelForSpeech) form.label else form.speechText
                        if (text.isBlank()) { report(ProblemCode.INVALID_DATA); return }
                        val id = RequestId(ids.next())
                        startMedia(id, synthesized = true) { prepareAudio(audio.synthesize(SynthesisRequest(id,
                            AudioOwner(owner!!, AudioPurpose.SYNTHESIS), text, sourceLanguage(form)))) }
                    }
                    AuthoringAction.FinishRecording -> request?.let { audio.finishRecording(owner!!, it) }
                    AuthoringAction.CancelMedia -> cancelMedia()
                    AuthoringAction.Preview -> preview()
                    AuthoringAction.StopPreview -> stopAudio()
                    AuthoringAction.RetrySave -> if (!activeSave) queueSave()
                    AuthoringAction.Apply -> apply()
                    AuthoringAction.DeleteDraft -> deleteDraft()
                    else -> Unit
                }
            }
        }
    }

    private fun canOpen(): Boolean {
        if (state.value.draft != null || state.value.phase == AuthoringPhase.LOADING) {
            report(ProblemCode.UNFINISHED_FORM); return false
        }
        return true
    }

    private data class Loaded(val draft: EditorDraft, val epoch: Long, val stored: StoredDraft?, val lease: MediaLease)

    private fun load(block: suspend () -> OperationResult<Loaded>) {
        val generation = ++loading
        val token = lifecycle.value
        val access = gate.guard(ProtectedAction.EDIT_BOARD, capability, owner!!)
        mutableState.value = state.value.copy(phase = AuthoringPhase.LOADING, problem = null)
        sessionScope.launch(Dispatchers.Default) {
            val result = operation(block)
            var transferred = false
            try {
                ensureActive()
                transferred = events.trySend {
                    if (generation != loading || token != lifecycle.value || state.value.phase == AuthoringPhase.CLOSED) {
                        if (result is OperationResult.Completed) result.value.lease.close()
                    } else when (result) {
                        is OperationResult.Completed -> {
                            val value = result.value
                            val checked = access()
                            if (checked !is OperationResult.Completed) {
                                value.lease.close(); pause(); failure(checked)
                            } else if (value.epoch != library.listing.value.epoch) {
                                value.lease.close(); conflict(ProblemCode.STALE_REQUEST)
                            } else {
                                lease.close(); lease = value.lease
                                initialize(value.draft, value.epoch, value.stored)
                            }
                        }
                        else -> { mutableState.value = state.value.copy(phase = AuthoringPhase.IDLE); failure(result) }
                    }
                }.isSuccess
            } finally { if (!transferred && result is OperationResult.Completed) result.value.lease.close() }
        }
    }

    private fun initialize(draft: EditorDraft, epoch: Long, stored: StoredDraft?) {
        durable = stored
        revision = 0
        durableRevision = if (stored == null) -1 else 0
        mutableState.value = state.value.copy(phase = AuthoringPhase.EDITING, draft = snapshot(draft), epoch = epoch,
            durableVersion = stored?.version, savePhase = if (stored == null) DraftSavePhase.UNSAVED else DraftSavePhase.SAVED,
            problem = null)
        if (stored == null) queueSave()
    }

    private fun snapshot(draft: EditorDraft): EditorDraft = draft.copy(document = draft.document.copy(
        pages = draft.document.pages.map { it.copy(cells = it.cells.toList()) }))

    private fun changeDocument(document: BoardDocument) {
        if (state.value.draft?.form != null) { report(ProblemCode.UNFINISHED_FORM); return }
        change(state.value.draft!!.copy(document = document))
    }

    private fun change(next: EditorDraft, extraLease: MediaLease? = null) {
        val retained = MessageLease.retainFrom(next.assetIds(), listOfNotNull(lease, extraLease))
        if (retained !is OperationResult.Completed) { failure(retained); return }
        val previous = lease
        lease = retained.value
        previous.close()
        revision++
        applyRequested = false
        mutableState.value = state.value.copy(draft = snapshot(next), savePhase = DraftSavePhase.SAVING)
        queueSave()
    }

    private fun permit(action: ProtectedAction): WritePermit {
        val token = lifecycle.value
        val authorizedGeneration = token == resumedAt
        val guard = gate.guard(action, capability, owner!!)
        return WritePermit(state.value.epoch!!, {
            if (token != lifecycle.value || !authorizedGeneration) OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
            else guard()
        })
    }

    private fun queueSave() {
        val draft = state.value.draft ?: return
        if (state.value.phase == AuthoringPhase.UNCONFIRMED || resumedAt != lifecycle.value) return
        when (val retained = lease.retain(draft.assetIds())) {
            is OperationResult.Completed -> {
                val save = Save(snapshot(draft), revision, retained.value, permit(
                    if (draft.boardId == null) ProtectedAction.CREATE_BOARD else ProtectedAction.EDIT_BOARD))
                mutableState.value = state.value.copy(savePhase = DraftSavePhase.SAVING)
                if (activeSave) {
                    pendingSave?.lease?.close()
                    pendingSave = save
                } else startSave(save)
            }
            else -> { mutableState.value = state.value.copy(savePhase = DraftSavePhase.FAILED); failure(retained) }
        }
    }

    private fun startSave(save: Save) {
        activeSave = true
        val expected = durable?.version
        var result: OperationResult<StoredDraft> = OperationResult.Cancelled
        val effect = sessionScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            result = operation { library.saveDraft(save.draft, expected, save.permit) }
        }
        effect.invokeOnCompletion {
            save.lease.close()
            events.trySend { saved(save, result) }
        }
        effect.start()
    }

    private fun saved(save: Save, result: OperationResult<StoredDraft>) {
        activeSave = false
        if (state.value.phase == AuthoringPhase.CLOSED) return
        when (result) {
            is OperationResult.Completed -> {
                if (result.value.draft != save.draft || result.value.version <= (durable?.version ?: 0)) {
                    freeze(); return
                }
                durable = result.value
                durableRevision = save.revision
                mutableState.value = state.value.copy(durableVersion = result.value.version,
                    savePhase = if (revision == save.revision) DraftSavePhase.SAVED else DraftSavePhase.SAVING)
                // A previously reported failure remains visible until an explicit successful retry/apply.
            }
            is OperationResult.Failed -> {
                mutableState.value = state.value.copy(savePhase = DraftSavePhase.FAILED, problem = result.problem)
                if (result.problem.code == ProblemCode.CONFLICT || result.problem.code == ProblemCode.STALE_REQUEST) {
                    conflict(result.problem.code)
                }
            }
            OperationResult.Cancelled -> {
                mutableState.value = state.value.copy(savePhase = DraftSavePhase.FAILED)
                report(ProblemCode.INTERRUPTED)
            }
            OperationResult.UnconfirmedCommit -> { freeze(); return }
        }
        if (state.value.epoch != library.listing.value.epoch) conflict(ProblemCode.STALE_REQUEST)
        val next = pendingSave
        pendingSave = null
        if (next != null) {
            if (result is OperationResult.Completed && state.value.phase != AuthoringPhase.PAUSED &&
                state.value.phase != AuthoringPhase.CONFLICT && resumedAt == lifecycle.value) startSave(next)
            else next.lease.close()
        }
        if (applyRequested && !activeSave && revision == durableRevision && result is OperationResult.Completed) apply()
    }

    private fun apply() {
        val draft = state.value.draft ?: return
        if (draft.form != null || state.value.mediaBusy) { report(ProblemCode.UNFINISHED_FORM); return }
        if (state.value.phase == AuthoringPhase.CONFLICT) { report(ProblemCode.CONFLICT); return }
        if (revision != durableRevision || activeSave) {
            applyRequested = true
            if (!activeSave) queueSave()
            return
        }
        val stored = durable ?: return
        val permit = permit(ProtectedAction.APPLY_DRAFT)
        applyRequested = false
        activeMutation = true
        mutableState.value = state.value.copy(phase = AuthoringPhase.APPLYING, problem = null)
        sessionScope.launch(Dispatchers.Default) {
            val result = operation { library.publishDraft(draft.id, stored.version, draft.baseRevision, permit) }
            events.trySend {
                activeMutation = false
                if (state.value.phase != AuthoringPhase.CLOSED) when (result) {
                    is OperationResult.Completed -> {
                        if (state.value.epoch != library.listing.value.epoch) conflict(ProblemCode.STALE_REQUEST)
                        else mutableState.value = state.value.copy(phase = AuthoringPhase.APPLIED, published = result.value, problem = null)
                    }
                    is OperationResult.Failed -> {
                        mutableState.value = state.value.copy(phase = if (resumedAt == lifecycle.value)
                            AuthoringPhase.EDITING else AuthoringPhase.PAUSED, problem = result.problem)
                        if (result.problem.code in setOf(ProblemCode.CONFLICT, ProblemCode.NOT_FOUND, ProblemCode.STALE_REQUEST))
                            conflict(ProblemCode.CONFLICT)
                    }
                    OperationResult.Cancelled -> {
                        mutableState.value = state.value.copy(phase = if (resumedAt == lifecycle.value)
                            AuthoringPhase.EDITING else AuthoringPhase.PAUSED)
                        report(ProblemCode.INTERRUPTED)
                    }
                    OperationResult.UnconfirmedCommit -> freeze()
                }
            }
        }
    }

    private fun deleteDraft() {
        val stored = durable ?: run { report(ProblemCode.UNFINISHED_FORM); return }
        if (activeSave || revision != durableRevision) { report(ProblemCode.UNFINISHED_FORM); return }
        activeMutation = true
        val permit = permit(ProtectedAction.DELETE_DRAFT)
        sessionScope.launch(Dispatchers.Default) {
            val result = operation { library.deleteDraft(stored.draft.id, stored.version, permit) }
            events.trySend {
                activeMutation = false
                when (result) {
                    is OperationResult.Completed -> close()
                    else -> failure(result)
                }
            }
        }
    }

    private fun pause() {
        cancelMedia()
        loading++
        applyRequested = false
        pendingSave?.lease?.close(); pendingSave = null
        mutableState.value = state.value.copy(phase = AuthoringPhase.PAUSED)
    }

    private fun close() {
        lifecycle.update { it + 1 }
        loading++
        cancelMedia()
        pendingSave?.lease?.close(); pendingSave = null
        lease.close()
        mutableState.value = state.value.copy(phase = AuthoringPhase.CLOSED)
        events.close()
        job.cancel()
    }

    private fun freeze() {
        lifecycle.update { it + 1 }
        cancelMedia()
        pendingSave?.lease?.close(); pendingSave = null
        applyRequested = false
        mutableState.value = state.value.copy(phase = AuthoringPhase.UNCONFIRMED,
            savePhase = DraftSavePhase.UNCONFIRMED, problem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
    }

    private fun conflict(code: ProblemCode) {
        applyRequested = false
        mutableState.value = state.value.copy(phase = AuthoringPhase.CONFLICT, problem = Problem(code))
    }

    private fun report(code: ProblemCode) { mutableState.value = state.value.copy(problem = Problem(code)) }
    private fun failure(result: OperationResult<*>) {
        when (result) {
            is OperationResult.Failed -> mutableState.value = state.value.copy(problem = result.problem)
            OperationResult.UnconfirmedCommit -> freeze()
            OperationResult.Cancelled -> report(ProblemCode.INTERRUPTED)
            is OperationResult.Completed -> Unit
        }
    }

    private fun removePage(id: PageId) {
        val document = state.value.draft!!.document
        val removed = document.pages.firstOrNull { it.id == id } ?: return
        if (document.pages.size == 1) { report(ProblemCode.INVALID_DATA); return }
        val pages = document.pages.filter { it.id != id }.toMutableList()
        var remaining = removed.cells.sortedBy { it.position }
        for (index in pages.indices) {
            val page = pages[index]
            val occupied = page.cells.map { it.position }.toSet()
            val free = (0 until page.capacity).filter { it !in occupied }
            val take = remaining.take(free.size)
            pages[index] = page.copy(cells = page.cells + take.mapIndexed { i, cell -> cell.copy(position = free[i]) })
            remaining = remaining.drop(take.size)
        }
        val extra = (remaining.size + removed.capacity - 1) / removed.capacity
        if (pages.size + extra > 256) { report(ProblemCode.LIMIT_EXCEEDED); return }
        remaining.chunked(removed.capacity).forEach { cells ->
            pages += BoardPage(PageId(ids.next()), removed.rows, removed.columns,
                cells.mapIndexed { i, cell -> cell.copy(position = i) })
        }
        changeDocument(document.copy(pages = pages.toList()))
    }

    private fun resize(action: AuthoringAction.ResizeGrid, fromForm: Boolean = false) {
        if (!fromForm && state.value.draft?.form != null) { report(ProblemCode.UNFINISHED_FORM); return }
        if (!gridValid(action.rows, action.columns)) return
        val document = state.value.draft!!.document
        val page = document.pages.firstOrNull { it.id == action.pageId } ?: return
        val capacity = action.rows * action.columns
        val overflow = page.cells.filter { it.position >= capacity }.sortedBy { it.position }
        val count = (overflow.size + capacity - 1) / capacity
        if (document.pages.size + count > 256) { report(ProblemCode.LIMIT_EXCEEDED); return }
        val pages = document.pages.toMutableList()
        val index = pages.indexOf(page)
        pages[index] = page.copy(rows = action.rows, columns = action.columns, cells = page.cells.filter { it.position < capacity })
        pages.addAll(index + 1, overflow.chunked(capacity).map { cells ->
            BoardPage(PageId(ids.next()), action.rows, action.columns, cells.mapIndexed { i, cell -> cell.copy(position = i) })
        })
        change(state.value.draft!!.copy(document = document.copy(pages = pages.toList()), form = null))
    }

    private fun setCell(action: AuthoringAction.SetCell) {
        if (action.kind == CellKind.CONTENT) { openCard(AuthoringAction.OpenCard(action.pageId, action.position)); return }
        val document = state.value.draft!!.document
        val page = document.pages.firstOrNull { it.id == action.pageId } ?: return
        if (action.position !in 0 until page.capacity) { report(ProblemCode.INVALID_DATA); return }
        val old = page.cells.firstOrNull { it.position == action.position }
        val cell = BoardCell(action.position, action.kind, extensionsJson = old?.extensionsJson ?: "{}")
        changeDocument(document.copy(pages = document.pages.map {
            if (it.id == page.id) it.copy(cells = it.cells.filter { it.position != action.position } + cell) else it
        }))
    }

    private fun moveCell(action: AuthoringAction.MoveCell) {
        val document = state.value.draft!!.document
        val from = document.pages.firstOrNull { it.id == action.fromPage } ?: return
        val to = document.pages.firstOrNull { it.id == action.toPage } ?: return
        if (action.from !in 0 until from.capacity || action.to !in 0 until to.capacity) {
            report(ProblemCode.INVALID_DATA); return
        }
        val a = from.cells.firstOrNull { it.position == action.from }
        val b = to.cells.firstOrNull { it.position == action.to }
        val pages = document.pages.map { page ->
            val cells = page.cells.filterNot {
                (page.id == from.id && it.position == action.from) || (page.id == to.id && it.position == action.to)
            }.toMutableList()
            if (page.id == from.id && b != null) cells += b.copy(position = action.from)
            if (page.id == to.id && a != null && (from.id != to.id || action.from != action.to)) cells += a.copy(position = action.to)
            page.copy(cells = cells.toList())
        }
        changeDocument(document.copy(pages = pages))
    }

    private fun openCard(action: AuthoringAction.OpenCard) {
        val draft = state.value.draft!!
        if (draft.form != null) { report(ProblemCode.UNFINISHED_FORM); return }
        val page = draft.document.pages.firstOrNull { it.id == action.pageId } ?: return
        if (action.position !in 0 until page.capacity) { report(ProblemCode.INVALID_DATA); return }
        cancelMedia()
        val card = page.cells.firstOrNull { it.position == action.position }?.card
        val speech = card?.speech
        val form = EditorForm.Card(page.id, action.position, card?.id ?: CardId(ids.next()),
            label = card?.label.orEmpty(), speechText = (speech as? SpeechSource.Tts)?.text.orEmpty(),
            speechChoice = when (speech) {
                is SpeechSource.Recording -> SpeechChoice.RECORDING
                SpeechSource.None -> SpeechChoice.NONE
                else -> SpeechChoice.TTS
            }, useLabelForSpeech = speech !is SpeechSource.Tts || speech.text == card.label,
            image = card?.image, recording = (speech as? SpeechSource.Recording)?.assetId,
            audioText = (speech as? SpeechSource.Recording)?.audioText,
            audioVoice = (speech as? SpeechSource.Recording)?.audioVoice,
            extensionsJson = card?.extensionsJson ?: "{}")
        change(draft.copy(form = form))
    }

    private fun sourceLanguage(form: EditorForm.Card): String? =
        (state.value.draft?.document?.pages?.flatMap { it.cells }?.firstOrNull { it.card?.id == form.cardId }
            ?.card?.speech as? SpeechSource.Tts)?.languageTag

    private fun saveCard() {
        val draft = state.value.draft!!
        val form = draft.form as? EditorForm.Card ?: return
        if (state.value.mediaBusy) { report(ProblemCode.UNFINISHED_FORM); return }
        val speech = when (form.speechChoice) {
            SpeechChoice.TTS -> SpeechSource.Tts(if (form.useLabelForSpeech) form.label else form.speechText, sourceLanguage(form))
            SpeechChoice.RECORDING -> SpeechSource.Recording(form.recording ?: run {
                report(ProblemCode.MISSING_ASSET); return
            }, form.audioText, form.audioVoice)
            SpeechChoice.NONE -> SpeechSource.None
        }
        val page = draft.document.pages.firstOrNull { it.id == form.pageId } ?: run { report(ProblemCode.CONFLICT); return }
        val original = page.cells.firstOrNull { it.position == form.slotIndex }
        if (original?.card == null && draft.document.pages.sumOf { p -> p.cells.count { it.kind == CellKind.CONTENT } } >= 100000) {
            report(ProblemCode.LIMIT_EXCEEDED); return
        }
        val cell = BoardCell(form.slotIndex, CellKind.CONTENT,
            CardDocument(form.cardId, form.label, form.image, speech, form.extensionsJson), original?.extensionsJson ?: "{}")
        cancelMedia()
        change(draft.copy(form = null, document = draft.document.copy(pages = draft.document.pages.map {
            if (it.id == page.id) it.copy(cells = it.cells.filter { it.position != cell.position } + cell) else it
        })))
    }

    private fun stopAudio() {
        val old = request
        request = null
        owner?.let { audio.stop(it, old) }
        mutableState.value = state.value.copy(audio = AudioState())
    }

    private fun cancelMedia() {
        mediaGeneration++
        stopAudio()
        mediaJob?.cancel(); mediaJob = null
        mutableState.value = state.value.copy(mediaBusy = false)
    }

    private fun startMedia(id: RequestId? = null, synthesized: Boolean = false, block: suspend () -> OperationResult<PreparedMedia>) {
        val form = state.value.draft?.form as? EditorForm.Card ?: return
        cancelMedia()
        val generation = mediaGeneration
        val token = lifecycle.value
        val access = gate.guard(ProtectedAction.EDIT_BOARD, capability, owner!!)
        request = id
        mutableState.value = state.value.copy(mediaBusy = true)
        mediaJob = sessionScope.launch(Dispatchers.Default) {
            val result = operation(block)
            var transferred = false
            try {
                ensureActive()
                transferred = events.trySend {
                    if (generation != mediaGeneration || token != lifecycle.value ||
                        state.value.draft?.form != form || state.value.phase == AuthoringPhase.CLOSED) {
                        if (result is OperationResult.Completed) result.value.lease.close()
                    } else {
                        mutableState.value = state.value.copy(mediaBusy = false)
                        mediaJob = null
                        when (result) {
                            is OperationResult.Completed -> {
                                val prepared = result.value
                                try {
                                    val checked = access()
                                    if (checked !is OperationResult.Completed) { pause(); failure(checked); return@trySend }
                                    val next = if (prepared.asset.kind == MediaKind.IMAGE) form.copy(image = prepared.asset.id)
                                    else form.copy(recording = prepared.asset.id, speechChoice = SpeechChoice.RECORDING,
                                        audioText = if (!synthesized) null else if (form.useLabelForSpeech) form.label else form.speechText,
                                        audioVoice = null)
                                    change(state.value.draft!!.copy(form = next), prepared.lease)
                                } finally { prepared.lease.close() }
                            }
                            else -> failure(result)
                        }
                    }
                }.isSuccess
            } finally { if (!transferred && result is OperationResult.Completed) result.value.lease.close() }
        }
    }

    private suspend fun preparePickedImage(): OperationResult<PreparedMedia> {
        val picked = files.pick(PickedFileKind.IMAGE, owner!!)
        if (picked !is OperationResult.Completed) return picked.asFailure()
        return try {
            // Validate the original before handing it to the image preparation decoder as well.
            when (val info = inspection.image(picked.value)) {
                is OperationResult.Completed -> if (!imageWithinBounds(info.value)) OperationResult.Failed(Problem(ProblemCode.LIMIT_EXCEEDED))
                    else prepareImageResult(images.prepareImage(picked.value))
                else -> info.asFailure()
            }
        } finally { files.discard(picked.value) }
    }

    private suspend fun prepareImageResult(result: OperationResult<TemporaryFile>): OperationResult<PreparedMedia> {
        if (result !is OperationResult.Completed) return result.asFailure()
        return try {
            when (val info = inspection.image(result.value)) {
                is OperationResult.Completed -> if (!imageWithinBounds(info.value)) OperationResult.Failed(Problem(ProblemCode.LIMIT_EXCEEDED))
                    else prepareFile(result.value, MediaKind.IMAGE, info.value.mediaType)
                else -> info.asFailure()
            }
        } finally { files.discard(result.value) }
    }

    private fun imageWithinBounds(info: ImageInfo): Boolean =
        info.width <= 8192 && info.height <= 8192 && info.width.toLong() * info.height <= 16_000_000

    private suspend fun prepareAudio(result: OperationResult<TemporaryFile>): OperationResult<PreparedMedia> {
        if (result !is OperationResult.Completed) return result.asFailure()
        return try {
            when (val info = inspection.audio(result.value)) {
                is OperationResult.Completed -> if (info.value.durationMillis > 600_000) OperationResult.Failed(Problem(ProblemCode.LIMIT_EXCEEDED))
                    else prepareFile(result.value, MediaKind.AUDIO, info.value.mediaType)
                else -> info.asFailure()
            }
        } finally { files.discard(result.value) }
    }

    private suspend fun prepareFile(file: TemporaryFile, kind: MediaKind, type: String): OperationResult<PreparedMedia> {
        val source = files.source(file)
        if (source !is OperationResult.Completed) return source.asFailure()
        val result = media.prepare(source.value, kind, type)
        if (result is OperationResult.Completed) {
            val value = result.value
            if (value.asset.kind != kind || value.asset.id !in value.lease.assets || value.asset.byteCount <= 0 ||
                value.asset.byteCount > 64L * 1024 * 1024) {
                value.lease.close()
                return OperationResult.Failed(Problem(ProblemCode.INVALID_DATA))
            }
            try { currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { value.lease.close(); throw cancelled }
        }
        return result
    }

    private fun preview() {
        val form = state.value.draft?.form as? EditorForm.Card ?: return
        if (state.value.mediaBusy) { report(ProblemCode.UNFINISHED_FORM); return }
        stopAudio()
        val segment = when (form.speechChoice) {
            SpeechChoice.NONE -> return
            SpeechChoice.TTS -> SpeechSegment.Text(if (form.useLabelForSpeech) form.label else form.speechText, sourceLanguage(form))
            SpeechChoice.RECORDING -> SpeechSegment.Recording(form.recording ?: run { report(ProblemCode.MISSING_ASSET); return })
        }
        val assets = if (segment is SpeechSegment.Recording) setOf(segment.assetId) else emptySet()
        when (val retained = lease.retain(assets)) {
            is OperationResult.Completed -> {
                val id = RequestId(ids.next())
                request = id
                val audioOwner = AudioOwner(owner!!, AudioPurpose.PREVIEW)
                mutableState.value = state.value.copy(audio = AudioState(id, audioOwner, AudioPhase.PREPARING))
                audio.play(PlaybackRequest(id, audioOwner,
                    SpeechPlan(listOf(segment), if (form.useLabelForSpeech) form.label else form.speechText), retained.value))
            }
            else -> failure(retained)
        }
    }

    private fun validText(text: String): Boolean {
        if (text.length > 65536) { report(ProblemCode.LIMIT_EXCEEDED); return false }
        if (!isNativeText(text)) { report(ProblemCode.INVALID_DATA); return false }
        return true
    }

    private fun gridValid(rows: Int, columns: Int): Boolean {
        if (rows !in 1..64 || columns !in 1..64) { report(ProblemCode.LIMIT_EXCEEDED); return false }
        return true
    }

    private suspend fun <T> operation(block: suspend () -> OperationResult<T>): OperationResult<T> = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) }

    private fun <T> OperationResult<*>.asFailure(): OperationResult<T> = when (this) {
        is OperationResult.Failed -> this
        OperationResult.Cancelled -> OperationResult.Cancelled
        OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
        is OperationResult.Completed -> error("Expected failure")
    }
}
