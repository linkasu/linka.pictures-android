package su.linka.pictures.components

import android.content.DialogInterface
import android.graphics.Bitmap
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import androidx.fragment.app.FragmentActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import su.linka.pictures.Card
import su.linka.pictures.Callback
import su.linka.pictures.ControlledTextToSpeech
import su.linka.pictures.R
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [ControlledTextToSpeech::class])
@LooperMode(LooperMode.Mode.PAUSED)
class EditCardDialogTest {

    private lateinit var activity: FragmentActivity
    private lateinit var workingDir: File
    private lateinit var set: su.linka.pictures.Set

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val card = Card(0, title = "Title", cardType = 0)
        val (_, createdSet, dir) = ComponentTestUtils.createSet(cards = listOf(card))
        set = createdSet
        workingDir = dir
    }

    @After
    fun tearDown() {
        ComponentTestUtils.cleanup(workingDir)
        activity.finish()
    }

    @Test
    fun validate_missingMedia_returnsFalse() {
        val dialog = EditCardDialog(activity)
        dialog.show(set, null)

        val result = dialog.validate()

        assertFalse(result)
        dialog.dismiss()
    }

    @Test
    fun validate_completeStandardCard_returnsTrue() {
        val dialog = EditCardDialog(activity)
        dialog.show(set, null)

        val titleField = dialog.findViewById<EditText>(su.linka.pictures.R.id.card_title_edittext)!!
        titleField.setText("Hello")
        setPrivateField(dialog, "currentBitmap", Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        val audioFile = File.createTempFile("audio", ".tmp", workingDir)
        setPrivateField(dialog, "currentAudio", audioFile)

        val result = dialog.validate()

        assertTrue(result)
        dialog.dismiss()
        audioFile.delete()
    }

    @Test
    fun validate_spaceCard_returnsTrueWithoutMedia() {
        val dialog = EditCardDialog(activity)
        dialog.show(set, null)

        val radioGroup = dialog.findViewById<RadioGroup>(su.linka.pictures.R.id.card_type_radiogroup)!!
        radioGroup.check(su.linka.pictures.R.id.space_card_radio)

        val result = dialog.validate()

        assertTrue(result)
        dialog.dismiss()
    }

    @Test
    fun save_audioCopyFailure_keepsDialogAndOriginalPathsWithoutSuccess() {
        val image = File(workingDir, "old.png").apply { writeText("old image") }
        val audio = File(workingDir, "old.3gp").apply { writeText("old audio") }
        val original = Card(0, image.name, "Original", audio.name, 0)
        val dialog = EditCardDialog(activity)
        dialog.show(set, original)
        val editedCard = getPrivateField(dialog, "card") as Card
        val missingAudio = File(workingDir, "missing-source/missing.3gp")
        setPrivateField(dialog, "currentBitmap", Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        setPrivateField(dialog, "imageDirty", true)
        setPrivateField(dialog, "currentAudio", missingAudio)
        setPrivateField(dialog, "audioDirty", true)
        dialog.findViewById<EditText>(R.id.card_title_edittext)!!.setText("Changed")
        var done = false
        var failure: Exception? = null
        dialog.setCallback(object : Callback<Card>() {
            override fun onDone(result: Card) { done = true }
            override fun onFail(error: Exception?) { failure = error }
        })

        dialog.findViewById<android.view.View>(R.id.positiveBtn)!!.performClick()

        assertFalse(done)
        assertTrue(failure is IOException)
        assertTrue(dialog.isShowing)
        assertSame(editedCard, getPrivateField(dialog, "card"))
        assertEquals(original, editedCard)
        assertEquals(image.name, getPrivateField(dialog, "existingImagePath"))
        assertEquals(audio.name, getPrivateField(dialog, "existingAudioPath"))
        assertEquals(true, getPrivateField(dialog, "imageDirty"))
        assertEquals(true, getPrivateField(dialog, "audioDirty"))
        assertSame(missingAudio, getPrivateField(dialog, "currentAudio"))
        assertEquals("old image", image.readText())
        assertEquals("old audio", audio.readText())
        assertEquals(setOf(image.name, audio.name), workingDir.list()!!.toSet())
        assertEquals(activity.getString(R.string.set_save_error), ShadowToast.getTextOfLatestToast())
        dialog.dismiss()
    }

    @Test
    fun generatedAudioIsNotPlayableOrSaveableUntilDone() {
        val dialog = standardDialog()
        val engine = ControlledTextToSpeech.latest
        startGeneration(dialog)
        assertTrue(engine.requests.isEmpty())
        assertFalse(dialog.validate())
        assertFalse(dialog.findViewById<Button>(R.id.play_audio_button)!!.isEnabled)
        assertFalse(dialog.findViewById<View>(R.id.positiveBtn)!!.isEnabled)

        engine.initialization.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        val (id, file) = engine.requests.single()
        file.writeText("audio")
        assertFalse(dialog.validate())
        engine.progress.onDone(id)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(dialog.validate())
        assertTrue(dialog.findViewById<Button>(R.id.play_audio_button)!!.isEnabled)
        assertTrue(dialog.findViewById<View>(R.id.positiveBtn)!!.isEnabled)
        assertEquals(file, getPrivateField(dialog, "currentAudio"))
        dialog.dismiss()
        file.delete()
    }

    @Test
    fun generationErrorClearsAudioAndAllowsRetry() {
        val dialog = standardDialog()
        val engine = ControlledTextToSpeech.latest
        engine.initialization.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        startGeneration(dialog)
        @Suppress("DEPRECATION")
        engine.progress.onError(engine.requests.single().first)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(getPrivateField(dialog, "currentAudio"))
        assertFalse(dialog.validate())
        assertFalse(dialog.findViewById<Button>(R.id.play_audio_button)!!.isEnabled)
        assertTrue(dialog.findViewById<View>(R.id.positiveBtn)!!.isEnabled)
        startGeneration(dialog)
        assertEquals(2, engine.requests.size)
        assertFalse(dialog.findViewById<View>(R.id.positiveBtn)!!.isEnabled)
        dialog.dismiss()
    }

    @Test
    fun dismissedGenerationCannotChangeReopenedCard() {
        val dialog = standardDialog()
        val engine = ControlledTextToSpeech.latest
        engine.initialization.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        startGeneration(dialog)
        val (oldId, _) = engine.requests.single()
        dialog.dismiss()
        assertTrue(engine.stopped)
        dialog.show(set, Card(42, cardType = 1))
        engine.progress.onDone(oldId)
        @Suppress("DEPRECATION")
        engine.progress.onError(oldId)
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(getPrivateField(dialog, "currentAudio"))
        assertFalse(getPrivateField(dialog, "audioDirty") as Boolean)
        assertFalse(dialog.findViewById<Button>(R.id.play_audio_button)!!.isEnabled)
        assertTrue(dialog.validate())
        dialog.dismiss()
    }

    @Test
    fun hostStopCancelsGenerationButKeepsEngineReusable() {
        val dialog = standardDialog()
        val engine = ControlledTextToSpeech.latest
        engine.initialization.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        startGeneration(dialog)
        val oldId = engine.requests.single().first

        dialog.onHostStop()
        assertTrue(engine.stopped)
        assertFalse(engine.closed)
        engine.progress.onDone(oldId)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(getPrivateField(dialog, "currentAudio"))
        assertFalse(dialog.validate())
        assertTrue(dialog.findViewById<View>(R.id.positiveBtn)!!.isEnabled)

        startGeneration(dialog)
        val (id, file) = engine.requests.last()
        file.writeText("audio")
        engine.progress.onDone(id)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(dialog.validate())
        dialog.release()
        file.delete()
    }

    @Test
    fun releaseCancelsGenerationBeforeClosingEngineAndIgnoresLateEvents() {
        val dialog = standardDialog()
        val engine = ControlledTextToSpeech.latest
        engine.initialization.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        startGeneration(dialog)
        val (id, file) = engine.requests.single()
        file.writeText("partial")

        dialog.release()
        dialog.release()
        assertTrue(engine.stopped)
        assertTrue(engine.closed)
        assertFalse(file.exists())
        assertFalse(dialog.isShowing)
        engine.progress.onDone(id)
        @Suppress("DEPRECATION")
        engine.progress.onError(id)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(getPrivateField(dialog, "currentAudio"))
        assertFalse(getPrivateField(dialog, "audioDirty") as Boolean)
        assertFalse(dialog.findViewById<Button>(R.id.play_audio_button)!!.isEnabled)
    }

    @Test
    fun oldInputPromptCannotStartGenerationForReopenedCard() {
        val dialog = standardDialog()
        val engine = ControlledTextToSpeech.latest
        dialog.findViewById<Button>(R.id.generate_audio_button)!!.performClick()
        val prompt = ShadowAlertDialog.getLatestAlertDialog()
        prompt.findViewById<EditText>(R.id.input_prompt)!!.setText("Old input")
        dialog.dismiss()
        dialog.show(set, Card(42, cardType = 1))
        prompt.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        engine.initialization.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(engine.requests.isEmpty())
        assertNull(getPrivateField(dialog, "currentAudio"))
        assertTrue(dialog.validate())
        dialog.dismiss()
    }

    private fun standardDialog(): EditCardDialog {
        val dialog = EditCardDialog(activity)
        dialog.show(set, null)
        dialog.findViewById<EditText>(R.id.card_title_edittext)!!.setText("Hello")
        setPrivateField(dialog, "currentBitmap", Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        return dialog
    }

    private fun startGeneration(dialog: EditCardDialog) {
        dialog.findViewById<Button>(R.id.generate_audio_button)!!.performClick()
        val prompt = ShadowAlertDialog.getLatestAlertDialog()
        prompt.findViewById<EditText>(R.id.input_prompt)!!.setText("Hello")
        prompt.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun getPrivateField(target: Any, fieldName: String): Any? {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(target)
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
