package su.linka.pictures.components

import android.view.View
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
import su.linka.pictures.SetsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "w320dp-h480dp")
class EditCardDialogAccessibilityTest {
    @Test fun editorHasPersistentTitleLabelScrollableControlsAndNamedDeleteTarget() {
        val owner = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val set = SetsManager(owner.get()).createSet()
        val dialog = EditCardDialog(owner.get())
        try {
            dialog.show(set, null)
            val root = dialog.findViewById<android.view.ViewGroup>(android.R.id.content)!!
            assertTrue(root.getChildAt(0) is ScrollView)
            val title = dialog.findViewById<EditText>(R.id.card_title_edittext)!!
            val label = dialog.findViewById<TextView>(R.id.card_title_label)!!
            title.setText("User text")
            assertEquals(title.id, label.labelFor)
            assertEquals(owner.get().getString(R.string.card_title), label.text.toString())
            val delete = dialog.findViewById<View>(R.id.delete_button)!!
            assertEquals(owner.get().getString(R.string.delete), delete.contentDescription)
            assertTrue(delete.layoutParams.width >= (48 * owner.get().resources.displayMetrics.density).toInt())
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                dialog.findViewById<View>(R.id.image)!!.importantForAccessibility)
        } finally {
            dialog.release()
            set.close()
            owner.pause().stop().destroy()
        }
    }
}
