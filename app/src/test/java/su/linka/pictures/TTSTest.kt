package su.linka.pictures

import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [ControlledTextToSpeech::class])
@LooperMode(LooperMode.Mode.PAUSED)
class TTSTest {
    private lateinit var tts: TTS
    private lateinit var engine: ControlledTextToSpeech
    private val results = mutableListOf<File>()
    private val errors = mutableListOf<Exception?>()
    private val callback = object : Callback<File>() {
        override fun onDone(result: File) { results.add(result) }
        override fun onFail(error: Exception?) { errors.add(error) }
    }

    @Before
    fun setUp() {
        tts = TTS(ApplicationProvider.getApplicationContext())
        engine = ControlledTextToSpeech.latest
    }

    @After
    fun tearDown() {
        tts.shutdown()
        engine.requests.forEach { it.second.delete() }
    }

    @Test
    fun synthesisWaitsForInitializationAndMatchingDoneWithUsableFile() {
        tts.synthesize("Hello", callback)
        assertEquals(TTS.State.INITIALIZING, tts.state)
        assertTrue(engine.requests.isEmpty())
        initialize()
        assertEquals(TTS.State.GENERATING, tts.state)
        assertFalse(tts.ready)
        assertTrue(results.isEmpty())
        val (id, file) = engine.requests.single()
        file.writeText("audio")
        engine.progress.onDone("another utterance")
        idle()
        assertTrue(results.isEmpty())
        engine.progress.onDone(id)
        assertTrue(results.isEmpty()) // Engine threads cannot invoke UI callbacks directly.
        idle()
        assertEquals(listOf(file), results)
        assertTrue(tts.ready)
        engine.progress.onDone(id)
        idle()
        assertEquals(1, results.size)
    }

    @Test
    fun initializationFailureFailsPendingRequestAndDoesNotSpeak() {
        tts.speak("Not ready")
        assertFalse(engine.spoken)
        tts.synthesize("Hello", callback)
        engine.initialization.onInit(TextToSpeech.ERROR)
        idle()
        assertEquals(TTS.State.ERROR, tts.state)
        assertFalse(tts.ready)
        assertEquals(1, errors.size)
        assertTrue(engine.requests.isEmpty())
        tts.synthesize("Again", callback)
        tts.speak("Still unavailable")
        assertEquals(2, errors.size)
        assertFalse(engine.spoken)
    }

    @Test
    fun emptyFileIsFailureAndRetryCanSucceed() {
        initialize()
        tts.synthesize("Hello", callback)
        engine.progress.onDone(engine.requests.last().first)
        idle()
        assertEquals(1, errors.size)
        assertEquals(TTS.State.ERROR, tts.state)
        assertTrue(results.isEmpty())
        tts.synthesize("Retry", callback)
        val (id, file) = engine.requests.last()
        file.writeText("audio")
        engine.progress.onDone(id)
        idle()
        assertEquals(listOf(file), results)
    }

    @Test
    fun asynchronousErrorDeletesPartialFileAndReportsOnce() {
        initialize()
        tts.synthesize("Hello", callback)
        val (id, file) = engine.requests.single()
        file.writeText("partial")
        @Suppress("DEPRECATION")
        engine.progress.onError(id)
        engine.progress.onDone(id)
        idle()
        assertEquals(1, errors.size)
        assertTrue(results.isEmpty())
        assertFalse(file.exists())
        assertEquals(TTS.State.ERROR, tts.state)
    }

    @Test
    fun immediateRejectionReportsFailureWithoutWaitingForDone() {
        initialize()
        engine.synthesisResult = TextToSpeech.ERROR
        tts.synthesize("Hello", callback)
        assertEquals(1, errors.size)
        assertTrue(results.isEmpty())
        assertEquals(TTS.State.ERROR, tts.state)
    }

    @Test
    fun cancellationAndReplacementIgnoreLateCompletion() {
        initialize()
        tts.synthesize("Old card", callback)
        val (oldId, oldFile) = engine.requests.single()
        oldFile.writeText("partial")
        tts.cancelSynthesis()
        assertTrue(engine.stopped)
        assertFalse(oldFile.exists())
        assertTrue(tts.ready)
        tts.synthesize("New card", callback)
        val (newId, newFile) = engine.requests.last()
        assertNotEquals(oldId, newId)
        newFile.writeText("audio")
        engine.progress.onDone(oldId)
        @Suppress("DEPRECATION")
        engine.progress.onError(oldId)
        idle()
        assertTrue(results.isEmpty())
        assertTrue(errors.isEmpty())
        assertEquals(TTS.State.GENERATING, tts.state)
        engine.progress.onDone(newId)
        idle()
        assertEquals(listOf(newFile), results)
    }

    @Test
    fun shutdownDuringInitializationCannotBeRevived() {
        tts.synthesize("Hello", callback)
        tts.shutdown()
        initialize()
        assertEquals(TTS.State.CLOSED, tts.state)
        assertFalse(tts.ready)
        assertTrue(engine.closed)
        assertTrue(engine.requests.isEmpty())
        assertTrue(results.isEmpty())
        assertTrue(errors.isEmpty())
        tts.synthesize("Closed", callback)
        assertEquals(1, errors.size)
    }

    @Test
    fun cancellationWhileInitializingNeverStartsSynthesis() {
        tts.synthesize("Cancelled", callback)
        tts.cancelSynthesis()
        initialize()
        assertTrue(tts.ready)
        assertTrue(engine.requests.isEmpty())
        assertTrue(results.isEmpty())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun replacementCancelsPreviousRequestWithoutCallingItsCallback() {
        initialize()
        tts.synthesize("Old request", callback)
        val oldId = engine.requests.single().first
        tts.synthesize("Replacement", callback)
        assertTrue(engine.stopped)
        engine.progress.onDone(oldId)
        idle()
        assertEquals(TTS.State.GENERATING, tts.state)
        assertTrue(results.isEmpty())
        assertTrue(errors.isEmpty())
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun speakOnlyUsesInitializedEngine() {
        tts.speak("Wait")
        assertFalse(engine.spoken)
        initialize()
        tts.speak("Ready")
        assertTrue(engine.spoken)
    }

    private fun initialize() {
        engine.initialization.onInit(TextToSpeech.SUCCESS)
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}
