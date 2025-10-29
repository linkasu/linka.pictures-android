package su.linka.pictures

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.UUID

class TTS(context: Context) {

    private val appContext = context.applicationContext
    private val tts: TextToSpeech = TextToSpeech(appContext) { /* no-op */ }

    fun speak(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        } else {
            @Suppress("DEPRECATION")
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null)
        }
    }

    @Throws(Exception::class)
    fun speakToBuffer(text: String): File {
        val file = File(appContext.cacheDir, "${UUID.randomUUID()}.wav")
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts.synthesizeToFile(text, null, file, UUID.randomUUID().toString())
        } else {
            @Suppress("DEPRECATION")
            tts.synthesizeToFile(text, null, file.absolutePath)
        }
        if (result != TextToSpeech.SUCCESS) {
            throw Exception("synth error")
        }
        return file
    }

    fun shutdown() {
        tts.shutdown()
    }
}
