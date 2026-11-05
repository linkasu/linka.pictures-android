@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package su.linka.pictures.platform.audio

import kotlinx.coroutines.*
import kotlinx.cinterop.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okio.use
import platform.AVFAudio.*
import platform.Foundation.*
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.darwin.NSObject
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId
import su.linka.pictures.text.isNativeText

internal class IosAudioBackend(
    private val files: PlatformFilesPort,
    private val inspection: MediaInspectionPort,
    private val preparePlaybackClip: suspend (LocalFile) -> OperationResult<TemporaryFile>,
    private val requestMicrophonePermission: suspend (RequestId) -> OperationResult<Unit>,
    private val isApplicationActive: () -> Boolean,
    scope: CoroutineScope,
) : NativeAudioBackend {
    private val lock = NSRecursiveLock()
    private var active: IosAudioCall? = null
    private var closed = false
    private val runtime = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val capability = MutableStateFlow(VoiceCapability(VoiceStatus.INITIALIZING))
    override val voices = capability.asStateFlow()
    private val initialized = CompletableDeferred<Unit>()
    private val observers = mutableListOf<Any>()

    init {
        runtime.coroutineContext.job.invokeOnCompletion {
            val call = lock.holding { closed = true; active }
            call?.let { abort(it, OperationResult.Cancelled) }
            initialized.complete(Unit)
            // Cleanup must not depend on the cancelled owning scope.
            NSOperationQueue.mainQueue.addOperationWithBlock {
                observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
                observers.clear()
                capability.value = VoiceCapability(VoiceStatus.INTERRUPTED)
            }
        }
        runtime.launch(Dispatchers.Main.immediate) {
            try {
                refreshVoices()
                observe(UIApplicationDidEnterBackgroundNotification) { true }
                observe(AVAudioSessionInterruptionNotification) {
                    (it.userInfo?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedIntegerValue == AVAudioSessionInterruptionTypeBegan
                }
                observe(AVAudioSessionRouteChangeNotification) {
                    (it.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedIntegerValue == AVAudioSessionRouteChangeReasonOldDeviceUnavailable
                }
            } catch (_: Exception) { capability.value = VoiceCapability(VoiceStatus.FAILED) }
            finally { initialized.complete(Unit) }
        }
    }

    private fun observe(name: String?, interrupted: (NSNotification) -> Boolean) {
        requireNotNull(name)
        observers += NSNotificationCenter.defaultCenter.addObserverForName(name, null, NSOperationQueue.mainQueue) { notification ->
            if (notification != null && interrupted(notification)) {
                lock.holding { active }?.let { abort(it, failed(ProblemCode.INTERRUPTED)) }
            }
        }
    }

    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))
    private fun live(call: IosAudioCall) = lock.holding { !closed && active === call && call.reason == null && call.worker?.isActive == true }
    private fun current(call: IosAudioCall) {
        if (!live(call)) throw IosAudioFailure(lock.holding { call.reason } ?: OperationResult.Cancelled)
    }
    private fun callback(call: IosAudioCall, action: () -> Unit) {
        NSOperationQueue.mainQueue.addOperationWithBlock {
            if (live(call)) try { action() } catch (_: Exception) { abort(call, failed(ProblemCode.IO_FAILURE)) }
        }
    }
    private fun cleanup(call: IosAudioCall, action: () -> Unit) {
        try { action() } catch (_: Exception) { call.cleanupFailed = true }
    }
    private fun abort(call: IosAudioCall, reason: OperationResult<Nothing>) {
        val worker = lock.holding {
            if (active !== call || call.reason != null) return
            call.reason = reason // Immediate revocation, even when stop is called off Main.
            call.worker
        }
        worker?.cancel()
    }
    override fun stop(requestId: RequestId) {
        lock.holding { active?.takeIf { it.id == requestId } }?.let { abort(it, OperationResult.Cancelled) }
    }
    override fun finishRecording(requestId: RequestId) {
        val call = lock.holding { active?.takeIf { it.id == requestId } } ?: return
        NSOperationQueue.mainQueue.addOperationWithBlock {
            if (live(call) && call.recorder != null && !call.finishing) {
                if (NSProcessInfo.processInfo.systemUptime >= call.recordDeadline) {
                    abort(call, failed(ProblemCode.LIMIT_EXCEEDED))
                    return@addOperationWithBlock
                }
                call.finishing = true
                try {
                    call.onPhase?.invoke(AudioPhase.FINISHING)
                    call.recorder?.stop()
                } catch (_: Exception) { abort(call, failed(ProblemCode.IO_FAILURE)) }
            }
        }
    }

    private fun foreground(call: IosAudioCall) {
        current(call)
        if (!isApplicationActive()) throw IosAudioFailure(ProblemCode.INTERRUPTED)
    }
    private fun session(call: IosAudioCall, recording: Boolean = false) {
        foreground(call)
        val session = AVAudioSession.sharedInstance()
        if (!session.setCategory(if (recording) AVAudioSessionCategoryPlayAndRecord else AVAudioSessionCategoryPlayback,
                mode = if (recording) AVAudioSessionModeDefault else AVAudioSessionModeSpokenAudio, options = 0u, error = null))
            throw IosAudioFailure(ProblemCode.IO_FAILURE)
        if (!session.setActive(true, error = null)) throw IosAudioFailure(ProblemCode.INTERRUPTED)
        call.sessionActive = true
    }
    private fun release(call: IosAudioCall) {
        // A new generation is not admitted until this release has finished.
        cleanup(call) { call.player?.delegate = null; call.player?.stop() }; call.player = null
        cleanup(call) { call.recorder?.delegate = null; call.recorder?.stop() }; call.recorder = null
        cleanup(call) { call.synthesizer?.delegate = null; call.synthesizer?.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate) }; call.synthesizer = null
        call.delegate = null
        call.pcm.cancel()
        if (call.sessionActive && lock.holding { active === call }) {
            cleanup(call) {
                if (!AVAudioSession.sharedInstance().setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)) call.cleanupFailed = true
            }
            call.sessionActive = false
        }
    }

    private suspend fun <T> operate(id: RequestId, work: suspend (IosAudioCall) -> OperationResult<T>): OperationResult<T> {
        val call = IosAudioCall(id)
        lock.holding {
            if (closed || !runtime.isActive) return OperationResult.Cancelled
            if (active != null) return failed(ProblemCode.CONFLICT)
            active = call
        }
        try {
            val result = supervisorScope {
                val task = async(Dispatchers.IO, start = CoroutineStart.LAZY) { work(call) }
                lock.holding { call.worker = task; if (call.reason != null || closed) task.cancel() else task.start() }
                try { task.await() }
                catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    lock.holding { call.reason } ?: throw cancelled
                } catch (failure: IosAudioFailure) { failure.result }
                catch (_: Exception) { failed(ProblemCode.IO_FAILURE) }
                finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) { release(call); lock.holding { if (active === call) active = null } }
                }
            }
            currentCoroutineContext().ensureActive()
            lock.holding {
                if (closed) return OperationResult.Cancelled
                call.reason?.let { return it }
                if (result is OperationResult.Completed && call.cleanupFailed) return failed(ProblemCode.IO_FAILURE)
                if (result is OperationResult.Completed && result.value is TemporaryFile) call.temporary = null
                return result
            }
        } finally {
            if (lock.holding { active === call }) withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (lock.holding { active === call }) { release(call); lock.holding { active = null } }
            }
            call.temporary?.let { file -> withContext(NonCancellable + Dispatchers.IO) { try { files.discard(file) } catch (_: Exception) { } } }
        }
    }

    private fun <T> value(result: OperationResult<T>): T = when (result) {
        is OperationResult.Completed -> result.value
        is OperationResult.Failed -> throw IosAudioFailure(result)
        OperationResult.Cancelled -> throw IosAudioFailure(OperationResult.Cancelled)
        OperationResult.UnconfirmedCommit -> throw IosAudioFailure(OperationResult.UnconfirmedCommit)
    }
    private suspend fun terminal(call: IosAudioCall) {
        try { value(withTimeout(610_000) { call.terminal.await() }) }
        catch (_: TimeoutCancellationException) { throw IosAudioFailure(ProblemCode.LIMIT_EXCEEDED) }
        current(call)
    }
    override suspend fun inspectClip(file: LocalFile): OperationResult<Unit> = withContext(Dispatchers.IO) {
        when (val result = inspection.audio(file)) {
            is OperationResult.Completed -> OperationResult.Completed(Unit)
            is OperationResult.Failed -> result
            OperationResult.Cancelled -> OperationResult.Cancelled
            OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
        }
    }
    private fun temporary(call: IosAudioCall): TemporaryFile {
        val file = value(files.createTemporaryFile("audio-${NSUUID().UUIDString}.wav", "audio/wav"))
        call.temporary = file
        if (!file.path.endsWith(".wav", ignoreCase = true)) throw IosAudioFailure(ProblemCode.UNSUPPORTED_MEDIA)
        val source = value(files.source(file))
        source.use {
            if (it.read(okio.Buffer(), 1) != -1L) throw IosAudioFailure(ProblemCode.CONFLICT)
        }
        return file
    }
    private suspend fun validated(call: IosAudioCall, file: TemporaryFile): OperationResult<TemporaryFile> {
        current(call)
        val info = value(inspection.audio(file))
        if (info.durationMillis > 600_000) throw IosAudioFailure(ProblemCode.LIMIT_EXCEEDED)
        current(call)
        return OperationResult.Completed(file)
    }

    override suspend fun playClip(file: LocalFile, requestId: RequestId): OperationResult<Unit> = operate(requestId) { call ->
        val prepared = value(preparePlaybackClip(file))
        call.temporary = prepared
        current(call)
        value(inspection.audio(prepared))
        withContext(Dispatchers.Main.immediate) {
            session(call)
            val player = AVAudioPlayer(NSURL.fileURLWithPath(prepared.path), error = null)
            call.player = player
            val delegate = object : NSObject(), AVAudioPlayerDelegateProtocol {
                override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
                    callback(call) { call.terminal.complete(if (successfully) OperationResult.Completed(Unit) else failed(ProblemCode.IO_FAILURE)) }
                }
                override fun audioPlayerDecodeErrorDidOccur(player: AVAudioPlayer, error: NSError?) {
                    callback(call) { call.terminal.complete(failed(ProblemCode.INVALID_DATA)) }
                }
            }
            call.delegate = delegate; player.delegate = delegate
            foreground(call)
            if (!player.prepareToPlay() || !player.play()) throw IosAudioFailure(ProblemCode.IO_FAILURE)
        }
        terminal(call)
        OperationResult.Completed(Unit)
    }

    private fun refreshVoices(): List<AVSpeechSynthesisVoice> {
        val local = AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>()
        capability.value = VoiceCapability(if (local.isEmpty()) VoiceStatus.MISSING_VOICE else VoiceStatus.LOCAL_VOICE_READY,
            local.map { LocalVoice(it.identifier, it.language, it.name) }, 4000)
        return local
    }
    private fun validateText(text: String) {
        if (!isNativeText(text)) throw IosAudioFailure(ProblemCode.INVALID_DATA)
        if (text.length > 4000) throw IosAudioFailure(ProblemCode.LIMIT_EXCEEDED)
    }
    private suspend fun utterance(call: IosAudioCall, text: String, language: String?, preferred: String?): AVSpeechUtterance {
        validateText(text)
        if (text.isBlank()) throw IosAudioFailure(ProblemCode.SPEECH_UNAVAILABLE)
        initialized.await()
        foreground(call)
        val local = refreshVoices()
        // Never ask the OS to resolve a missing explicit voice or download one.
        val selected = when {
            preferred != null -> local.firstOrNull { it.identifier == preferred }
            language != null -> local.firstOrNull { it.language.equals(language, true) }
            else -> {
                val systemLanguage = NSLocale.preferredLanguages.firstOrNull() as? String
                local.firstOrNull { it.language.equals(systemLanguage, true) }
            }
        } ?: throw IosAudioFailure(ProblemCode.VOICE_UNAVAILABLE)
        if (language != null && !selected.language.equals(language, true)) throw IosAudioFailure(ProblemCode.VOICE_UNAVAILABLE)
        return AVSpeechUtterance.speechUtteranceWithString(text).also { it.voice = selected; call.utterance = it }
    }
    override suspend fun speak(text: String, languageTag: String?, voiceId: String?, requestId: RequestId): OperationResult<Unit> = operate(requestId) { call ->
        withContext(Dispatchers.Main.immediate) {
            validateText(text)
            foreground(call)
            if (text.isBlank()) return@withContext
            val utterance = utterance(call, text, languageTag, voiceId)
            session(call)
            val synth = AVSpeechSynthesizer()
            val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
                @ObjCSignatureOverride
                override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
                    callback(call) { if (call.utterance == didFinishSpeechUtterance) call.terminal.complete(OperationResult.Completed(Unit)) }
                }
                @ObjCSignatureOverride
                override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
                    callback(call) { if (call.utterance == didCancelSpeechUtterance) abort(call, failed(ProblemCode.INTERRUPTED)) }
                }
            }
            call.synthesizer = synth; call.delegate = delegate; synth.delegate = delegate
            foreground(call); synth.speakUtterance(utterance)
        }
        if (!text.isBlank()) terminal(call)
        OperationResult.Completed(Unit)
    }

    override suspend fun record(request: RecordingRequest, onPhase: (AudioPhase) -> Unit): OperationResult<TemporaryFile> = operate(request.id) { call ->
        withContext(Dispatchers.Main.immediate) { foreground(call); onPhase(AudioPhase.REQUESTING_PERMISSION) }
        value(requestMicrophonePermission(request.id))
        withContext(Dispatchers.Main.immediate) {
            foreground(call)
            if (AVAudioSession.sharedInstance().recordPermission != AVAudioSessionRecordPermissionGranted) throw IosAudioFailure(ProblemCode.PERMISSION_DENIED)
        }
        val file = temporary(call)
        withContext(Dispatchers.Main.immediate) {
            session(call, recording = true)
            val recorder = AVAudioRecorder(NSURL.fileURLWithPath(file.path), waveSettings(16000.0, 1u), null)
            val delegate = object : NSObject(), AVAudioRecorderDelegateProtocol {
                override fun audioRecorderDidFinishRecording(recorder: AVAudioRecorder, successfully: Boolean) {
                    callback(call) {
                        call.terminal.complete(when {
                            !successfully -> failed(ProblemCode.IO_FAILURE)
                            !call.finishing && NSProcessInfo.processInfo.systemUptime >= call.recordDeadline -> failed(ProblemCode.LIMIT_EXCEEDED)
                            !call.finishing -> failed(ProblemCode.INTERRUPTED)
                            else -> OperationResult.Completed(Unit)
                        })
                    }
                }
                override fun audioRecorderEncodeErrorDidOccur(recorder: AVAudioRecorder, error: NSError?) {
                    callback(call) { abort(call, failed(ProblemCode.IO_FAILURE)) }
                }
            }
            call.onPhase = onPhase; call.delegate = delegate; call.recorder = recorder; recorder.delegate = delegate
            foreground(call)
            if (!recorder.prepareToRecord()) throw IosAudioFailure(ProblemCode.IO_FAILURE)
            call.recordDeadline = NSProcessInfo.processInfo.systemUptime + AUDIO_SECONDS_LIMIT
            if (!recorder.recordForDuration(AUDIO_SECONDS_LIMIT)) throw IosAudioFailure(ProblemCode.IO_FAILURE)
            onPhase(AudioPhase.RECORDING)
        }
        terminal(call)
        validated(call, file)
    }

    override suspend fun synthesize(request: SynthesisRequest): OperationResult<TemporaryFile> = operate(request.id) { call ->
        validateText(request.text)
        if (request.text.isBlank()) throw IosAudioFailure(ProblemCode.SPEECH_UNAVAILABLE)
        val file = temporary(call)
        withContext(Dispatchers.Main.immediate) {
            val utterance = utterance(call, request.text, request.languageTag, request.preferredVoiceId)
            val synth = AVSpeechSynthesizer(); call.synthesizer = synth
            val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
                override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
                    callback(call) { if (call.utterance == didCancelSpeechUtterance) abort(call, failed(ProblemCode.INTERRUPTED)) }
                }
            }
            call.delegate = delegate; synth.delegate = delegate
            foreground(call)
            synth.writeUtterance(utterance) { buffer ->
                if (live(call) && !lock.holding { call.speechEnded }) try {
                    val pcm = buffer as? AVAudioPCMBuffer ?: throw IosAudioFailure(ProblemCode.UNSUPPORTED_MEDIA)
                    if (pcm.frameLength == 0u) { lock.holding { call.speechEnded = true }; call.pcm.close() }
                    else if (!call.pcm.trySend(ownedPcm(pcm)).isSuccess) abort(call, failed(ProblemCode.LIMIT_EXCEEDED))
                } catch (failure: IosAudioFailure) { abort(call, failure.result) }
                catch (_: Exception) { abort(call, failed(ProblemCode.IO_FAILURE)) }
            }
        }
        writeSpeech(call, file)
        validated(call, file)
    }

    private suspend fun writeSpeech(call: IosAudioCall, file: TemporaryFile) {
        var writer: IosSynthWriter? = null
        var frames = 0L
        try {
            withTimeout(610_000) {
                for (buffer in call.pcm) {
                    current(call)
                    if (writer == null) writer = IosSynthWriter(files, file, buffer.format)
                    frames += buffer.frameLength.toLong()
                    writer!!.write(buffer)
                }
            }
            if (frames == 0L) throw IosAudioFailure(ProblemCode.SPEECH_UNAVAILABLE)
            writer?.close()
        } catch (_: TimeoutCancellationException) { throw IosAudioFailure(ProblemCode.LIMIT_EXCEEDED) }
        finally { cleanup(call) { writer?.close() } }
    }
}
