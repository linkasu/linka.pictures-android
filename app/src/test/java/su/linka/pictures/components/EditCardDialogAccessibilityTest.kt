package su.linka.pictures.components

import android.view.LayoutInflater
import android.view.View
import android.content.res.Configuration
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.linka.pictures.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditCardDialogAccessibilityTest {
    @Test @Config(qualifiers = "en")
    fun englishControlsHaveAccessibleNames() = checkControls()

    @Test @Config(qualifiers = "ru")
    fun russianControlsHaveAccessibleNames() = checkControls()

    @Test fun largeFontContentScrollsToSaveOnCompactWindow() = checkScrolling(320)

    @Test @Config(qualifiers = "en-w600dp-h480dp")
    fun largeFontContentScrollsToSaveOnTabletWindow() = checkScrolling(600)

    private fun checkScrolling(widthDp: Int) {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val activity = controller.get()
            val context = activity.createConfigurationContext(
                Configuration(activity.resources.configuration).apply { fontScale = 2f })
            val root = LayoutInflater.from(activity).cloneInContext(context)
                .inflate(R.layout.edit_card_dialog, null) as ScrollView
            activity.setContentView(root)
            val density = activity.resources.displayMetrics.density
            root.measure(
                View.MeasureSpec.makeMeasureSpec((widthDp * density).toInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((480 * density).toInt(), View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            assertTrue(root.canScrollVertically(1))
            val save = root.findViewById<View>(R.id.positiveBtn)
            root.scrollTo(0, root.getChildAt(0).height)
            assertTrue(save.height >= 48 * density)
            assertTrue(save.bottom <= root.scrollY + root.height)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun checkControls() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val activity = controller.get()
            // Inflate the real layout without constructing the dialog's TTS or requesting permissions.
            val root = LayoutInflater.from(activity).inflate(R.layout.edit_card_dialog, null)
            activity.setContentView(root)
            assertTrue(root is ScrollView)
            val delete = root.findViewById<View>(R.id.delete_button)
            assertEquals(activity.getString(R.string.delete), delete.createAccessibilityNodeInfo().contentDescription)
            val title = root.findViewById<EditText>(R.id.card_title_edittext)
            title.setText("Already entered")
            val label = root.findViewById<TextView>(R.id.card_title_label)
            assertEquals(title.id, label.labelFor)
            assertEquals(activity.getString(R.string.card_title), label.text.toString())
            listOf(
                R.id.choose_image_button to R.string.choose_image,
                R.id.generate_image_button to R.string.generate_image,
                R.id.generate_audio_button to R.string.generate_audio,
                R.id.record_audio_button to R.string.record_audio,
                R.id.play_audio_button to R.string.play_audio,
            ).forEach { (id, text) ->
                val button = root.findViewById<TextView>(id)
                assertTrue(activity.getString(text).equals(
                    button.createAccessibilityNodeInfo().text.toString(), ignoreCase = true))
                assertTrue(button.minimumHeight >= (48 * activity.resources.displayMetrics.density).toInt())
            }
            assertFalse(root.findViewById<View>(R.id.play_audio_button).isEnabled)
            assertFalse(root.findViewById<View>(R.id.image).isFocusable)
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                root.findViewById<View>(R.id.image).importantForAccessibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
