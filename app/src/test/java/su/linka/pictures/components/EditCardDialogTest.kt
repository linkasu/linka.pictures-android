package su.linka.pictures.components

import android.graphics.Bitmap
import android.widget.EditText
import android.widget.RadioGroup
import androidx.fragment.app.FragmentActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.linka.pictures.Card
import java.io.File

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

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
