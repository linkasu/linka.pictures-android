package su.linka.pictures.components

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import su.linka.pictures.activity.ComposeHostClock
import su.linka.pictures.activity.composeNodes
import su.linka.pictures.awaitWork
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetImageTest {
    @Test fun changedFileAtSamePathGetsNewBitmapAndDisposalClearsRequest() {
        val owner = Robolectric.buildActivity(AppCompatActivity::class.java).setup()
        val clock = ComposeHostClock()
        val activity = owner.get()
        val file = File(activity.cacheDir, "same-path.png")
        val refresh = mutableStateOf(false)
        fun write(color: Int) {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
        fun image(view: View): ImageView? = when (view) {
            is ImageView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { image(view.getChildAt(it)) }
            else -> null
        }
        try {
            write(Color.RED)
            activity.setContent { SetImage(file, Modifier.testTag(if (refresh.value) "changed" else "initial")) }
            composeNodes(activity)
            val target = checkNotNull(image(activity.findViewById(android.R.id.content)))
            awaitWork { (target.drawable as? BitmapDrawable)?.bitmap?.getPixel(0, 0) == Color.RED }
            val modified = file.lastModified()
            write(Color.BLUE)
            check(file.setLastModified(modified + 2000))
            refresh.value = true
            composeNodes(activity)
            awaitWork {
                composeNodes(activity)
                (target.drawable as? BitmapDrawable)?.bitmap?.getPixel(0, 0) == Color.BLUE
            }
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, target.importantForAccessibility)
            owner.pause().stop().destroy()
            awaitWork { target.drawable == null }
        } finally {
            if (!activity.isDestroyed) owner.pause().stop().destroy()
            clock.close()
            file.delete()
        }
    }
}
