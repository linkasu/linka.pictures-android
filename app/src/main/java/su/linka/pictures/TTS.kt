package su.linka.pictures

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.MainThread
import java.io.File
import java.util.UUID

/** Call from the main thread; synthesis callbacks also run on the main thread. */
class TTS(context: Context) {
    enum class State { INITIALIZING, READY, GENERATING, ERROR, CLOSED }

    var state = State.INITIALIZING
        private set
    val ready: Boolean get() = state == State.READY

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false
    private var pending: Synthesis? = null
    private val tts = TextToSpeech(appContext) { status ->
        handler.post {
            if (state == State.INITIALIZING) {
                initialized = status == TextToSpeech.SUCCESS
                state = if (initialized) State.READY else State.ERROR
                pending?.let {
                    if (initialized) start(it) else fail(it, IllegalStateException("TTS initialization failed"))
                }
            }
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                handler.post {
                    val operation = pending?.takeIf { it.id == utteranceId } ?: return@post
                    if (!operation.file.isFile || !operation.file.canRead() || operation.file.length() == 0L) {
                        fail(operation, IllegalStateException("TTS produced no readable audio"))
                    } else {
                        pending = null
                        state = State.READY
                        operation.callback.onDone(operation.file)
                    }
                }
            }

            @Deprecated("Required by UtteranceProgressListener")
            override fun onError(utteranceId: String?) {
                handler.post {
                    pending?.takeIf { it.id == utteranceId }?.let {
                        fail(it, IllegalStateException("TTS synthesis failed"))
                    }
                }
            }
        })
    }

    @MainThread
    fun speak(text: String) {
        if (!ready) return
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString()) != TextToSpeech.SUCCESS) {
            state = State.ERROR
        }
    }

    /** Waits for initialization. Replacing/cancelling a request silently drops its callback. */
    @MainThread
    fun synthesize(text: String, callback: Callback<File>) {
        if (state == State.CLOSED || (!initialized && state == State.ERROR)) {
            callback.onFail(IllegalStateException("TTS is not available"))
            return
        }
        cancelSynthesis()
        val id = UUID.randomUUID().toString()
        val operation = Synthesis(id, text, File(appContext.cacheDir, "$id.wav"), callback)
        pending = operation
        if (initialized) start(operation)
    }

    @MainThread
    fun cancelSynthesis() {
        val operation = pending ?: return
        pending = null
        if (initialized) tts.stop()
        operation.file.delete()
        state = if (initialized) State.READY else State.INITIALIZING
    }

    private fun start(operation: Synthesis) {
        state = State.GENERATING
        val result = try {
            tts.synthesizeToFile(operation.text, null, operation.file, operation.id)
        } catch (error: Exception) {
            fail(operation, error)
            return
        }
        if (result != TextToSpeech.SUCCESS) {
            fail(operation, IllegalStateException("TTS rejected synthesis"))
        }
    }

    private fun fail(operation: Synthesis, error: Exception) {
        pending = null
        state = State.ERROR
        operation.file.delete()
        operation.callback.onFail(error)
    }

    @MainThread
    fun shutdown() {
        if (state == State.CLOSED) return
        cancelSynthesis()
        state = State.CLOSED
        tts.shutdown()
    }

    private data class Synthesis(
        val id: String,
        val text: String,
        val file: File,
        val callback: Callback<File>
    )
}
