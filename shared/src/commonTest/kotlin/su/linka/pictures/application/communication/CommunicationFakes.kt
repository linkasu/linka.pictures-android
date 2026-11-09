package su.linka.pictures.application.communication

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Source
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal fun <T> unexpected(): T = error("Unexpected port operation in communication test")

internal class FakeMedia : MediaStore {
    val existing = mutableSetOf<AssetId>()
    val refs = mutableMapOf<AssetId, Int>()
    var openLeases = 0
        private set
    var onRetain: (() -> Unit)? = null
    var roots: () -> Set<AssetId> = { emptySet() }
    override suspend fun acquire(assets: Set<AssetId>): OperationResult<MediaLease> =
        if (existing.containsAll(assets)) OperationResult.Completed(Lease(assets))
        else OperationResult.Failed(Problem(ProblemCode.MISSING_ASSET))

    inner class Lease(override val assets: Set<AssetId>) : MediaLease {
        private var closed = false
        init {
            openLeases++
            assets.forEach { refs[it] = (refs[it] ?: 0) + 1 }
        }
        override fun retain(assets: Set<AssetId>): OperationResult<MediaLease> {
            check(!closed && this.assets.containsAll(assets) && existing.containsAll(assets))
            val lease = Lease(assets)
            onRetain?.invoke()
            return OperationResult.Completed(lease)
        }
        override fun close() {
            if (!closed) {
                closed = true
                openLeases--
                assets.forEach { refs[it] = refs.getValue(it) - 1 }
            }
        }
    }

    override suspend fun collectGarbage(): OperationResult<GarbageCollectionReport> {
        val garbage = existing.filter { (refs[it] ?: 0) == 0 && it !in roots() }
        existing.removeAll(garbage.toSet())
        return OperationResult.Completed(GarbageCollectionReport(garbage.size, 0, 0))
    }
    override suspend fun prepare(source: Source, kind: MediaKind, mediaType: String): OperationResult<PreparedMedia> = unexpected()
    override suspend fun metadata(id: AssetId): OperationResult<MediaAsset> = unexpected()
    override fun file(id: AssetId, lease: MediaLease): OperationResult<LocalFile> = unexpected()
    override fun source(id: AssetId, lease: MediaLease): OperationResult<Source> = unexpected()
}

internal class FakeMessages : MessageStore {
    var stored = StoredMessage(0, 0, MessageDraft())
    var loadFailure: Problem? = null
    var delayed = false
    data class Pending(val value: StoredMessage, val result: CompletableDeferred<OperationResult<Unit>>)
    val pending = mutableListOf<Pending>()
    val attempts = mutableListOf<StoredMessage>()
    override suspend fun message(): OperationResult<StoredMessage> = loadFailure?.let { OperationResult.Failed(it) } ?: OperationResult.Completed(stored)
    override suspend fun saveMessage(value: StoredMessage): OperationResult<Unit> {
        attempts += value
        if (delayed) {
            val request = Pending(value, CompletableDeferred())
            pending += request
            val result = request.result.await()
            if (result !is OperationResult.Completed) return result
        }
        if (value.epoch != stored.epoch) return OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
        if (value.version < stored.version || (value.version == stored.version && value != stored)) return OperationResult.Failed(Problem(ProblemCode.CONFLICT))
        stored = value
        return OperationResult.Completed(Unit)
    }
}

internal class FakeAudio : AudioPort {
    override val state = MutableStateFlow(AudioState())
    override val voices = MutableStateFlow(VoiceCapability(VoiceStatus.LOCAL_VOICE_READY))
    val played = mutableListOf<PlaybackRequest>()
    private val owned = mutableMapOf<RequestId, PlaybackRequest>()
    val stopped = mutableListOf<RequestId?>()
    override fun play(request: PlaybackRequest) {
        played += request
        owned[request.id] = request
        state.value = AudioState(request.id, request.owner, AudioPhase.PLAYING)
    }
    override fun stop(ownerId: String, requestId: RequestId?) {
        stopped += requestId
        owned.values.filter { it.owner.id == ownerId && (requestId == null || it.id == requestId) }.toList().forEach {
            owned.remove(it.id)?.lease?.close()
        }
    }
    fun callback(request: PlaybackRequest, phase: AudioPhase) {
        if (phase in setOf(AudioPhase.COMPLETED, AudioPhase.FAILED, AudioPhase.CANCELLED)) owned.remove(request.id)?.lease?.close()
        state.value = AudioState(request.id, request.owner, phase)
    }
    override fun interrupt() = unexpected<Unit>()
    override suspend fun synthesize(request: SynthesisRequest): OperationResult<TemporaryFile> = unexpected()
    override suspend fun record(request: RecordingRequest): OperationResult<TemporaryFile> = unexpected()
    override fun finishRecording(ownerId: String, requestId: RequestId) = unexpected<Unit>()
}

