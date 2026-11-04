package su.linka.pictures

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
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

    @Test fun thumbnailIsBoundedWhileSnapshotKeepsOriginalPixelsAndBytes() {
        val file = folder.newFile("photo.png")
        val original = Bitmap.createBitmap(2049, 1025, Bitmap.Config.ARGB_8888)
        file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        original.recycle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snapshot = BitmapDecoding.snapshot(context, Uri.fromFile(file))
        try {
            assertArrayEquals(file.readBytes(), snapshot.readBytes())
            val decoded = BitmapDecoding.decode(snapshot, 512)
            assertTrue(decoded.width in 1..512)
            assertTrue(decoded.height in 1..512)
            assertTrue(decoded.width > decoded.height)
            decoded.recycle()
        } finally { snapshot.delete() }
    }

    @Test fun invalidAndOversizedInputIsRejectedAndTemporarySnapshotIsRemoved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bad = folder.newFile("bad").apply { writeText("not an image") }
        val before = context.cacheDir.listFiles()!!.map { it.name }.toSet()
        assertThrows(IOException::class.java) { BitmapDecoding.decode(bad, 512) }
        assertThrows(IOException::class.java) { BitmapDecoding.decode(context, Uri.fromFile(bad), 512) }
        val oversized = folder.newFile("oversized")
        RandomAccessFile(oversized, "rw").use { it.setLength(ArchiveLimits().maxMediaBytes + 1) }
        assertThrows(IOException::class.java) { BitmapDecoding.decode(oversized, 512) }
        assertThrows(IOException::class.java) { BitmapDecoding.decode(context, Uri.fromFile(oversized), 512) }
        assertEquals(before, context.cacheDir.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun editorSavesOriginalPhotoNotItsPreviewAndKeepsDurableSnapshotAfterDismiss() {
        val owner = org.robolectric.Robolectric.buildActivity(androidx.fragment.app.FragmentActivity::class.java).setup()
        val activity = owner.get()
        val source = SetsManager(activity).createSet()
        val input = folder.newFile("selected.png")
        val full = Bitmap.createBitmap(2049, 1025, Bitmap.Config.ARGB_8888)
        input.outputStream().use { assertTrue(full.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        full.recycle()
        input.copyTo(java.io.File(source.getFolder(), "old.png"))
        java.io.File(source.getFolder(), "borrowed.audio").writeText("audio")
        val dialog = su.linka.pictures.components.EditCardDialog(activity)
        try {
            dialog.show(source, Card(0, "old.png", "Photo", "borrowed.audio", 0))
            awaitHostWork(dialog)
            dialog.findViewById<android.view.View>(R.id.choose_image_button)!!.performClick()
            val media = folder.newFolder("durable-media")
            val pending = dialog.captureState(media)!!
            dialog.dismiss()
            dialog.restoreState(source, pending, media)
            // The provider can return while the restored old thumbnail is still decoding.
            dialog.onActivityResult(pending.getInt("imageRequestCode"), android.app.Activity.RESULT_OK,
                android.content.Intent().setData(Uri.fromFile(input)))
            awaitHostWork(dialog)
            val state = dialog.captureState(media)!!
            val durable = java.io.File(media, state.getString("image"))
            assertArrayEquals(input.readBytes(), durable.readBytes())
            val field = dialog.javaClass.getDeclaredField("currentBitmap").apply { isAccessible = true }
            assertTrue((field.get(dialog) as Bitmap).width <= 1024)
            val owned = dialog.javaClass.getDeclaredField("ownedImage").apply { isAccessible = true }.get(dialog) as java.io.File
            var saved: Card? = null
            dialog.setCallback(object : Callback<Card>() {
                override fun onDone(result: Card) { saved = result }
                override fun onFail(error: Exception?) { throw AssertionError(error) }
            })
            dialog.findViewById<android.view.View>(R.id.positiveBtn)!!.performClick()
            awaitHostWork(dialog)
            assertNotNull(saved)
            assertArrayEquals(input.readBytes(), java.io.File(source.getFolder(), saved!!.imagePath!!).readBytes())
            assertFalse(owned.exists())
            assertTrue(durable.exists())
            assertTrue(java.io.File(source.getFolder(), "borrowed.audio").exists())
        } finally {
            dialog.release()
            owner.pause().stop().destroy()
            source.close()
        }
    }
}
