@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.bootstrap

import androidx.compose.ui.graphics.ImageBitmapConfig
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import okio.ByteString.Companion.decodeBase64
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.Foundation.*
import platform.ImageIO.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.LocalFile
import su.linka.pictures.platform.capabilities.IosMediaInspectionPort
import kotlin.test.*

class IosThumbnailDecoderTest {
    private val decoder = IosThumbnailDecoder(IosMediaInspectionPort { error("Not an audio test") })

    @Test fun realOwnedPngHasBoundedArgbPixelsAndCanonicalHashDoesNotChange() = runTest {
        withContext(Dispatchers.IO) {
            val path = (NSTemporaryDirectory() + NSUUID().UUIDString + ".png").toPath()
            try {
                writePng(path.toString())
                val before = FileSystem.SYSTEM.source(path).buffer().use { it.readByteString().sha256() }
                for (side in listOf(32, 64, 256)) {
                    val result = assertIs<OperationResult.Completed<androidx.compose.ui.graphics.ImageBitmap>>(
                        decoder.decode(LocalFile(path.toString()), side)).value
                    assertTrue(result.width in 1..side && result.height in 1..side)
                    assertEquals(ImageBitmapConfig.Argb8888, result.config)
                    val pixels = IntArray(result.width * result.height)
                    result.readPixels(pixels)
                    assertTrue(pixels.any { it ushr 24 != 0 })
                }
                assertEquals(before, FileSystem.SYSTEM.source(path).buffer().use { it.readByteString().sha256() })
            } finally { FileSystem.SYSTEM.delete(path, mustExist = false) }
        }
    }

    @Test fun excessiveThumbnailLimitsFailBeforeAnyFileRead() = runTest {
        for (side in listOf(-1, 0, 257, Int.MAX_VALUE)) {
            assertEquals(OperationResult.Failed(Problem(ProblemCode.LIMIT_EXCEEDED)),
                decoder.decode(LocalFile("/not-owned-and-never-opened.png"), side))
        }
    }

    @Test fun asymmetricPngRetainsTopLeftRowOrderWithoutFlipOrTranspose() = runTest {
        withContext(Dispatchers.IO) {
            // Standard RGBA PNG: row 0 RED/GREEN, row 1 BLUE/WHITE; no orientation metadata.
            val bytes = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEklEQVR4nGP4z8DwHwyBNBgAAEnICff5q7YNAAAAAElFTkSuQmCC".decodeBase64()!!
            val path = (NSTemporaryDirectory() + NSUUID().UUIDString + ".png").toPath()
            try {
                FileSystem.SYSTEM.write(path) { write(bytes) }
                val before = FileSystem.SYSTEM.source(path).buffer().use { it.readByteString().sha256() }
                val image = assertIs<OperationResult.Completed<androidx.compose.ui.graphics.ImageBitmap>>(
                    decoder.decode(LocalFile(path.toString()), 2)).value
                assertEquals(2, image.width)
                assertEquals(2, image.height)
                assertEquals(ImageBitmapConfig.Argb8888, image.config)
                val actual = IntArray(4)
                image.readPixels(actual)
                assertContentEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffffff.toInt()), actual)
                assertEquals(before, FileSystem.SYSTEM.source(path).buffer().use { it.readByteString().sha256() })
            } finally { FileSystem.SYSTEM.delete(path, mustExist = false) }
        }
    }

    private fun writePng(path: String) {
        val space = CGColorSpaceCreateDeviceRGB()!!
        try {
            val context = CGBitmapContextCreate(null, 512uL, 256uL, 8uL, 2048uL, space,
                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value)!!
            try {
                CGContextSetRGBFillColor(context, 0.2, 0.6, 0.9, 1.0)
                CGContextFillRect(context, CGRectMake(0.0, 0.0, 512.0, 256.0))
                val image = CGBitmapContextCreateImage(context)!!
                try {
                    val url = CFBridgingRetain(NSURL.fileURLWithPath(path))!!
                    try {
                        val type = CFStringCreateWithCString(null, "public.png", kCFStringEncodingUTF8)!!
                        try {
                            val output = CGImageDestinationCreateWithURL(url.reinterpret(), type, 1u, null)!!
                            try {
                                CGImageDestinationAddImage(output, image, null)
                                check(CGImageDestinationFinalize(output))
                            } finally { CFRelease(output) }
                        } finally { CFRelease(type) }
                    } finally { CFRelease(url) }
                } finally { CGImageRelease(image) }
            } finally { CGContextRelease(context) }
        } finally { CGColorSpaceRelease(space) }
    }
}
