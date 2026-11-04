package su.linka.pictures

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

/** A queued engine: tests explicitly deliver initialization and utterance events. */
@Implements(TextToSpeech::class)
class ControlledTextToSpeech {
    lateinit var initialization: TextToSpeech.OnInitListener
    lateinit var progress: UtteranceProgressListener
    val requests = mutableListOf<Pair<String, File>>()
    var synthesisResult = TextToSpeech.SUCCESS
    var spoken = false
    var spokenText: String? = null
    var spokenId: String? = null
    var stopped = false
    var closed = false

    @Implementation
    fun __constructor__(context: Context, listener: TextToSpeech.OnInitListener) {
        initialization = listener
        latest = this
    }

    @Implementation
    fun initTts(): Int = TextToSpeech.SUCCESS

    @Implementation
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int {
        progress = listener
        return TextToSpeech.SUCCESS
    }

    @Implementation
    fun synthesizeToFile(text: CharSequence, params: Bundle?, file: File, utteranceId: String): Int {
        requests.add(utteranceId to file)
        return synthesisResult
    }

    @Implementation
    fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String): Int {
        spoken = true
        spokenText = text.toString()
        spokenId = utteranceId
        return TextToSpeech.SUCCESS
    }

    @Implementation
    fun stop(): Int {
        stopped = true
        return TextToSpeech.SUCCESS
    }

    @Implementation
    fun shutdown() {
        closed = true
    }

    companion object {
        lateinit var latest: ControlledTextToSpeech
    }
}
