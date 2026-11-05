package su.linka.pictures.platform.audio

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId
import su.linka.pictures.text.isNativeText
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class AndroidAudioBackend(
    context: Context,
    private val files: PlatformFilesPort,
    private val inspection: MediaInspectionPort,
    private val requestMicrophonePermission: suspend (RequestId) -> OperationResult<Unit>,
    scope: CoroutineScope,
) : NativeAudioBackend {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val runtime = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val active = AtomicReference<NativeAudioCall?>(null)
    private val closed = AtomicBoolean(false)
    private val initialized = CompletableDeferred<Unit>()
    private val capabilities = MutableStateFlow(VoiceCapability(VoiceStatus.INITIALIZING))
    override val voices = capabilities.asStateFlow()
    private var engine: TextToSpeech? = null
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String) = Unit
        override fun onDone(id: String) { main.post { utterance(id)?.terminal?.complete(OperationResult.Completed(Unit)) } }
        @android.annotation.TargetApi(23)
        override fun onStop(id: String, interrupted: Boolean) { main.post {
            utterance(id)?.terminal?.complete(OperationResult.Failed(Problem(ProblemCode.INTERRUPTED)))
        } }
        @Suppress("OVERRIDE_DEPRECATION") override fun onError(id: String) { failed(id) }
        override fun onError(id: String, code: Int) { failed(id, code) }
        private fun failed(id: String, code: Int? = null) { main.post {
            utterance(id)?.let { call ->
                val unavailable = code == TextToSpeech.ERROR_NOT_INSTALLED_YET || code == TextToSpeech.ERROR_NETWORK || code == TextToSpeech.ERROR_NETWORK_TIMEOUT
                if (unavailable) capabilities.value = capabilities.value.copy(status = VoiceStatus.MISSING_VOICE)
                call.terminal.complete(OperationResult.Failed(Problem(if (unavailable) ProblemCode.VOICE_UNAVAILABLE else ProblemCode.IO_FAILURE)))
            }
        } }
    }

    // Initialize callbacks before scheduling Main: this constructor may itself run on an IO thread.
    init {
        runtime.coroutineContext.job.invokeOnCompletion {
            closed.set(true)
            capabilities.value = VoiceCapability(VoiceStatus.INTERRUPTED)
            initialized.complete(Unit)
            main.post {
                active.get()?.let { abort(it, OperationResult.Cancelled) }
                safe { engine?.shutdown() }; engine = null
            }
        }
        runtime.launch(Dispatchers.Main.immediate) {
            if (!closed.get()) {
                try {
                    engine = TextToSpeech(app) { status -> main.post {
                        if (closed.get()) return@post
                        try {
                            if (status == TextToSpeech.SUCCESS) {
                                engine?.setOnUtteranceProgressListener(listener)
                                engine?.setAudioAttributes(attributes)
                                refreshVoices()
                            } else capabilities.value = VoiceCapability(VoiceStatus.FAILED)
                        } catch (_: Exception) { capabilities.value = VoiceCapability(VoiceStatus.FAILED) }
                        finally { initialized.complete(Unit) }
                    } }
                } catch (_: Exception) {
                    capabilities.value = VoiceCapability(VoiceStatus.FAILED)
                    initialized.complete(Unit)
                }
            }
        }
    }

    private fun live(call: NativeAudioCall) = !closed.get() && active.get() === call && call.reason.get() == null && call.worker?.isActive == true
    private fun utterance(id: String): NativeAudioCall? = active.get()?.takeIf { it.utterance == id && live(it) }
    private fun current(call: NativeAudioCall) {
        if (!live(call)) throw NativeAudioFailure(call.reason.get() ?: OperationResult.Cancelled)
    }
    private fun safe(action: () -> Unit) { try { action() } catch (_: Exception) { /* Best-effort OS cleanup, no raw diagnostics. */ } }
    private fun cleanup(call: NativeAudioCall, action: () -> Unit) {
        try { action() } catch (_: Exception) { call.cleanupFailed.set(true) }
    }
    private fun abort(call: NativeAudioCall, result: OperationResult<Nothing>) {
        if (active.get() !== call || !call.reason.compareAndSet(null, result)) return
        release(call)
        call.worker?.cancel()
    }
    override fun stop(requestId: RequestId) {
        val call = active.get()?.takeIf { it.id == requestId } ?: return
        main.post { abort(call, OperationResult.Cancelled) }
    }
    override fun finishRecording(requestId: RequestId) {
        val call = active.get()?.takeIf { it.id == requestId } ?: return
        main.post {
            if (live(call) && call.recorder != null && call.finish.compareAndSet(false, true)) {
                try { call.recorder?.stop() } catch (_: Exception) { abort(call, OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun focus(call: NativeAudioCall) {
        current(call)
        val listener = AudioManager.OnAudioFocusChangeListener { change -> main.post {
            if (change < 0) abort(call, OperationResult.Failed(Problem(ProblemCode.INTERRUPTED)))
        } }
        call.focus = listener
        if (manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw NativeAudioFailure(ProblemCode.INTERRUPTED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) abort(call, OperationResult.Failed(Problem(ProblemCode.INTERRUPTED)))
            }
        }
        call.noisy = receiver
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    @Suppress("DEPRECATION")
    private fun release(call: NativeAudioCall, terminal: Boolean = false) {
        call.player?.let { cleanup(call) { it.setOnPreparedListener(null) }; cleanup(call) { it.setOnCompletionListener(null) }; cleanup(call) { it.setOnErrorListener(null) }; cleanup(call) { it.release() } }; call.player = null
        call.recorder?.let { cleanup(call) { if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop() } }
        if (terminal) { call.recorder?.let { cleanup(call) { it.release() } }; call.recorder = null }
        if (active.get() === call) cleanup(call) { engine?.stop() }
        call.noisy?.let { cleanup(call) { app.unregisterReceiver(it) } }; call.noisy = null
        call.focus?.let { cleanup(call) { manager.abandonAudioFocus(it) } }; call.focus = null
    }

    private suspend fun <T> operate(id: RequestId, work: suspend (NativeAudioCall) -> OperationResult<T>): OperationResult<T> {
        if (closed.get() || !runtime.isActive) return OperationResult.Cancelled
        val call = NativeAudioCall(id)
        if (!active.compareAndSet(null, call)) return OperationResult.Failed(Problem(ProblemCode.CONFLICT))
        try {
            val result = supervisorScope {
                val task = async(Dispatchers.IO, start = CoroutineStart.LAZY) { work(call) }
                call.worker = task
                if (call.reason.get() != null) task.cancel() else task.start()
                try {
                    task.await()
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    call.reason.get() ?: throw cancelled
                } catch (failure: NativeAudioFailure) { failure.result }
                catch (_: SecurityException) { OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED)) }
                catch (_: Exception) { OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) }
                finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        release(call, terminal = true)
                        active.compareAndSet(call, null)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (closed.get()) return OperationResult.Cancelled
            if (result is OperationResult.Completed && call.cleanupFailed.get()) return OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
            if (result is OperationResult.Completed && result.value is TemporaryFile) call.temporary.set(null)
            return result
        } finally {
            // No suspension follows successful ownership transfer; undelivered/failed files remain ours here.
            call.temporary.getAndSet(null)?.let { file -> withContext(NonCancellable + Dispatchers.IO) { safe { files.discard(file) } } }
            if (active.get() === call) withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (active.get() === call) { release(call, terminal = true); active.compareAndSet(call, null) }
            }
        }
    }

    override suspend fun inspectClip(file: LocalFile): OperationResult<Unit> = when (val result = inspection.audio(file)) {
        is OperationResult.Completed -> OperationResult.Completed(Unit)
        is OperationResult.Failed -> result
        OperationResult.Cancelled -> OperationResult.Cancelled
        OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
    }

    override suspend fun playClip(file: LocalFile, requestId: RequestId): OperationResult<Unit> = operate(requestId) { call ->
        val input = FileInputStream(File(file.path))
        try { withContext(Dispatchers.Main.immediate) {
            current(call); focus(call)
            val player = MediaPlayer().also { call.player = it }
            player.setAudioAttributes(attributes)
            player.setOnPreparedListener {
                if (live(call)) {
                    try { it.start() } catch (_: Exception) { call.terminal.complete(OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))) }
                }
            }
            player.setOnCompletionListener { if (live(call)) call.terminal.complete(OperationResult.Completed(Unit)) }
            player.setOnErrorListener { _, what, extra ->
                if (live(call)) call.terminal.complete(OperationResult.Failed(Problem(when (extra) {
                    MediaPlayer.MEDIA_ERROR_MALFORMED -> ProblemCode.INVALID_DATA
                    MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> ProblemCode.UNSUPPORTED_MEDIA
                    else -> if (what == MediaPlayer.MEDIA_ERROR_SERVER_DIED) ProblemCode.INTERRUPTED else ProblemCode.IO_FAILURE
                })))
                true
            }
            player.setDataSource(input.fd)
            player.prepareAsync()
            terminal(call)
        } } finally { cleanup(call) { input.close() } }
    }

    private fun refreshVoices(): List<Voice> {
        val local = engine?.voices.orEmpty().filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
        capabilities.value = VoiceCapability(if (local.isEmpty()) VoiceStatus.MISSING_VOICE else VoiceStatus.LOCAL_VOICE_READY,
            local.map { LocalVoice(it.name, it.locale.toLanguageTag(), it.name) }, TextToSpeech.getMaxSpeechInputLength())
        return local
    }
    private suspend fun voice(call: NativeAudioCall, language: String?, preferred: String?): TextToSpeech {
        try { withTimeout(10_000) { initialized.await() } } catch (_: TimeoutCancellationException) {
            capabilities.value = VoiceCapability(VoiceStatus.FAILED)
            throw NativeAudioFailure(ProblemCode.VOICE_UNAVAILABLE)
        }
        current(call)
        val tts = engine ?: throw NativeAudioFailure(ProblemCode.VOICE_UNAVAILABLE)
        if (capabilities.value.status == VoiceStatus.FAILED) throw NativeAudioFailure(ProblemCode.VOICE_UNAVAILABLE)
        val local = try { refreshVoices() } catch (_: Exception) { throw NativeAudioFailure(ProblemCode.VOICE_UNAVAILABLE) }
        val selected = if (preferred != null) local.firstOrNull { it.name == preferred }
            else if (language != null) local.firstOrNull { it.locale.toLanguageTag().equals(language, true) }
            else local.firstOrNull { it.name == tts.defaultVoice?.name }
        if (selected == null || (language != null && !selected.locale.toLanguageTag().equals(language, true))) throw NativeAudioFailure(ProblemCode.VOICE_UNAVAILABLE)
        if (tts.isLanguageAvailable(selected.locale) < TextToSpeech.LANG_AVAILABLE || tts.setVoice(selected) != TextToSpeech.SUCCESS ||
            tts.voice?.isNetworkConnectionRequired != false || tts.voice?.name != selected.name ||
            tts.voice?.locale?.toLanguageTag()?.equals(selected.locale.toLanguageTag(), true) != true) throw NativeAudioFailure(ProblemCode.VOICE_UNAVAILABLE)
        return tts
    }

    override suspend fun speak(text: String, languageTag: String?, voiceId: String?, requestId: RequestId): OperationResult<Unit> = operate(requestId) { call ->
        withContext(Dispatchers.Main.immediate) {
            if (!isNativeText(text)) throw NativeAudioFailure(ProblemCode.INVALID_DATA)
            if (text.isBlank()) return@withContext OperationResult.Completed(Unit)
            val tts = voice(call, languageTag, voiceId)
            if (text.length > TextToSpeech.getMaxSpeechInputLength()) throw NativeAudioFailure(ProblemCode.LIMIT_EXCEEDED)
            focus(call)
            current(call)
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle().also { it.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }, call.utterance) != TextToSpeech.SUCCESS) throw NativeAudioFailure(ProblemCode.IO_FAILURE)
            terminal(call)
        }
    }

    private fun temporary(call: NativeAudioCall, prefix: String): TemporaryFile {
        val value = when (val result = files.createTemporaryFile("$prefix-${UUID.randomUUID()}.wav", "audio/wav")) {
            is OperationResult.Completed -> result.value
            is OperationResult.Failed -> throw NativeAudioFailure(result)
            OperationResult.Cancelled -> throw NativeAudioFailure(OperationResult.Cancelled)
            OperationResult.UnconfirmedCommit -> throw NativeAudioFailure(OperationResult.UnconfirmedCommit)
        }
        val file = File(value.path)
        if (!file.isFile || file.length() != 0L) throw NativeAudioFailure(ProblemCode.CONFLICT)
        call.temporary.set(value)
        return value
    }
    private suspend fun validated(call: NativeAudioCall, file: TemporaryFile): OperationResult<TemporaryFile> {
        current(call)
        when (val result = inspection.audio(file)) {
            is OperationResult.Completed -> Unit
            is OperationResult.Failed -> throw NativeAudioFailure(result)
            OperationResult.Cancelled -> throw NativeAudioFailure(OperationResult.Cancelled)
            OperationResult.UnconfirmedCommit -> throw NativeAudioFailure(OperationResult.UnconfirmedCommit)
        }
        current(call)
        return OperationResult.Completed(file)
    }
    override suspend fun synthesize(request: SynthesisRequest): OperationResult<TemporaryFile> = operate(request.id) { call ->
        if (!isNativeText(request.text)) throw NativeAudioFailure(ProblemCode.INVALID_DATA)
        if (request.text.length > TextToSpeech.getMaxSpeechInputLength()) throw NativeAudioFailure(ProblemCode.LIMIT_EXCEEDED)
        if (request.text.isBlank()) throw NativeAudioFailure(ProblemCode.SPEECH_UNAVAILABLE)
        val file = temporary(call, "speech")
        val terminal = withContext(Dispatchers.Main.immediate) {
            val tts = voice(call, request.languageTag, request.preferredVoiceId)
            current(call)
            if (tts.synthesizeToFile(request.text, Bundle(), File(file.path), call.utterance) != TextToSpeech.SUCCESS) throw NativeAudioFailure(ProblemCode.IO_FAILURE)
            terminal(call)
        }
        if (terminal !is OperationResult.Completed) throw NativeAudioFailure(when (terminal) {
            is OperationResult.Failed -> terminal
            OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
            else -> OperationResult.Cancelled
        })
        validated(call, file)
    }

    @Suppress("DEPRECATION")
    override suspend fun record(request: RecordingRequest, onPhase: (AudioPhase) -> Unit): OperationResult<TemporaryFile> = operate(request.id) { call ->
        withContext(Dispatchers.Main.immediate) { current(call); onPhase(AudioPhase.REQUESTING_PERMISSION) }
        when (val result = requestMicrophonePermission(request.id)) {
            is OperationResult.Completed -> Unit
            is OperationResult.Failed -> throw NativeAudioFailure(result)
            OperationResult.Cancelled -> throw NativeAudioFailure(OperationResult.Cancelled)
            OperationResult.UnconfirmedCommit -> throw NativeAudioFailure(OperationResult.UnconfirmedCommit)
        }
        current(call)
        if (app.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, app.packageName) != PackageManager.PERMISSION_GRANTED) throw NativeAudioFailure(ProblemCode.PERMISSION_DENIED)
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0 || minimum > 64 * 1024 * 1024) throw NativeAudioFailure(ProblemCode.UNSUPPORTED_MEDIA)
        val file = temporary(call, "record")
        val recorder = withContext(Dispatchers.Main.immediate) {
            current(call)
            AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8192)).also {
                call.recorder = it
                if (it.state != AudioRecord.STATE_INITIALIZED) throw NativeAudioFailure(ProblemCode.UNSUPPORTED_MEDIA)
                current(call)
                it.startRecording()
                if (it.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw NativeAudioFailure(ProblemCode.IO_FAILURE)
                current(call)
                onPhase(AudioPhase.RECORDING)
            }
        }
        try {
            PcmWaveFile(file).use { wave ->
                val buffer = ShortArray(2048)
                val deadline = SystemClock.elapsedRealtime() + 600_000
                while (!call.finish.get()) {
                    currentCoroutineContext().ensureActive(); current(call)
                    if (SystemClock.elapsedRealtime() >= deadline) throw NativeAudioFailure(ProblemCode.LIMIT_EXCEEDED)
                    val count = recorder.read(buffer, 0, buffer.size)
                    if (count <= 0) {
                        if (call.finish.get()) break
                        throw NativeAudioFailure(ProblemCode.IO_FAILURE)
                    }
                    wave.write(buffer, count)
                }
                current(call)
                withContext(Dispatchers.Main.immediate) { current(call); onPhase(AudioPhase.FINISHING) }
                wave.finish()
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                cleanup(call) { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
                cleanup(call) { recorder.release() }; call.recorder = null
            }
        }
        validated(call, file)
    }

    private suspend fun terminal(call: NativeAudioCall): OperationResult<Unit> = try {
        withTimeout(610_000) { call.terminal.await() }
    } catch (_: TimeoutCancellationException) { throw NativeAudioFailure(ProblemCode.IO_FAILURE) }
}
