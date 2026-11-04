package su.linka.pictures

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID

/** Activity-owned foreground playback; stop invalidates every pending callback. */
class BoardAudioPlayer(context: Context) : AutoCloseable {
    enum class SpeechState { INITIALIZING, READY, ERROR, CLOSED }

    var playing = false
        private set
    var speechState = SpeechState.INITIALIZING
        private set
    val ttsReady: Boolean get() = speechState == SpeechState.READY
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var generation = 0
    private var media: MediaPlayer? = null
    private var utterance: String? = null
    private val tts = TextToSpeech(context.applicationContext) { status ->
        handler.post { if (!closed) speechState = if (status == TextToSpeech.SUCCESS) SpeechState.READY else SpeechState.ERROR }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finished(utteranceId, false)
            @Deprecated("Platform callback")
            override fun onError(utteranceId: String?) = finished(utteranceId, true)
            private fun finished(id: String?, error: Boolean) {
                handler.post {
                    if (!closed && utterance == id) {
                        utterance = null
                        playing = false
                        if (error) speechState = SpeechState.ERROR
                    }
                }
            }
        })
    }

    fun speak(text: String): Boolean {
        if (closed) return false
        stop()
        if (!ttsReady || text.isEmpty()) return false
        utterance = UUID.randomUUID().toString()
        playing = try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utterance) == TextToSpeech.SUCCESS
        } catch (_: Exception) { false }
        if (!playing) { utterance = null; speechState = SpeechState.ERROR }
        return playing
    }

    fun play(set: Set, cards: List<Card>) {
        if (closed) return
        stop()
        val files = cards.filter { it.cardType == 0 }.mapNotNull { card ->
            set.getAudioFile(card.audioPath)?.takeIf { it.isFile }
        }
        val operation = generation
        var index = 0
        fun next() {
            if (closed || operation != generation) return
            media?.release()
            media = null
            if (index >= files.size) { playing = false; return }
            val file = files[index++]
            val player = MediaPlayer()
            media = player
            player.setOnPreparedListener { if (operation == generation && !closed && media === it) it.start() }
            player.setOnCompletionListener { if (media === it) next() }
            player.setOnErrorListener { _, _, _ ->
                handler.post { if (media === player) next() }
                true
            }
            try {
                player.setDataSource(file.absolutePath)
                player.prepareAsync()
                playing = true
            } catch (_: Exception) {
                handler.post { next() }
            }
        }
        next()
    }

    fun stop() {
        generation++
        utterance = null
        media?.release()
        media = null
        if (!closed) tts.stop()
        playing = false
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        speechState = SpeechState.CLOSED
        tts.shutdown()
    }
}
