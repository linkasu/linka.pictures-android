package su.linka.pictures.activity

import android.content.Context
import android.content.Intent
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
import su.linka.pictures.ui.EditorAction
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
            su.linka.pictures.awaitHostWork(activity)
            val field = SetEditActivity::class.java.getDeclaredField("set").apply { isAccessible = true }
            val manifest = (field.get(activity) as Set).getManifest()
            assertComposeHost(activity)
            // Compose dimension controls emit Int values, never unparsed text.
            for (invalid in listOf(0, -1, 33, Int.MAX_VALUE)) {
                activity.onAction(EditorAction.Resize(invalid, 4))
                assertEquals(2, manifest.rows)
                assertEquals(4, manifest.columns)
                assertEquals(8, activity.document!!.rows * activity.document!!.columns)
                assertEquals(activity.getString(R.string.invalid_grid_dimensions), ShadowToast.getTextOfLatestToast())
            }
        } finally {
            controller.pause().stop().destroy()
            archive.delete()
        }
    }
}
