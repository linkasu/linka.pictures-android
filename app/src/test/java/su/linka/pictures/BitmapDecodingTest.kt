package su.linka.pictures

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BitmapDecodingTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun decodeBoundsRealPixelsAndRejectsInvalidOrOversizedInput() {
        val file = folder.newFile("photo.png")
        val original = Bitmap.createBitmap(2049, 1025, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            original.recycle()
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (decoded in listOf(
            BitmapDecoding.decode(file, 512),
            BitmapDecoding.decode(context, Uri.fromFile(file), 512)
        )) {
            try {
                assertTrue(decoded.width in 1..512)
                assertTrue(decoded.height in 1..512)
                assertTrue(decoded.width > decoded.height)
            } finally {
                decoded.recycle()
            }
        }
        val bad = folder.newFile("bad").apply { writeText("not an image") }
        assertThrows(IOException::class.java) { BitmapDecoding.decode(bad, 512) }
        assertThrows(IOException::class.java) { BitmapDecoding.decode(context, Uri.fromFile(bad), 512) }
        val oversized = folder.newFile("oversized")
        RandomAccessFile(oversized, "rw").use { it.setLength(ArchiveLimits().maxMediaBytes + 1) }
        assertThrows(IOException::class.java) { BitmapDecoding.decode(oversized, 512) }
        val before = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        assertThrows(IOException::class.java) { BitmapDecoding.decode(context, Uri.fromFile(oversized), 512) }
        assertEquals(before, context.cacheDir.listFiles()!!.map { it.name }.toSet())
    }
}
