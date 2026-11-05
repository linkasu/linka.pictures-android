package su.linka.pictures.application.authoring

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okio.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

// Deliberately fake native boundaries; these do not prove OS permissions, decoders or recording.
internal class AuthoringNativeFakes : PlatformFilesPort, ImagePreparationPort, MediaInspectionPort, AudioPort, AssistantGate {
    override val state = MutableStateFlow(AudioState())
    override val voices = MutableStateFlow(VoiceCapability(VoiceStatus.LOCAL_VOICE_READY))
    val discarded = MutableStateFlow<Set<String>>(emptySet())
    var pickResult: CompletableDeferred<OperationResult<TemporaryFile>>? = null
    val pickEntered = CompletableDeferred<Unit>()
    var ignorePickCancellation = false
    var authorized = true
    var throwOnSource = false
    var audioDuration = 1000L
    var imageSize = ImageInfo(100, 100, "image/png")
    val played = mutableListOf<PlaybackRequest>()
    var recording: CompletableDeferred<OperationResult<TemporaryFile>>? = null
    private var serial = 0
    fun temporary() = TemporaryFile("/fake-authoring-owned/${serial++}")
    override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> {
        pickEntered.complete(Unit)
        val result = pickResult ?: return OperationResult.Completed(temporary())
        return if (ignorePickCancellation) withContext(NonCancellable) { result.await() } else result.await()
    }
    override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> = error("Not used")
    override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> = OperationResult.Completed(temporary())
    override fun source(file: LocalFile): OperationResult<Source> {
        if (throwOnSource) error("Fake IO failure")
        return OperationResult.Completed(Buffer().writeUtf8("fake bytes ${file.path}"))
    }
    override fun sink(file: TemporaryFile): OperationResult<Sink> = OperationResult.Completed(Buffer())
    override fun discard(file: TemporaryFile) { discarded.value = discarded.value + file.path }
    override suspend fun prepareImage(file: LocalFile): OperationResult<TemporaryFile> = OperationResult.Completed(temporary())
    override suspend fun imageFromText(request: TextImageRequest): OperationResult<TemporaryFile> = OperationResult.Completed(temporary())
    override suspend fun image(file: LocalFile): OperationResult<ImageInfo> = OperationResult.Completed(imageSize)
    override suspend fun audio(file: LocalFile): OperationResult<AudioInfo> = OperationResult.Completed(AudioInfo(audioDuration, "audio/wav"))
    override fun play(request: PlaybackRequest) {
        played += request
        state.value = AudioState(request.id, request.owner, AudioPhase.PLAYING)
    }
    override fun stop(ownerId: String, requestId: RequestId?) {
        played.filter { it.owner.id == ownerId && (requestId == null || it.id == requestId) }.forEach { it.lease.close() }
        state.value = AudioState()
    }
    override fun interrupt() { state.value.owner?.let { stop(it.id) } }
    override suspend fun synthesize(request: SynthesisRequest): OperationResult<TemporaryFile> = OperationResult.Completed(temporary())
    override suspend fun record(request: RecordingRequest): OperationResult<TemporaryFile> {
        state.value = AudioState(request.id, request.owner, AudioPhase.RECORDING)
        return recording?.await() ?: OperationResult.Cancelled
    }
    override fun finishRecording(ownerId: String, requestId: RequestId) { recording?.complete(OperationResult.Completed(temporary())) }
    override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): OperationResult<Unit> =
        if (authorized) OperationResult.Completed(Unit) else OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
    override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard = { check(action, capability, ownerId) }
}
