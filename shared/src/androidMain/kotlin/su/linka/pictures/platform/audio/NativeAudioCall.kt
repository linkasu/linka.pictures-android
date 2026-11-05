package su.linka.pictures.platform.audio

import android.content.BroadcastReceiver
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.TemporaryFile
import su.linka.pictures.domain.RequestId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class NativeAudioFailure(val result: OperationResult<Nothing>) : Exception() {
    constructor(code: ProblemCode) : this(OperationResult.Failed(Problem(code)))
}

/** One primitive generation, not a queue or an alternative owner arbiter. Android handles live on Main. */
internal class NativeAudioCall(val id: RequestId) {
    val utterance = UUID.randomUUID().toString()
    val terminal = CompletableDeferred<OperationResult<Unit>>()
    val reason = AtomicReference<OperationResult<Nothing>?>(null)
    val temporary = AtomicReference<TemporaryFile?>(null)
    val finish = AtomicBoolean(false)
    val cleanupFailed = AtomicBoolean(false)
    @Volatile var worker: Job? = null
    var player: MediaPlayer? = null
    var recorder: AudioRecord? = null
    var focus: AudioManager.OnAudioFocusChangeListener? = null
    var noisy: BroadcastReceiver? = null
}
