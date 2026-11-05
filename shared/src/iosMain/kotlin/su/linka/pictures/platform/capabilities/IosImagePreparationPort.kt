@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package su.linka.pictures.platform.capabilities

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import okio.Buffer
import okio.use
import platform.CoreFoundation.*
import platform.CoreGraphics.*
import platform.CoreText.*
import platform.Foundation.*
import platform.ImageIO.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import kotlin.math.ceil

class IosImagePreparationPort internal constructor(
    private val files: PlatformFilesPort,
    private val inspection: MediaInspectionPort,
) : ImagePreparationPort {
    override suspend fun prepareImage(file: LocalFile): OperationResult<TemporaryFile> = produce(file.path) {
        when (val inspected = inspection.image(file)) {
            is OperationResult.Completed -> Unit
            else -> return@produce inspected.failure()
        }
        copy(file, this)
    }

    override suspend fun imageFromText(request: TextImageRequest): OperationResult<TemporaryFile> {
        currentCoroutineContext().ensureActive()
        if (!validText(request.text) || request.foregroundArgb !in 0..0xffffffffL ||
            request.backgroundArgb !in 0..0xffffffffL
        ) return failed(ProblemCode.INVALID_DATA)
        if (request.text.length > 4096) return failed(ProblemCode.LIMIT_EXCEEDED)
        return produce(null) {
            val png = render(request) ?: return@produce failed(ProblemCode.LIMIT_EXCEEDED)
            currentCoroutineContext().ensureActive()
            val target = allocate("text.png", "image/png") ?: return@produce allocationFailure!!
            files.sink(target).let { result ->
                when (result) {
                    is OperationResult.Completed -> result.value.use { sink ->
                        sink.write(Buffer().write(png), png.size.toLong())
                    }
                    else -> return@produce result.failure()
                }
            }
            when (val actual = inspection.image(target)) {
                is OperationResult.Completed -> if (actual.value.mediaType != "image/png" ||
                    actual.value.width != 1024 || actual.value.height > 4096
                ) failed(ProblemCode.INVALID_DATA) else OperationResult.Completed(target)
                else -> actual.failure()
            }
        }
    }

    private inner class Output(val sourcePath: String?) {
        var owned: TemporaryFile? = null
        var allocationFailure: OperationResult<Nothing>? = null

        fun allocate(name: String, type: String): TemporaryFile? {
            val target = when (val result = files.createTemporaryFile(name, type)) {
                is OperationResult.Completed -> result.value
                else -> { allocationFailure = result.failure(); return null }
            }
            // Never open a sink or discard an aliased/nonempty/unreadable allocation.
            if (target.path == sourcePath) {
                allocationFailure = failed(ProblemCode.CONFLICT)
                return null
            }
            when (val result = files.source(target)) {
                is OperationResult.Completed -> result.value.use {
                    if (it.read(Buffer(), 1) != -1L) {
                        allocationFailure = failed(ProblemCode.CONFLICT)
                        return null
                    }
                    owned = target
                }
                else -> { allocationFailure = result.failure(); return null }
            }
            return target
        }
    }

    private suspend fun produce(
        sourcePath: String?,
        block: suspend Output.() -> OperationResult<TemporaryFile>,
    ): OperationResult<TemporaryFile> {
        val output = Output(sourcePath)
        var published = false
        try {
            val result = withContext(Dispatchers.Default) { output.block() }
            currentCoroutineContext().ensureActive()
            published = result is OperationResult.Completed
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return failed(ProblemCode.IO_FAILURE)
        } finally {
            if (!published) output.owned?.let(files::discard)
        }
    }

    private suspend fun copy(file: LocalFile, output: Output): OperationResult<TemporaryFile> {
        val source = when (val result = files.source(file)) {
            is OperationResult.Completed -> result.value
            else -> return result.failure()
        }
        source.use { input ->
            val target = output.allocate(file.displayName ?: "image", file.mediaTypeHint ?: "application/octet-stream")
                ?: return output.allocationFailure!!
            val sink = when (val result = files.sink(target)) {
                is OperationResult.Completed -> result.value
                else -> return result.failure()
            }
            sink.use {
                val buffer = Buffer()
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 8192)
                    if (count == -1L) break
                    total += count
                    if (total > 64L * 1024 * 1024) return failed(ProblemCode.LIMIT_EXCEEDED)
                    it.write(buffer, count)
                }
                if (total == 0L) return failed(ProblemCode.INVALID_DATA)
            }
            return OperationResult.Completed(target)
        }
    }

    private fun validText(text: String): Boolean {
        if (text.isEmpty()) return false
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c == '\u0000' || c.isLowSurrogate()) return false
            if (c.isHighSurrogate() && (i == text.length || !text[i++].isLowSurrogate())) return false
        }
        return true
    }

    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))

    private fun OperationResult<*>.failure(): OperationResult<Nothing> = when (this) {
        is OperationResult.Failed -> this
        OperationResult.Cancelled -> OperationResult.Cancelled
        OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
        is OperationResult.Completed -> error("Expected failure")
    }

    private fun render(request: TextImageRequest): ByteArray? = autoreleasepool {
        // CoreText's system-font API is thread-safe; private UIFont names are not portable font names.
        val font = CTFontCreateUIFontForLanguage(kCTFontUIFontSystem, 64.0, null)!!
        val colorSpace = CGColorSpaceCreateDeviceRGB()!!
        fun color(argb: Long): CGColorRef = memScoped {
            val values = allocArray<CGFloatVar>(4)
            values[0] = ((argb shr 16) and 255) / 255.0
            values[1] = ((argb shr 8) and 255) / 255.0
            values[2] = (argb and 255) / 255.0
            values[3] = ((argb shr 24) and 255) / 255.0
            CGColorCreate(colorSpace, values)!!
        }
        val foreground = color(request.foregroundArgb)
        val background = color(request.backgroundArgb)
        try {
            val utf8 = request.text.encodeToByteArray()
            val text = utf8.usePinned {
                CFStringCreateWithBytes(null, it.addressOf(0).reinterpret(), utf8.size.toLong(), kCFStringEncodingUTF8, false)!!
            }
            val attributes = CFDictionaryCreateMutable(null, 0, null, null)!!
            CFDictionarySetValue(attributes, kCTFontAttributeName, font)
            CFDictionarySetValue(attributes, kCTForegroundColorAttributeName, foreground)
            val attributed = try { CFAttributedStringCreate(null, text, attributes)!! } finally {
                CFRelease(text)
                CFRelease(attributes)
            }
            val setter = try { CTFramesetterCreateWithAttributedString(attributed)!! } finally { CFRelease(attributed) }
            try {
                val size = CTFramesetterSuggestFrameSizeWithConstraints(
                    setter, CFRangeMake(0, 0), null, CGSizeMake(960.0, 1.0e9), null,
                )
                val height = ceil(size.useContents { this.height } + 64.0).toInt()
                if (height !in 65..4096) return@autoreleasepool null
                val context = CGBitmapContextCreate(null, 1024u, height.toULong(), 8u, 0u,
                    colorSpace, CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value) ?: error("Bitmap allocation failed")
                try {
                    CGContextSetFillColorWithColor(context, background)
                    CGContextFillRect(context, CGRectMake(0.0, 0.0, 1024.0, height.toDouble()))
                    val path = CGPathCreateWithRect(CGRectMake(32.0, 32.0, 960.0, height - 64.0), null)!!
                    try {
                        val frame = CTFramesetterCreateFrame(setter, CFRangeMake(0, 0), path, null)!!
                        try {
                            val visible = CTFrameGetVisibleStringRange(frame)
                            if (visible.useContents { location != 0L || length != request.text.length.toLong() }) {
                                return@autoreleasepool null
                            }
                            CTFrameDraw(frame, context)
                        } finally { CFRelease(frame) }
                    } finally { CGPathRelease(path) }
                    val image = CGBitmapContextCreateImage(context)!!
                    try {
                        val data = NSMutableData()
                        val nativeData = CFBridgingRetain(data)!!
                        try {
                            val type = CFStringCreateWithCString(null, "public.png", kCFStringEncodingUTF8)!!
                            val destination = try { CGImageDestinationCreateWithData(nativeData.reinterpret(), type, 1u, null)!! }
                                finally { CFRelease(type) }
                            try {
                                CGImageDestinationAddImage(destination, image, null)
                                check(CGImageDestinationFinalize(destination))
                            } finally { CFRelease(destination) }
                            data.bytes!!.reinterpret<ByteVar>().readBytes(data.length.toInt())
                        } finally { CFRelease(nativeData) }
                    } finally { CGImageRelease(image) }
                } finally { CGContextRelease(context) }
            } finally { CFRelease(setter) }
        } finally {
            CGColorRelease(foreground)
            CGColorRelease(background)
            CGColorSpaceRelease(colorSpace)
            CFRelease(font)
        }
    }
}
