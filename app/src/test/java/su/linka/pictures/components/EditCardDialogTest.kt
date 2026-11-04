package su.linka.pictures.components

import android.graphics.Bitmap
import android.widget.EditText
import android.widget.RadioGroup
import androidx.fragment.app.FragmentActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.linka.pictures.Card
import su.linka.pictures.Callback
import su.linka.pictures.R
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
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
