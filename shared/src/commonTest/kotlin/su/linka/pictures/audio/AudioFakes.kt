package su.linka.pictures.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Sink
import okio.Source
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal fun <T> unused(): T = error("Unexpected audio test port call")

internal class AudioFilesFake : PlatformFilesPort {
    val discarded = mutableListOf<TemporaryFile>()
    val discardAttempts = mutableListOf<TemporaryFile>()
    var discardAction: (TemporaryFile) -> Unit = {}
    override fun discard(file: TemporaryFile) {
        check(file !in discardAttempts) { "Double discard" }
        discardAttempts += file
        discardAction(file)
        discarded += file
    }
    override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> = unused()
    override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> = unused()
    override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> = unused()
    override fun source(file: LocalFile): OperationResult<Source> = unused()
    override fun sink(file: TemporaryFile): OperationResult<Sink> = unused()
}

internal class AudioMediaFake : MediaStore {
    val clips = mutableMapOf<AssetId, LocalFile>()
    var failure: OperationResult<LocalFile>? = null
    var open = 0
    var closeFailure: (() -> Unit)? = null
    var closeAttempts = 0
    inner class Lease(override val assets: Set<AssetId>) : MediaLease {
        var closed = false
        init { open++ }
        override fun retain(assets: Set<AssetId>): OperationResult<MediaLease> {
            check(!closed && this.assets.containsAll(assets))
            return OperationResult.Completed(Lease(assets))
        }
        override fun close() {
            if (!closed) {
                closeAttempts++
                closed = true
                open--
                closeFailure?.invoke()
            }
        }
    }
    override fun file(id: AssetId, lease: MediaLease): OperationResult<LocalFile> {
        check(lease is Lease && !lease.closed && id in lease.assets)
        return failure ?: clips[id]?.let { OperationResult.Completed(it) } ?: OperationResult.Failed(Problem(ProblemCode.MISSING_ASSET))
    }
    override suspend fun prepare(source: Source, kind: MediaKind, mediaType: String): OperationResult<PreparedMedia> = unused()
    override suspend fun acquire(assets: Set<AssetId>): OperationResult<MediaLease> = unused()
    override suspend fun metadata(id: AssetId): OperationResult<MediaAsset> = unused()
    override fun source(id: AssetId, lease: MediaLease): OperationResult<Source> = unused()
    override suspend fun collectGarbage(): OperationResult<GarbageCollectionReport> = unused()
}

internal class NativeAudioFake : NativeAudioBackend {
    override val voices = MutableStateFlow(VoiceCapability(VoiceStatus.LOCAL_VOICE_READY, listOf(LocalVoice("ru", "ru-RU", "Russian")), 4000))
    val calls = mutableListOf<String>()
    val inspected = mutableListOf<LocalFile>()
    val stopped = mutableListOf<RequestId>()
    val finished = mutableListOf<RequestId>()
    var stopAction: (RequestId) -> Unit = {}
    var finishAction: (RequestId) -> Unit = {}
    var inspect: suspend (LocalFile) -> OperationResult<Unit> = { OperationResult.Completed(Unit) }
    var audible: suspend (RequestId) -> OperationResult<Unit> = { OperationResult.Completed(Unit) }
    var recording: suspend (RecordingRequest, (AudioPhase) -> Unit) -> OperationResult<TemporaryFile> = { _, _ -> unused() }
    var synthesis: suspend (SynthesisRequest) -> OperationResult<TemporaryFile> = { unused() }
    var nativeActive = 0
    var peakNativeActive = 0
    private suspend fun <T> entered(block: suspend () -> T): T {
        nativeActive++
        peakNativeActive = maxOf(peakNativeActive, nativeActive)
        try { return block() } finally { nativeActive-- }
    }
    override suspend fun inspectClip(file: LocalFile): OperationResult<Unit> {
        inspected += file
        return inspect(file)
    }
    override suspend fun playClip(file: LocalFile, requestId: RequestId): OperationResult<Unit> = entered {
        calls += "clip:${file.path}"
        audible(requestId)
    }
    override suspend fun speak(text: String, languageTag: String?, voiceId: String?, requestId: RequestId): OperationResult<Unit> = entered {
        calls += "tts:$text:$languageTag:$voiceId"
        audible(requestId)
    }
    override suspend fun synthesize(request: SynthesisRequest): OperationResult<TemporaryFile> = entered {
        calls += "synth:${request.text}"
        synthesis(request)
    }
    override suspend fun record(request: RecordingRequest, onPhase: (AudioPhase) -> Unit): OperationResult<TemporaryFile> = entered {
        calls += "record"
        recording(request, onPhase)
    }
    override fun finishRecording(requestId: RequestId) { finished += requestId; finishAction(requestId) }
    override fun stop(requestId: RequestId) { stopped += requestId; stopAction(requestId) }
}