internal class FakeLibrary(private val media: FakeMedia) : LibraryStore {
    override val listing = MutableStateFlow(LibraryListing(0, 0, emptyList()))
    val boards = mutableMapOf<BoardId, PublishedBoard>()
    val delayed = mutableMapOf<BoardId, CompletableDeferred<Unit>>()
    val ignoresCancellation = mutableSetOf<BoardId>()
    var beforeReadResult: (() -> Unit)? = null
    override suspend fun board(id: BoardId): OperationResult<LeasedBoard> {
        if (id in ignoresCancellation) return withContext(NonCancellable) { readBoard(id) }
        return readBoard(id)
    }
    private suspend fun readBoard(id: BoardId): OperationResult<LeasedBoard> {
        delayed[id]?.await()
        val board = boards[id] ?: return OperationResult.Failed(Problem(ProblemCode.NOT_FOUND))
        return when (val lease = media.acquire(board.document.assetIds())) {
            is OperationResult.Completed -> {
                beforeReadResult?.invoke()
                OperationResult.Completed(LeasedBoard(listing.value.epoch, board, lease.value))
            }
            is OperationResult.Failed -> lease
            OperationResult.Cancelled -> OperationResult.Cancelled
            OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
        }
    }
    override suspend fun draft(id: DraftId): OperationResult<LeasedDraft> = unexpected()
    override suspend fun drafts(): OperationResult<List<DraftSummary>> = unexpected()
    override suspend fun saveDraft(value: EditorDraft, expectedVersion: Long?, permit: WritePermit): OperationResult<StoredDraft> = unexpected()
    override suspend fun deleteDraft(id: DraftId, expectedVersion: Long, permit: WritePermit): OperationResult<Unit> = unexpected()
    override suspend fun publishDraft(id: DraftId, expectedDraftVersion: Long, expectedBoardRevision: Long?, permit: WritePermit): OperationResult<PublishedBoard> = unexpected()
    override suspend fun addBoard(value: BoardDocument, permit: WritePermit): OperationResult<PublishedBoard> = unexpected()
    override suspend fun replaceBoard(value: BoardDocument, expectedRevision: Long, permit: WritePermit): OperationResult<PublishedBoard> = unexpected()
    override suspend fun renameBoard(id: BoardId, name: String, expectedRevision: Long, permit: WritePermit): OperationResult<PublishedBoard> = unexpected()
    override suspend fun deleteBoard(id: BoardId, expectedRevision: Long, permit: WritePermit): OperationResult<Unit> = unexpected()
    override suspend fun retainArchive(value: RetainedArchive, permit: WritePermit): OperationResult<Unit> = unexpected()
    override suspend fun settings(): OperationResult<PortableSettings> = unexpected()
    override suspend fun saveSettings(value: PortableSettings, expectedLibraryRevision: Long, permit: WritePermit): OperationResult<Long> = unexpected()
    override suspend fun saveOrganization(value: LibraryOrganization, expectedLibraryRevision: Long, permit: WritePermit): OperationResult<Long> = unexpected()
    override suspend fun snapshot(): OperationResult<SnapshotReader> = unexpected()
    override suspend fun createSnapshotBuilder(): OperationResult<SnapshotBuilder> = unexpected()
    override suspend fun replaceLibrary(value: SnapshotReader, expectedLibraryRevision: Long, expectedMessage: MessageStamp, permit: WritePermit): OperationResult<RecoveryInfo> = unexpected()
    override suspend fun recovery(): OperationResult<RecoveryInfo?> = unexpected()
    override suspend fun returnToRecovery(id: RecoveryId, expectedLibraryRevision: Long, expectedMessage: MessageStamp, permit: WritePermit): OperationResult<RecoveryInfo> = unexpected()
    override suspend fun deleteRecovery(id: RecoveryId, permit: WritePermit): OperationResult<Unit> = unexpected()
}
