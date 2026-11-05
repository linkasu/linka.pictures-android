@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.bootstrap

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL
import platform.ImageIO.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*

/** Decodes only a bounded display copy; canonical media is never written or re-encoded. */
internal class IosThumbnailDecoder(private val inspection: MediaInspectionPort) {
    suspend fun decode(file: LocalFile, maximumSide: Int): OperationResult<ImageBitmap> = withContext(Dispatchers.IO) {
        if (maximumSide !in 1..256) return@withContext failed(ProblemCode.LIMIT_EXCEEDED)
        when (val inspected = inspection.image(file)) {
            is OperationResult.Completed -> Unit
            is OperationResult.Failed -> return@withContext inspected
            OperationResult.Cancelled -> return@withContext OperationResult.Cancelled
            OperationResult.UnconfirmedCommit -> return@withContext OperationResult.UnconfirmedCommit
        }
        try {
            currentCoroutineContext().ensureActive()
            val url = CFBridgingRetain(NSURL.fileURLWithPath(file.path)) ?: error("Missing URL")
            val bitmap = try {
                val source = CGImageSourceCreateWithURL(url.reinterpret(), null) ?: return@withContext failed(ProblemCode.INVALID_DATA)
                try {
                    memScoped {
                        val value = alloc<IntVar>(); value.value = maximumSide
                        val maximum = CFNumberCreate(null, kCFNumberIntType, value.ptr) ?: error("Missing size")
                        try {
                            val options = CFDictionaryCreateMutable(null, 0, null, null) ?: error("Missing options")
                            try {
                                CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, maximum)
                                CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
                                CFDictionarySetValue(options, kCGImageSourceCreateThumbnailWithTransform, kCFBooleanTrue)
                                CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
                                CFDictionarySetValue(options, kCGImageSourceShouldAllowFloat, kCFBooleanFalse)
                                val image = CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
                                    ?: return@withContext failed(ProblemCode.INVALID_DATA)
                                try {
                                    val width = CGImageGetWidth(image).toInt()
                                    val height = CGImageGetHeight(image).toInt()
                                    if (width !in 1..maximumSide || height !in 1..maximumSide)
                                        return@withContext failed(ProblemCode.LIMIT_EXCEEDED)
                                    val bytes = ByteArray(width * height * 4)
                                    val color = CGColorSpaceCreateDeviceRGB() ?: error("Missing color space")
                                    try {
                                        bytes.usePinned { pixels ->
                                            val context = CGBitmapContextCreate(pixels.addressOf(0), width.toULong(), height.toULong(),
                                                8u, (width * 4).toULong(), color,
                                                CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big)
                                                ?: error("Missing bitmap context")
                                            try {
                                                CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
                                                CGContextFlush(context)
                                            } finally { CGContextRelease(context) }
                                        }
                                        // At most 256 KiB of raw RGBA, not a full original NSData/UIImage.
                                        Image.makeRaster(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL),
                                            bytes, width * 4).toComposeImageBitmap()
                                    } finally { CGColorSpaceRelease(color) }
                                } finally { CGImageRelease(image) }
                            } finally { CFRelease(options) }
                        } finally { CFRelease(maximum) }
                    }
                } finally { CFRelease(source) }
            } finally { CFRelease(url) }
            currentCoroutineContext().ensureActive()
            OperationResult.Completed(bitmap)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed(ProblemCode.IO_FAILURE) }
    }

    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))
}
