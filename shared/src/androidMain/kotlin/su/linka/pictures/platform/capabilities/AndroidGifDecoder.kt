package su.linka.pictures.platform.capabilities

import android.graphics.Bitmap
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.media.validateGifThumbnail

/** The already bundled Glide decoder preserves alpha without expanding every animation frame. */
internal object AndroidGifDecoder {
    private val decoding = Mutex()
    private val provider = object : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap = Bitmap.createBitmap(width, height, config)
        override fun release(bitmap: Bitmap) = bitmap.recycle()
        override fun obtainByteArray(size: Int) = ByteArray(size)
        override fun release(bytes: ByteArray) = Unit
        override fun obtainIntArray(size: Int) = IntArray(size)
        override fun release(array: IntArray) = Unit
    }

    suspend fun decode(file: LocalFile, info: ImageInfo, side: Int): Bitmap = decoding.withLock {
        val context = currentCoroutineContext()
        if (side !in 1..1024) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
        val pixels = info.width.toLong() * info.height
        if (info.width !in 1..8192 || info.height !in 1..8192 || pixels > 16_000_000) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
        var sample = 1
        while ((maxOf(info.width, info.height).toLong() + sample - 1) / sample > side) sample *= 2
        sample = minOf(sample, Integer.highestOneBit(minOf(info.width, info.height)))
        val sampledPixels = (info.width / sample).toLong() * (info.height / sample)
        val bytes = FileInputStream(file.path).use { input ->
            val length = input.channel.size()
            if (length < 14) throw InvalidMedia(ProblemCode.INVALID_DATA)
            if (length > 64L * 1024 * 1024) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
            val needed = length + pixels + 12 * sampledPixels + 4L * side * side + 1_048_576
            val runtime = Runtime.getRuntime()
            val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            // shortcut: legacy GIF needs a bounded source buffer; use streaming if valid large GIFs exceed this heap budget.
            if (needed > runtime.maxMemory() / 2 || needed > available) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
            ByteArray(length.toInt()).also { data ->
                var offset = 0
                while (offset < data.size) {
                    context.ensureActive()
                    val read = input.read(data, offset, minOf(8192, data.size - offset))
                    if (read <= 0) throw InvalidMedia(ProblemCode.INVALID_DATA)
                    offset += read
                }
                if (input.read() != -1) throw InvalidMedia(ProblemCode.INVALID_DATA)
            }
        }
        val validated = validateGifThumbnail(bytes, info.width, info.height) { context.ensureActive() }
        if (validated is OperationResult.Failed) throw InvalidMedia(validated.problem.code)
        val data = ByteBuffer.wrap(bytes).asReadOnlyBuffer()
        val parser = GifHeaderParser().setData(data)
        var decoder: StandardGifDecoder? = null
        var owned: Bitmap? = null
        try {
            val header = parser.parseHeader()
            if (header.status != GifDecoder.STATUS_OK || header.numFrames == 0) throw InvalidMedia(ProblemCode.INVALID_DATA)
            context.ensureActive()
            val gif = StandardGifDecoder(provider, header, data, sample).also { decoder = it }
            gif.setDefaultBitmapConfig(Bitmap.Config.ARGB_8888)
            gif.advance()
            val frame = gif.nextFrame ?: throw InvalidMedia(ProblemCode.INVALID_DATA)
            owned = frame
            if (gif.status != GifDecoder.STATUS_OK) throw InvalidMedia(ProblemCode.INVALID_DATA)
            if (frame.width > side || frame.height > side) {
                val factor = side.toDouble() / maxOf(frame.width, frame.height)
                val scaled = Bitmap.createScaledBitmap(frame, maxOf(1, (frame.width * factor).toInt()), maxOf(1, (frame.height * factor).toInt()), true)
                owned = scaled
                if (scaled !== frame) frame.recycle()
            }
            context.ensureActive()
            checkNotNull(owned).also { owned = null }
        } finally {
            decoder?.clear()
            parser.clear()
            owned?.recycle()
        }
    }
}
