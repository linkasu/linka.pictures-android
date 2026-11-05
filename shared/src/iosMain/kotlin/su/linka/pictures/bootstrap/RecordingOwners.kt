@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.bootstrap

import kotlinx.coroutines.*
import platform.AVFAudio.*
import platform.Foundation.NSLock
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId
import kotlin.coroutines.resume

/** Carries the actual recording caller across the native RequestId-only permission callback. */
internal class RecordingOwners(
    private val foreground: (String) -> Boolean,
    private val onPrompt: () -> Unit,
) {
    private data class Pending(val owner: String, val job: Job)
    private val lock = NSLock()
    private val pending = mutableMapOf<RequestId, Pending>()

    fun bind(audio: AudioPort): AudioPort = object : AudioPort by audio {
        override fun play(request: PlaybackRequest) {
            locked { pending.clear() }
            audio.play(request)
        }
        override suspend fun synthesize(request: SynthesisRequest): OperationResult<TemporaryFile> {
            locked { pending.clear() }
            return audio.synthesize(request)
        }
        override fun stop(ownerId: String, requestId: RequestId?) {
            locked { pending.entries.removeAll { (id, caller) -> caller.owner == ownerId && (requestId == null || id == requestId) } }
            audio.stop(ownerId, requestId)
        }
        override fun interrupt() {
            locked { pending.clear() }
            audio.interrupt()
        }
        override suspend fun record(request: RecordingRequest): OperationResult<TemporaryFile> {
            val caller = Pending(request.owner.id, currentCoroutineContext().job)
            val admitted = locked { if (request.id in pending) false else { pending.clear(); pending[request.id] = caller; true } }
            if (!admitted) return OperationResult.Failed(Problem(ProblemCode.CONFLICT))
            try { return audio.record(request) }
            finally { locked { if (pending[request.id] === caller) pending.remove(request.id) } }
        }
    }

    suspend fun permission(id: RequestId): OperationResult<Unit> = withContext(Dispatchers.Main) {
        val caller = locked { pending[id] } ?: return@withContext OperationResult.Cancelled
        fun current() = locked { pending[id] === caller && caller.job.isActive } && foreground(caller.owner)
        if (!current()) return@withContext OperationResult.Cancelled
        val session = AVAudioSession.sharedInstance()
        when (session.recordPermission) {
            AVAudioSessionRecordPermissionGranted -> OperationResult.Completed(Unit)
            AVAudioSessionRecordPermissionDenied -> OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED))
            AVAudioSessionRecordPermissionUndetermined -> {
                onPrompt()
                if (!current()) return@withContext OperationResult.Cancelled
                suspendCancellableCoroutine { reply ->
                    session.requestRecordPermission { granted ->
                        // AVFAudio calls on an unspecified queue; foreground is a UIKit/Main predicate.
                        platform.Foundation.NSOperationQueue.mainQueue.addOperationWithBlock {
                            if (reply.isActive) reply.resume(when {
                                !current() -> OperationResult.Cancelled
                                granted -> OperationResult.Completed(Unit)
                                else -> OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED))
                            })
                        }
                    }
                }
            }
            else -> OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED))
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
