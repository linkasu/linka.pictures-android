package su.linka.pictures.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId
import su.linka.pictures.text.isNativeText

class AudioCoordinator internal constructor(
    private val backend: NativeAudioBackend,
    private val media: MediaStore,
    private val files: PlatformFilesPort,
    scope: CoroutineScope,
) : AudioPort {
    private data class Control(
        val desired: AudioWork? = null,
        val display: AudioState = AudioState(),
        val recent: List<RequestId> = emptyList(),
        val finish: AudioWork? = null,
        val completedToken: Any? = null,
        val closed: Boolean = false,
    )
    private val control = MutableStateFlow(Control())
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val runtime = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(AudioState())
    override val state = mutableState.asStateFlow()
    override val voices = backend.voices
    private var active: AudioWork? = null
    private var nativeJob: Job? = null
    private var stopping = false

    init {
        val actor = runtime.launch(start = CoroutineStart.LAZY) {
            try {
                for (signal in wake) tick()
            } finally {
                shutdown()
            }
        }
        actor.invokeOnCompletion { shutdown() }
        actor.start()
        runtime.launch { voices.collect { wake.trySend(Unit) } }
    }

    private fun shutdown() {
        val previous = control.getAndUpdate { it.copy(closed = true, desired = null, finish = null) }
        if (previous.closed) return
        try {
            previous.desired?.cancel(OperationResult.Cancelled)
            wake.close()
            active?.let {
                it.cancel(OperationResult.Cancelled)
                it.cleanup { backend.stop(it.id) }
                it.discard(it.takeOutcome())
            }
        } finally {
            try { active?.cleanup { nativeJob?.cancel() } }
            finally { runtime.cancel() }
        }
    }

    override fun play(request: PlaybackRequest) {
        // Snapshot a caller's mutable List before ownership crosses into the worker.
        val snapshot = request.copy(plan = request.plan.copy(segments = request.plan.segments.toList()))
        submit(AudioWork(snapshot.id, snapshot.owner, playback = snapshot, files = files, onCleanupFailure = ::cleanupFailed))
    }

    override suspend fun record(request: RecordingRequest): OperationResult<TemporaryFile> = suspendCancellableCoroutine { reply ->
        val work = AudioWork(request.id, request.owner, recording = request, continuation = reply, files = files, onCleanupFailure = ::cleanupFailed)
        reply.invokeOnCancellation { cancel(work, OperationResult.Cancelled, AudioPhase.CANCELLED) }
        submit(work)
    }

    override suspend fun synthesize(request: SynthesisRequest): OperationResult<TemporaryFile> = suspendCancellableCoroutine { reply ->
        val work = AudioWork(request.id, request.owner, synthesis = request, continuation = reply, files = files, onCleanupFailure = ::cleanupFailed)
        reply.invokeOnCancellation { cancel(work, OperationResult.Cancelled, AudioPhase.CANCELLED) }
        submit(work)
    }

    private fun submit(work: AudioWork) {
        while (true) {
            val before = control.value
            if (before.closed || work.cancelled || work.id in before.recent) {
                work.cancel(OperationResult.Cancelled)
                return
            }
            val after = before.copy(desired = work, display = work.phase.value, finish = null, completedToken = null, recent = (before.recent + work.id).takeLast(1024))
            if (control.compareAndSet(before, after)) {
                before.desired?.cancel(OperationResult.Cancelled)
                wake.trySend(Unit)
                return
            }
        }
    }

    override fun stop(ownerId: String, requestId: RequestId?) {
        val work = control.value.desired ?: return
        if (work.owner.id == ownerId && (requestId == null || requestId == work.id)) cancel(work, OperationResult.Cancelled, AudioPhase.CANCELLED)
    }

    override fun interrupt() {
        control.value.desired?.let { cancel(it, OperationResult.Failed(Problem(ProblemCode.INTERRUPTED)), AudioPhase.INTERRUPTED) }
    }

    private fun cancel(work: AudioWork, result: OperationResult<Nothing>, phase: AudioPhase) {
        while (true) {
            val before = control.value
            val problem = (result as? OperationResult.Failed)?.problem
            if (before.desired !== work) {
                if (before.desired == null && before.completedToken === work.token && before.display.phase == AudioPhase.READY) {
                    if (!control.compareAndSet(before, before.copy(display = AudioState(work.id, work.owner, phase, problem = problem ?: work.cleanupProblem.value)))) continue
                }
                break
            }
            if (control.compareAndSet(before, before.copy(desired = null, finish = null, completedToken = work.token, display = AudioState(work.id, work.owner, phase, problem = problem ?: work.cleanupProblem.value)))) break
        }
        work.cancel(result)
        wake.trySend(Unit)
    }

    override fun finishRecording(ownerId: String, requestId: RequestId) {
        while (true) {
            val before = control.value
            val work = before.desired ?: return
            if (work.recording == null || work.owner.id != ownerId || work.id != requestId || work.phase.value.phase != AudioPhase.RECORDING) return
            if (control.compareAndSet(before, before.copy(finish = work))) {
                wake.trySend(Unit)
                return
            }
        }
    }

    private fun tick() {
        val running = active
        if (running != null) {
            if (nativeJob!!.isCompleted) {
                active = null
                nativeJob = null
                finish(running, running.takeOutcome() ?: OperationResult.Cancelled)
            } else {
                val before = control.value
                if (before.desired !== running || running.cancelled) {
                    if (!stopping) {
                        stopping = true
                        try { running.cleanup { backend.stop(running.id) } }
                        finally { running.cleanup { nativeJob!!.cancel() } }
                    }
                } else {
                    val voice = running.voiceSnapshot.value
                    if (running.phase.value.phase == AudioPhase.PLAYING && voice != null && voice != voices.value) {
                        cancel(running, OperationResult.Failed(Problem(ProblemCode.INTERRUPTED)), AudioPhase.INTERRUPTED)
                        return
                    }
                    if (before.finish === running && control.compareAndSet(before, before.copy(finish = null))) {
                        if (running.phase.value.phase == AudioPhase.RECORDING) {
                            phase(running, AudioPhase.FINISHING)
                            if (!running.cleanup { backend.finishRecording(running.id) }) {
                                cancel(running, OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)), AudioPhase.FAILED)
                            }
                        }
                    }
                }
            }
        }
        val desired = control.value.desired
        if (active == null && desired != null && desired.start()) {
            active = desired
            stopping = false
            val job = runtime.launch(start = CoroutineStart.LAZY) {
                desired.outcome.value = try {
                    execute(desired)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
                }
            }
            nativeJob = job
            job.invokeOnCompletion {
                try { desired.closePlaybackLease() }
                finally { if (!wake.trySend(Unit).isSuccess) desired.discard(desired.takeOutcome()) }
            }
            job.start()
        }
        val current = control.value
        mutableState.value = if (active === current.desired && current.desired != null) current.desired.phase.value else current.display
    }

    private fun finish(work: AudioWork, result: OperationResult<TemporaryFile?>) {
        while (true) {
            val before = control.value
            if (before.desired !== work || work.cancelled) {
                work.discard(result)
                return
            }
            val phase = when (result) {
                is OperationResult.Completed -> if (work.playback != null) AudioPhase.COMPLETED else AudioPhase.READY
                is OperationResult.Failed -> if (result.problem.code == ProblemCode.INTERRUPTED) AudioPhase.INTERRUPTED else AudioPhase.FAILED
                OperationResult.UnconfirmedCommit -> AudioPhase.FAILED
                OperationResult.Cancelled -> AudioPhase.CANCELLED
            }
            val problem = when (result) {
                is OperationResult.Failed -> result.problem
                OperationResult.UnconfirmedCommit -> Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
                else -> null
            }
            val display = AudioState(work.id, work.owner, phase, problem = problem ?: work.cleanupProblem.value)
            if (control.compareAndSet(before, before.copy(desired = null, finish = null, display = display, completedToken = work.token))) {
                work.reply(result)
                return
            }
        }
    }

    private fun phase(work: AudioWork, phase: AudioPhase, index: Int = 0) {
        work.phase.value = AudioState(work.id, work.owner, phase, index, work.cleanupProblem.value)
        wake.trySend(Unit)
    }

    private fun cleanupFailed(work: AudioWork) {
        while (true) {
            val before = control.value
            if (before.closed || (before.desired !== work && (before.desired != null || before.completedToken !== work.token))) break
            if (control.compareAndSet(before, before.copy(display = before.display.copy(problem = before.display.problem ?: work.cleanupProblem.value)))) break
        }
        wake.trySend(Unit)
    }

    private suspend fun current(work: AudioWork) {
        currentCoroutineContext().ensureActive()
        if (control.value.desired !== work || work.cancelled) throw CancellationException()
    }

    private data class Prepared(val file: LocalFile? = null, val text: SpeechSegment.Text? = null)

    private suspend fun execute(work: AudioWork): OperationResult<TemporaryFile?> {
        current(work)
        val playback = work.playback
        if (playback != null) {
            val prepared = mutableListOf<Prepared>()
            val capability = voices.value
            val voice = capability.copy(voices = capability.voices.toList())
            val hasText = playback.plan.segments.any { it is SpeechSegment.Text && it.text.isNotBlank() }
            if (playback.plan.segments.any { it is SpeechSegment.Text && !isNativeText(it.text) }) return OperationResult.Failed(Problem(ProblemCode.INVALID_DATA))
            if (hasText) {
                work.voiceSnapshot.value = voice
                for (text in playback.plan.segments.filterIsInstance<SpeechSegment.Text>().filter { it.text.isNotBlank() }) {
                    val checked = voiceProblem(voice, text.languageTag, playback.preferredVoiceId)
                    if (checked != null) return OperationResult.Failed(checked)
                }
            }
            for (segment in playback.plan.segments) {
                current(work)
                when (segment) {
                    is SpeechSegment.Recording -> {
                        if (segment.assetId !in playback.lease.assets) return OperationResult.Failed(Problem(ProblemCode.MISSING_ASSET))
                        val file = when (val resolved = media.file(segment.assetId, playback.lease)) {
                            is OperationResult.Completed -> resolved.value
                            is OperationResult.Failed -> return resolved
                            OperationResult.Cancelled -> return OperationResult.Cancelled
                            OperationResult.UnconfirmedCommit -> return OperationResult.UnconfirmedCommit
                        }
                        when (val inspected = backend.inspectClip(file)) {
                            is OperationResult.Completed -> prepared += Prepared(file = file)
                            is OperationResult.Failed -> return inspected
                            OperationResult.Cancelled -> return OperationResult.Cancelled
                            OperationResult.UnconfirmedCommit -> return OperationResult.UnconfirmedCommit
                        }
                    }
                    is SpeechSegment.Text -> when (val chunks = speechChunks(segment.text, if (segment.text.isBlank()) 1 else voice.maxTextLength)) {
                        is OperationResult.Completed -> chunks.value.forEach { prepared += Prepared(text = segment.copy(text = it)) }
                        is OperationResult.Failed -> return chunks
                        OperationResult.Cancelled -> return OperationResult.Cancelled
                        OperationResult.UnconfirmedCommit -> return OperationResult.UnconfirmedCommit
                    }
                }
            }
            val snapshot = prepared.toList()
            if (hasText && voice != voices.value) return OperationResult.Failed(Problem(ProblemCode.VOICE_UNAVAILABLE))
            for ((index, segment) in snapshot.withIndex()) {
                current(work)
                if (hasText && voice != voices.value) return OperationResult.Failed(Problem(ProblemCode.INTERRUPTED))
                phase(work, AudioPhase.PLAYING, index)
                val result = if (segment.file != null) backend.playClip(segment.file, work.id)
                    else backend.speak(segment.text!!.text, segment.text.languageTag, playback.preferredVoiceId, work.id)
                when (result) {
                    is OperationResult.Completed -> Unit
                    is OperationResult.Failed -> return result
                    OperationResult.Cancelled -> return OperationResult.Cancelled
                    OperationResult.UnconfirmedCommit -> return OperationResult.UnconfirmedCommit
                }
            }
            current(work)
            return OperationResult.Completed(null)
        }
        val synthesis = work.synthesis
        if (synthesis != null) {
            if (!isNativeText(synthesis.text)) return OperationResult.Failed(Problem(ProblemCode.INVALID_DATA))
            val voice = voices.value
            voiceProblem(voice, synthesis.languageTag, synthesis.preferredVoiceId)?.let { return OperationResult.Failed(it) }
            if (synthesis.text.length > voice.maxTextLength) return OperationResult.Failed(Problem(ProblemCode.LIMIT_EXCEEDED))
            if (synthesis.text.isBlank()) return OperationResult.Failed(Problem(ProblemCode.SPEECH_UNAVAILABLE))
        }
        var owned: TemporaryFile? = null
        try {
            val produced = if (synthesis != null) backend.synthesize(synthesis) else backend.record(work.recording!!) { value ->
                if (control.value.desired === work && !work.cancelled) {
                    val previous = work.phase.value.phase
                    if ((value == AudioPhase.RECORDING && previous == AudioPhase.REQUESTING_PERMISSION) ||
                        (value == AudioPhase.FINISHING && previous == AudioPhase.RECORDING)) phase(work, value)
                }
            }
            when (produced) {
                is OperationResult.Completed -> owned = produced.value
                is OperationResult.Failed -> return produced
                OperationResult.Cancelled -> return produced
                OperationResult.UnconfirmedCommit -> return produced
            }
            current(work)
            when (val inspected = backend.inspectClip(owned)) {
                is OperationResult.Completed -> Unit
                is OperationResult.Failed -> return inspected
                OperationResult.Cancelled -> return OperationResult.Cancelled
                OperationResult.UnconfirmedCommit -> return OperationResult.UnconfirmedCommit
            }
            current(work)
            return OperationResult.Completed(owned).also { owned = null }
        } finally {
            owned?.let(work::discardFile)
        }
    }

    private fun voiceProblem(voice: VoiceCapability, language: String?, preferred: String?): Problem? {
        if (voice.maxTextLength <= 0) return Problem(ProblemCode.LIMIT_EXCEEDED)
        if (voice.status != VoiceStatus.LOCAL_VOICE_READY) return Problem(ProblemCode.VOICE_UNAVAILABLE)
        val selected = preferred?.let { id -> voice.voices.firstOrNull { it.id == id } ?: return Problem(ProblemCode.VOICE_UNAVAILABLE) }
        if (language != null && (selected?.languageTag?.equals(language, true) == false ||
                (selected == null && voice.voices.none { it.languageTag.equals(language, true) }))) return Problem(ProblemCode.VOICE_UNAVAILABLE)
        return null
    }
}
