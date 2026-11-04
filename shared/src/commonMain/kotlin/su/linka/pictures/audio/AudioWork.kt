package su.linka.pictures.audio

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.flow.MutableStateFlow
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId

internal class AudioWork(
    val id: RequestId,
    val owner: AudioOwner,
    val playback: PlaybackRequest? = null,
    val recording: RecordingRequest? = null,
    val synthesis: SynthesisRequest? = null,
    private val continuation: CancellableContinuation<OperationResult<TemporaryFile>>? = null,
    private val files: PlatformFilesPort,
) {
    val token = Any()
    private enum class Life { PENDING, RUNNING, CANCELLED, ENDED }
    private val life = MutableStateFlow(Life.PENDING)
    private val replied = MutableStateFlow(false)
    val phase = MutableStateFlow(AudioState(id, owner, when {
        recording != null -> AudioPhase.REQUESTING_PERMISSION
        synthesis != null -> AudioPhase.SYNTHESIZING
        else -> AudioPhase.PREPARING
    }))
    val voiceSnapshot = MutableStateFlow<VoiceCapability?>(null)
    val outcome = MutableStateFlow<OperationResult<TemporaryFile?>?>(null)
    val cancelled get() = life.value == Life.CANCELLED
    fun start() = life.compareAndSet(Life.PENDING, Life.RUNNING)

    fun cancel(result: OperationResult<Nothing>) {
        while (true) {
            val previous = life.value
            if (previous == Life.CANCELLED || previous == Life.ENDED) return
            if (life.compareAndSet(previous, Life.CANCELLED)) {
                if (previous == Life.PENDING) playback?.lease?.close()
                reply(result)
                return
            }
        }
    }

    fun reply(result: OperationResult<TemporaryFile?>) {
        if (!replied.compareAndSet(false, true)) {
            discard(result)
            return
        }
        life.compareAndSet(Life.RUNNING, Life.ENDED)
        val target = continuation ?: return
        val delivered: OperationResult<TemporaryFile> = when (result) {
            is OperationResult.Completed -> OperationResult.Completed(checkNotNull(result.value))
            is OperationResult.Failed -> result
            OperationResult.Cancelled -> OperationResult.Cancelled
            OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
        }
        // Cancellation between resume and dispatch must dispose the undelivered file, not a caller-owned one.
        target.resume(delivered) { _, value, _ ->
            if (value is OperationResult.Completed) files.discard(value.value)
        }
    }

    fun takeOutcome(): OperationResult<TemporaryFile?>? {
        while (true) {
            val value = outcome.value ?: return null
            if (outcome.compareAndSet(value, null)) return value
        }
    }

    fun discard(result: OperationResult<TemporaryFile?>?) {
        if (result is OperationResult.Completed) result.value?.let(files::discard)
    }
}
