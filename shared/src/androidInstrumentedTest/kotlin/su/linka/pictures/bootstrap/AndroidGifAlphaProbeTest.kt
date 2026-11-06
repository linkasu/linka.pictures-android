package su.linka.pictures.bootstrap

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ports.LocalFile
import su.linka.pictures.platform.capabilities.AndroidMediaInspectionPort

class AndroidGifAlphaProbeTest {
    @Test fun measuresPublicGifPixelsWithoutChangingSourceBytes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val inspection = AndroidMediaInspectionPort()
        val decoder = AndroidThumbnailDecoder(inspection)
        val fixtures = listOf(
            Triple("unnamed (4).gif", 364 to 228, "4bbd53973ea39c9df2dc14bee3a8e45bc36e79414efd204225d1688f009a6aeb"),
            Triple("unnamed (2).gif", 390 to 222, "18cfd53a71a7c1881a60bb43314106d2cff1c7505ac88ab56502208ba9d0a396"),
            Triple("unnamed (3).gif", 350 to 350, "b98b30f483099c0f132c2245dfeec98fb5ac53284ad2495c121cccf8e6ee53c6"),
        )
        for ((name, size, expectedSha) in fixtures) {
            val bytes = instrumentation.context.assets.open("u14-alpha/$name").use { it.readBytes() }
            assertEquals(expectedSha, sha(bytes))
            val source = File.createTempFile("gif-alpha-probe-", ".gif", instrumentation.targetContext.cacheDir)
            try {
                source.writeBytes(bytes)
                val file = LocalFile(source.absolutePath)
                val inspected = inspection.image(file)
                assertTrue("Inspection: $inspected", inspected is OperationResult.Completed)
                val info = (inspected as OperationResult.Completed).value
                assertEquals(size.first, info.width)
                assertEquals(size.second, info.height)
                assertEquals("image/gif", info.mediaType)
                val direct = checkNotNull(BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().also {
                    it.inPreferredConfig = Bitmap.Config.ARGB_8888
                    it.inScaled = false
                }))
                val directRecord = try {
                    assertEquals(size.first, direct.width)
                    assertEquals(size.second, direct.height)
                    pixels(direct.width, direct.height, direct.hasAlpha()) { x, y -> direct.getPixel(x, y) }
                } finally { direct.recycle() }
                val decoded = decoder.decode(file, 256)
                assertTrue("Thumbnail: $decoded", decoded is OperationResult.Completed)
                val image = (decoded as OperationResult.Completed).value
                val native = image.asAndroidBitmap()
                val thumbnailRecord = try {
                    assertTrue(image.width in 1..256 && image.height in 1..256)
                    val map = image.toPixelMap()
                    pixels(image.width, image.height, native.hasAlpha()) { x, y -> map[x, y].toArgb() }
                } finally { native.recycle() }
                val movieRecord = if (Build.VERSION.SDK_INT == 21) moviePixels(source, size, thumbnailRecord) else null
                assertArrayEquals(bytes, source.readBytes())
                assertEquals(expectedSha, sha(source.readBytes()))
                val record = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS[0])
                    .put("fixture", name).put("sourceSHA256", expectedSha).put("sourceUnchanged", true)
                    .put("thumbnailBranch", if (Build.VERSION.SDK_INT >= 28) "ImageDecoder/software" else "GlideGifDecoder/first-frame")
                    .put("directBitmapFactory", directRecord).put("actualThumbnail", thumbnailRecord)
                    .put("legacyMovie", movieRecord ?: JSONObject.NULL)
                    .put("status", if (thumbnailRecord.getInt("alphaBelow255") > 0) "TRANSPARENCY_PRESENT" else "TRANSPARENCY_ABSENT")
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "GIF_ALPHA_PROBE $record\n") })
                assertTrue("GIF thumbnails must retain transparent pixels on every supported API", thumbnailRecord.getInt("alphaBelow255") > 0)
            } finally { assertTrue("Owned temporary fixture cleanup", source.delete()) }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION") // Movie is deprecated on modern Android; this comparison runs only on API 21.
    private fun moviePixels(source: File, size: Pair<Int, Int>, thumbnail: JSONObject): JSONObject {
        val started = SystemClock.elapsedRealtimeNanos()
        val movie = checkNotNull(Movie.decodeFile(source.absolutePath))
        assertEquals(size.first, movie.width())
        assertEquals(size.second, movie.height())
        val target = Bitmap.createBitmap(thumbnail.getInt("width"), thumbnail.getInt("height"), Bitmap.Config.ARGB_8888)
        try {
            target.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(target)
            canvas.scale(target.width.toFloat() / movie.width(), target.height.toFloat() / movie.height())
            val timeChanged = movie.setTime(0)
            movie.draw(canvas, 0f, 0f)
            val elapsedNanos = SystemClock.elapsedRealtimeNanos() - started
            return pixels(target.width, target.height, target.hasAlpha()) { x, y -> target.getPixel(x, y) }
                .put("sourceWidth", movie.width()).put("sourceHeight", movie.height())
                .put("timeMillis", 0).put("setTimeChanged", timeChanged).put("decodeDrawElapsedNanos", elapsedNanos)
        } finally { target.recycle() }
    }

    private fun pixels(width: Int, height: Int, hasAlpha: Boolean, pixel: (Int, Int) -> Int): JSONObject {
        var transparent = 0
        for (y in 0 until height) for (x in 0 until width) if (pixel(x, y).ushr(24) < 255) transparent++
        val corners = JSONArray()
        for ((x, y) in listOf(0 to 0, (width - 1) to 0, 0 to (height - 1), (width - 1) to (height - 1))) {
            val argb = pixel(x, y)
            corners.put(JSONObject().put("x", x).put("y", y).put("alpha", argb.ushr(24)).put("argb", "%08x".format(argb)))
        }
        return JSONObject().put("width", width).put("height", height).put("nativeHasAlpha", hasAlpha)
            .put("alphaBelow255", transparent).put("pixelCount", width * height).put("corners", corners)
    }
}
