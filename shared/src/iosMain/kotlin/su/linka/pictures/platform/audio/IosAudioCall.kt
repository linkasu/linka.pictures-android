@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.audio

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import platform.AVFAudio.*
import platform.Foundation.NSRecursiveLock
import platform.darwin.NSObject
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId

internal class IosAudioFailure(val result: OperationResult<Nothing>) : Exception() {
    constructor(code: ProblemCode) : this(OperationResult.Failed(Problem(code)))
}

// Only generation arbitration, never a second audio queue. Native handles belong to Main.
internal class IosAudioCall(val id: RequestId) {
    val terminal = CompletableDeferred<OperationResult<Unit>>()
    val pcm = Channel<AVAudioPCMBuffer>(8)
    var worker: Job? = null
    var reason: OperationResult<Nothing>? = null
    var temporary: TemporaryFile? = null
    var finishing = false
    var recordDeadline = Double.POSITIVE_INFINITY
    var speechEnded = false
    var cleanupFailed = false
    var sessionActive = false
    var player: AVAudioPlayer? = null
    var recorder: AVAudioRecorder? = null
    var synthesizer: AVSpeechSynthesizer? = null
    var utterance: AVSpeechUtterance? = null
    var delegate: NSObject? = null
    var onPhase: ((AudioPhase) -> Unit)? = null
}

internal inline fun <T> NSRecursiveLock.holding(action: () -> T): T {
    lock()
    return try { action() } finally { unlock() }
}
