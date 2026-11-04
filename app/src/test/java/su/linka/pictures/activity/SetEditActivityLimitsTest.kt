package su.linka.pictures.activity

import android.content.Context
import android.content.Intent
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.SetsManager
import su.linka.pictures.components.CardGrid
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SetEditActivityLimitsTest {
    @Test fun invalidInputShowsMessageWithoutChangingManifestOrGrid() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val archive = File(SetsManager(context).getSetsDirectory(), "limits.linka")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("config.json"))
            zip.write("""{"rows":2,"columns":4}""".toByteArray())
            zip.closeEntry()
        }
        val intent = Intent(context, SetEditActivity::class.java).putExtra(SetEditActivity.EXTRA_FILE, archive.name)
        val controller = Robolectric.buildActivity(SetEditActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            val field = SetEditActivity::class.java.getDeclaredField("set").apply { isAccessible = true }
            val manifest = (field.get(activity) as Set).getManifest()
            val grid = activity.findViewById<CardGrid>(R.id.card_grid)
            for (invalid in listOf("", "abc", "0", "-1", "33", "2147483648")) {
                activity.findViewById<EditText>(R.id.rows_count).setText(invalid)
                activity.findViewById<EditText>(R.id.columns_count).setText("4")
                activity.findViewById<android.view.View>(R.id.change_grid_size_button).performClick()
                assertEquals(2, manifest.rows)
                assertEquals(4, manifest.columns)
                assertEquals(8, grid.getPageSize())
                assertEquals(activity.getString(R.string.invalid_grid_dimensions), ShadowToast.getTextOfLatestToast())
            }
        } finally {
            controller.pause().stop().destroy()
            archive.delete()
        }
    }
}
