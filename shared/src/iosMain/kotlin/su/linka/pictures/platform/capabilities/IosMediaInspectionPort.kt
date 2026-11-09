@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package su.linka.pictures.platform.capabilities

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import okio.IOException
import okio.Path.Companion.toPath
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.Foundation.NSURL
import platform.Foundation.CFBridgingRetain
import platform.ImageIO.*
import platform.UniformTypeIdentifiers.UTType
import platform.posix.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.media.validateImageEnvelope
import su.linka.pictures.text.isNativeText
import kotlin.math.floor

internal class IosMediaInspectionPort(
    private val inspectAudio: suspend (LocalFile) -> OperationResult<AudioInfo>,
) : MediaInspectionPort {
    override suspend fun audio(file: LocalFile): OperationResult<AudioInfo> = inspectAudio(file)

    override suspend fun image(file: LocalFile): OperationResult<ImageInfo> = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val check = { context.ensureActive() }
        try {
            check()
            if (!isNativeText(file.path) || !file.path.toPath().isAbsolute) fail(ProblemCode.INVALID_DATA)
            checkFile(file.path, check)
            val info = autoreleasepool { decode(file.path, check) }
            check()
            OperationResult.Completed(info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ImageFailure) {
            check()
            OperationResult.Failed(Problem(failure.code))
        } catch (_: Exception) {
            check()
            OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
        }
    }

    private fun checkFile(path: String, check: () -> Unit) = memScoped {
        val metadata = alloc<stat>()
        val initial = lstat(path, metadata.ptr)
        val initialError = errno
        check()
        if (initial != 0) fileError(initialError)
        if ((metadata.st_mode.toInt() and S_IFMT) != S_IFREG) fail(ProblemCode.INVALID_DATA)
        val descriptor = open(path, O_RDONLY or O_NOFOLLOW or O_NONBLOCK)
        if (descriptor < 0) {
            val error = errno
            check()
            fileError(error)
        }
        var closeFailed = false
        try {
            check()
            val inspected = fstat(descriptor, metadata.ptr)
            val error = errno
            check()
            if (inspected != 0) fileError(error)
            if ((metadata.st_mode.toInt() and S_IFMT) != S_IFREG || metadata.st_size <= 0) fail(ProblemCode.INVALID_DATA)
            if (metadata.st_size > 64L * 1024 * 1024) fail(ProblemCode.LIMIT_EXCEEDED)
            val envelope = validateImageEnvelope(metadata.st_size, { offset, buffer, count ->
                buffer.usePinned { pinned ->
                    var done = 0
                    while (done < count) {
                        check()
                        val read = pread(descriptor, pinned.addressOf(done), (count - done).toULong(), offset + done)
                        if (read < 0) {
                            if (errno == EINTR) continue
                            throw IOException("Image read failed")
                        }
                        if (read == 0L) throw IOException("Unexpected image EOF")
                        done += read.toInt()
                        check()
                    }
                }
            }, check)
            when (envelope) {
                is OperationResult.Completed -> Unit
                is OperationResult.Failed -> fail(envelope.problem.code)
                OperationResult.Cancelled -> throw CancellationException("Image validation cancelled")
                OperationResult.UnconfirmedCommit -> fail(ProblemCode.IO_FAILURE)
            }
        } finally { closeFailed = close(descriptor) != 0 }
        if (closeFailed) fail(ProblemCode.IO_FAILURE)
    }

    private fun decode(path: String, check: () -> Unit): ImageInfo {
        val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) ?: fail(ProblemCode.IO_FAILURE)
        try {
            val options = dictionary()
            try {
                CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanFalse)
                CFDictionarySetValue(options, kCGImageSourceShouldAllowFloat, kCFBooleanFalse)
                check()
                val source = CGImageSourceCreateWithURL(url.reinterpret(), options)
                if (source == null) { check(); fail(ProblemCode.UNSUPPORTED_MEDIA) }
                try {
                    check()
                    val type = CGImageSourceGetType(source) ?: fail(ProblemCode.UNSUPPORTED_MEDIA)
                    val mime = UTType.typeWithIdentifier(identifier(type))?.preferredMIMEType
                        ?.takeIf { it.isNotBlank() && isNativeText(it) } ?: fail(ProblemCode.UNSUPPORTED_MEDIA)
                    if (CGImageSourceGetCount(source) == 0uL) fail(ProblemCode.INVALID_DATA)
                    check()
                    val properties = CGImageSourceCopyPropertiesAtIndex(source, 0u, options)
                    if (properties == null) { check(); fail(ProblemCode.INVALID_DATA) }
                    val info = try {
                        check()
                        val width = dimension(properties, kCGImagePropertyPixelWidth)
                        val height = dimension(properties, kCGImagePropertyPixelHeight)
                        if (width.toLong() * height > 16_000_000L) fail(ProblemCode.LIMIT_EXCEEDED)
                        ImageInfo(width, height, mime)
                    } finally { CFRelease(properties) }
                    status(source)
                    thumbnail(source, check)
                    status(source)
                    check()
                    return info
                } finally { CFRelease(source) }
            } finally { CFRelease(options) }
        } finally { CFRelease(url) }
    }

    private fun thumbnail(source: CGImageSourceRef, check: () -> Unit) {
        // A 1000px side also bounds a square to decimal 1MP, below the 1024px ceiling.
        val maximum = memScoped {
            val value = alloc<IntVar>(); value.value = 1000
            CFNumberCreate(null, kCFNumberIntType, value.ptr) ?: fail(ProblemCode.IO_FAILURE)
        }
        try {
            val options = dictionary()
            try {
                CFDictionarySetValue(options, kCGImageSourceThumbnailMaxPixelSize, maximum)
                CFDictionarySetValue(options, kCGImageSourceCreateThumbnailFromImageAlways, kCFBooleanTrue)
                CFDictionarySetValue(options, kCGImageSourceShouldCacheImmediately, kCFBooleanTrue)
                CFDictionarySetValue(options, kCGImageSourceShouldCache, kCFBooleanTrue)
                CFDictionarySetValue(options, kCGImageSourceShouldAllowFloat, kCFBooleanFalse)
                check()
                val image = CGImageSourceCreateThumbnailAtIndex(source, 0u, options)
                if (image == null) { check(); fail(ProblemCode.INVALID_DATA) }
                try {
                    check()
                    val width = CGImageGetWidth(image)
                    val height = CGImageGetHeight(image)
                    if (width == 0uL || height == 0uL) fail(ProblemCode.INVALID_DATA)
                    if (width > 1024uL || height > 1024uL || width * height > 1_000_000uL) fail(ProblemCode.LIMIT_EXCEEDED)
                    status(source)
                    val colorSpace = CGColorSpaceCreateDeviceRGB() ?: fail(ProblemCode.IO_FAILURE)
                    try {
                        check()
                        val bitmap = CGBitmapContextCreate(null, width, height, 8u, width * 4u, colorSpace,
                            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value)
                        if (bitmap == null) { check(); fail(ProblemCode.IO_FAILURE) }
                        try {
                            check()
                            if (CGBitmapContextGetData(bitmap) == null) fail(ProblemCode.IO_FAILURE)
                            CGContextDrawImage(bitmap, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
                            check()
                            CGContextFlush(bitmap)
                            check()
                            status(source)
                        } finally { CGContextRelease(bitmap) }
                    } finally { CGColorSpaceRelease(colorSpace) }
                } finally { CGImageRelease(image) }
            } finally { CFRelease(options) }
        } finally { CFRelease(maximum) }
    }

    private fun status(source: CGImageSourceRef) {
        for (value in listOf(CGImageSourceGetStatus(source), CGImageSourceGetStatusAtIndex(source, 0u))) {
            when (value) {
                kCGImageStatusComplete -> Unit
                kCGImageStatusUnknownType -> fail(ProblemCode.UNSUPPORTED_MEDIA)
                else -> fail(ProblemCode.INVALID_DATA)
            }
        }
    }

    private fun dimension(properties: CFDictionaryRef, key: CFStringRef?): Int = memScoped {
        val raw = CFDictionaryGetValue(properties, key) ?: fail(ProblemCode.INVALID_DATA)
        if (CFGetTypeID(raw) != CFNumberGetTypeID()) fail(ProblemCode.INVALID_DATA)
        val number = alloc<DoubleVar>()
        if (!CFNumberGetValue(raw.reinterpret(), kCFNumberDoubleType, number.ptr)) fail(ProblemCode.INVALID_DATA)
        val value = number.value
        if (!value.isFinite() || value <= 0 || floor(value) != value) fail(ProblemCode.INVALID_DATA)
        if (value > 8192) fail(ProblemCode.LIMIT_EXCEEDED)
        value.toInt()
    }

    private fun identifier(type: CFStringRef): String = memScoped {
        val size = CFStringGetLength(type)
        if (size <= 0 || size > Int.MAX_VALUE) fail(ProblemCode.UNSUPPORTED_MEDIA)
        val chars = allocArray<UShortVar>(size)
        CFStringGetCharacters(type, CFRangeMake(0, size), chars)
        CharArray(size.toInt()) { chars[it].toInt().toChar() }.concatToString()
            .takeIf(::isNativeText) ?: fail(ProblemCode.UNSUPPORTED_MEDIA)
    }

    // Keys/booleans are static; the thumbnail's CFNumber stays alive through image release.
    private fun dictionary() = CFDictionaryCreateMutable(null, 0, null, null) ?: fail(ProblemCode.IO_FAILURE)

    private fun fileError(error: Int): Nothing = fail(when (error) {
        ENOENT, ENOTDIR -> ProblemCode.NOT_FOUND
        ELOOP -> ProblemCode.INVALID_DATA
        else -> ProblemCode.IO_FAILURE
    })
    private fun fail(code: ProblemCode): Nothing = throw ImageFailure(code)
    private class ImageFailure(val code: ProblemCode) : Exception()
}
