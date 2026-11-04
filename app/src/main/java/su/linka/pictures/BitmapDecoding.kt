package su.linka.pictures

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.WorkerThread
import java.io.File
import java.io.IOException

/** Synchronous IO: call off the main thread; replace the caller's bitmap only on success. */
object BitmapDecoding {
    private val maxBytes = ArchiveLimits().maxMediaBytes

    @WorkerThread
    @Throws(IOException::class)
    fun decode(file: File, maxDimension: Int): Bitmap {
        require(maxDimension > 0)
        if (file.length() > maxBytes) throw IOException("Image exceeds $maxBytes bytes")
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Invalid image")
        val longest = maxOf(options.outWidth, options.outHeight).toLong()
        var sample = 1L
        while ((longest + sample - 1) / sample > maxDimension) sample *= 2
        if (sample > Int.MAX_VALUE) throw IOException("Unsupported image dimensions")
        options.inSampleSize = sample.toInt()
        options.inJustDecodeBounds = false
        return file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("Unable to decode image")
    }

    @WorkerThread
    @Throws(IOException::class)
    fun decode(context: Context, uri: Uri, maxDimension: Int): Bitmap {
        require(maxDimension > 0)
        // Providers may be non-seekable and omit size: stage a bounded snapshot, not a byte array.
        val snapshot = File.createTempFile("image-decode-", ".tmp", context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open image")
            input.use { source ->
                snapshot.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IOException("Image exceeds $maxBytes bytes")
                        output.write(buffer, 0, count)
                    }
                }
            }
            return decode(snapshot, maxDimension)
        } finally {
            snapshot.delete()
        }
    }
}
