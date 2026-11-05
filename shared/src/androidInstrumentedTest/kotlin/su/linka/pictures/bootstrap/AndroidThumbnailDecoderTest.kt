package su.linka.pictures.bootstrap

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.LocalFile
import su.linka.pictures.platform.capabilities.AndroidMediaInspectionPort

class AndroidThumbnailDecoderTest {
    private val decoder = AndroidThumbnailDecoder(AndroidMediaInspectionPort())
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun image(width: Int, height: Int, format: Bitmap.CompressFormat): File {
        val root = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(root, "root-thumb-${UUID.randomUUID()}.${if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"}")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { assertTrue(bitmap.compress(format, 90, it)) } }
        finally { bitmap.recycle() }
        return file
    }

    @Test fun actualNativePixelsAreBoundedAndTheOriginalPngIsUnchanged() = runBlocking {
        val file = image(1024, 256, Bitmap.CompressFormat.PNG)
        try {
            val before = digest(file)
            val result = decoder.decode(LocalFile(file.absolutePath), 64)
            assertTrue(result is OperationResult.Completed)
            val bitmap = (result as OperationResult.Completed).value
            assertTrue(bitmap.width in 1..64 && bitmap.height in 1..64)
            assertEquals(ImageBitmapConfig.Argb8888, bitmap.config)
            assertEquals(before, digest(file))
        } finally { assertTrue(file.delete()) }
    }

    @Test fun jpegExifOrientationIsDisplayedWithoutRewritingItsBytes() = runBlocking {
        val file = image(12, 24, Bitmap.CompressFormat.JPEG)
        try {
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val before = digest(file)
            val result = decoder.decode(LocalFile(file.absolutePath), 64)
            assertTrue(result is OperationResult.Completed)
            val bitmap = (result as OperationResult.Completed).value
            assertEquals(24, bitmap.width)
            assertEquals(12, bitmap.height)
            assertEquals(before, digest(file))
        } finally { assertTrue(file.delete()) }
    }
}
