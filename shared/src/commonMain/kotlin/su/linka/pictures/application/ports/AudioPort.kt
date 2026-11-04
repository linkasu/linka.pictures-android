package su.linka.pictures.application.ports

import kotlinx.coroutines.flow.StateFlow
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.Problem
import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.RequestId

enum class AudioPurpose { COMMUNICATION, DIRECT, PREVIEW, RECORDING, SYNTHESIS }
data class AudioOwner(val id: String, val purpose: AudioPurpose)

sealed interface SpeechSegment {
    data class Recording(val assetId: AssetId) : SpeechSegment
    data class Text(val text: String, val languageTag: String? = null) : SpeechSegment
}

data class SpeechPlan(val segments: List<SpeechSegment>, val renderedText: String)
data class PlaybackRequest(
    val id: RequestId,
    val owner: AudioOwner,
    val plan: SpeechPlan,
    val lease: MediaLease,
    val preferredVoiceId: String? = null,
)

data class SynthesisRequest(
    val id: RequestId,
    val owner: AudioOwner,
    val text: String,
    val languageTag: String?,
    val preferredVoiceId: String? = null,
)

data class RecordingRequest(val id: RequestId, val owner: AudioOwner)
enum class VoiceStatus { INITIALIZING, LOCAL_VOICE_READY, MISSING_VOICE, UNSUPPORTED_LANGUAGE, INTERRUPTED, FAILED }
data class LocalVoice(val id: String, val languageTag: String, val name: String)
data class VoiceCapability(val status: VoiceStatus, val voices: List<LocalVoice> = emptyList(), val maxTextLength: Int = 4000)

enum class AudioPhase {
    IDLE, PREPARING, PLAYING, REQUESTING_PERMISSION, RECORDING, FINISHING, SYNTHESIZING,
    READY, COMPLETED, CANCELLED, INTERRUPTED, FAILED,
}

// Progress is ephemeral and intentionally contains neither utterance text nor owned file handles.
data class AudioState(
    val requestId: RequestId? = null,
    val owner: AudioOwner? = null,
    val phase: AudioPhase = AudioPhase.IDLE,
    val segmentIndex: Int = 0,
    val problem: Problem? = null,
)

interface AudioPort {
    val state: StateFlow<AudioState>
    val voices: StateFlow<VoiceCapability>
    fun play(request: PlaybackRequest)
    fun stop(ownerId: String, requestId: RequestId? = null)
    fun interrupt()
    suspend fun synthesize(request: SynthesisRequest): OperationResult<TemporaryFile>
    suspend fun record(request: RecordingRequest): OperationResult<TemporaryFile>
    fun finishRecording(ownerId: String, requestId: RequestId)
}
