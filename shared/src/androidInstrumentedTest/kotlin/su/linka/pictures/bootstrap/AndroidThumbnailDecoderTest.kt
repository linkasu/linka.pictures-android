package su.linka.pictures.bootstrap

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ProblemCode
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

    @Test fun gifMetadataBombAndOutOfCanvasFramesAreRejectedBeforeDecode() = runBlocking {
        val header = "GIF89a".toByteArray() + byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0, 0, 0, 0, -1, -1, -1)
        val frame = byteArrayOf(0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0)
        val oversized = frame.copyOf().also { it[5] = -1; it[6] = -1 }
        val comment = byteArrayOf(0x21, 0xfe.toByte(), 0)
        val cases = listOf(
            (header + frame + oversized + byteArrayOf(0x3b)) to ProblemCode.INVALID_DATA,
            (header + ByteArray(4097 * frame.size) { frame[it % frame.size] } + byteArrayOf(0x3b)) to ProblemCode.LIMIT_EXCEEDED,
            (header + ByteArray(16_384 * 3) { comment[it % 3] } + frame + byteArrayOf(0x3b)) to ProblemCode.LIMIT_EXCEEDED,
        )
        for ((bytes, code) in cases) {
            val file = File.createTempFile("gif-bounds-", ".gif", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
            try {
                file.writeBytes(bytes)
                val result = decoder.decode(LocalFile(file.absolutePath), 64)
                assertTrue("Expected bounded GIF failure, got $result", result is OperationResult.Failed)
                assertEquals(code, (result as OperationResult.Failed).problem.code)
                assertArrayEquals(bytes, file.readBytes())
            } finally { assertTrue(file.delete()) }
        }
    }

    @Test fun veryNarrowGifIsSampledWithoutZeroSizedBuffers() = runBlocking {
        val bytes = "GIF89a".toByteArray() + byteArrayOf(1, 0, 0, 32, 0x80.toByte(), 0, 0, 0, 0, 0, -1, -1, -1,
            0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3b)
        val file = File.createTempFile("gif-narrow-", ".gif", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
        try {
            file.writeBytes(bytes)
            val result = decoder.decode(LocalFile(file.absolutePath), 64)
            assertTrue("Expected bounded narrow GIF, got $result", result is OperationResult.Completed)
            val image = (result as OperationResult.Completed).value
            try {
                assertEquals(1, image.width)
                assertEquals(64, image.height)
                assertArrayEquals(bytes, file.readBytes())
            } finally { image.asAndroidBitmap().recycle() }
        } finally { assertTrue(file.delete()) }
    }
}
