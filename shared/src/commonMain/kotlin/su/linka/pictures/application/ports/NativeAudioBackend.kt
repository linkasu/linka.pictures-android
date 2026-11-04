package su.linka.pictures.application.ports

import kotlinx.coroutines.flow.StateFlow
import su.linka.pictures.application.OperationResult
import su.linka.pictures.domain.RequestId

// The shared coordinator owns sequencing, request replacement and media leases.
internal interface NativeAudioBackend {
    val voices: StateFlow<VoiceCapability>
    suspend fun inspectClip(file: LocalFile): OperationResult<Unit>
    suspend fun playClip(file: LocalFile, requestId: RequestId): OperationResult<Unit>
    suspend fun speak(text: String, languageTag: String?, voiceId: String?, requestId: RequestId): OperationResult<Unit>
    suspend fun synthesize(request: SynthesisRequest): OperationResult<TemporaryFile>
    suspend fun record(request: RecordingRequest, onPhase: (AudioPhase) -> Unit): OperationResult<TemporaryFile>
    fun finishRecording(requestId: RequestId)
    fun stop(requestId: RequestId)
}
