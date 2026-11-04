package su.linka.pictures

import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [ControlledTextToSpeech::class])
class BoardAudioPlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun recordedCardsPlaySequentiallySkippingSpaceEmptyAndMissingClipsAndStopInvalidatesCallbacks() {
        val source = SetsManager(context).createSet()
        val players = mutableListOf<MediaPlayer>()
        ShadowMediaPlayer.setCreateListener { player, _ -> players.add(player) }
        val audio = BoardAudioPlayer(context)
        try {
            val firstFile = File(source.getFolder(), "first.audio").apply { writeText("audio") }
            val secondFile = File(source.getFolder(), "second.audio").apply { writeText("audio") }
            for (file in listOf(firstFile, secondFile)) {
                ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(file.absolutePath), ShadowMediaPlayer.MediaInfo(10000, 0))
            }
            audio.play(source, listOf(
                Card(0, audioPath = firstFile.name),
                Card(1, audioPath = firstFile.name, cardType = 1),
                Card(2, audioPath = firstFile.name, cardType = 2),
                Card(3, audioPath = firstFile.name, cardType = 3),
                Card(4, audioPath = "missing.audio"),
                Card(5, audioPath = secondFile.name),
            ))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, players.size)
            assertTrue(players[0].isPlaying)
            assertTrue(audio.playing)
            val oldCompletion = shadowOf(players[0]).onCompletionListener
            shadowOf(players[0]).invokeCompletionListener()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, players.size)
            assertTrue(players[1].isPlaying)
            audio.stop()
            assertFalse(audio.playing)
            oldCompletion.onCompletion(players[0])
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, players.size)
            assertFalse(audio.playing)
        } finally { audio.close(); source.close(); ShadowMediaPlayer.setCreateListener(null) }
    }

    @Test fun noQueuedSpeechOnReadinessOrCloseAndLateUtteranceCannotStopNewSpeech() {
        val audio = BoardAudioPlayer(context)
        val engine = ControlledTextToSpeech.latest
        try {
            assertFalse(audio.speak("not queued"))
            engine.initialization.onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(engine.spoken)
            assertTrue(audio.speak("Привет 世界"))
            val firstId = engine.spokenId
            assertTrue(audio.playing)
            audio.stop()
            audio.speak("new")
            engine.progress.onDone(firstId)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(audio.playing)
            assertEquals("new", engine.spokenText)
            audio.close()
            engine.progress.onDone(engine.spokenId)
            engine.initialization.onInit(TextToSpeech.SUCCESS)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(audio.playing)
            assertFalse(audio.ttsReady)
            assertTrue(engine.closed)
        } finally { audio.close() }
    }
}
