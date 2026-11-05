package su.linka.pictures.bootstrap

import android.graphics.*
import android.media.ExifInterface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import java.io.File

internal class AndroidThumbnailDecoder(private val inspection: MediaInspectionPort) {
    suspend fun decode(file: LocalFile, side: Int): OperationResult<ImageBitmap> = withContext(Dispatchers.IO) {
        if (side !in 1..256) return@withContext failed(ProblemCode.LIMIT_EXCEEDED)
        val info = when (val checked = inspection.image(file)) {
            is OperationResult.Completed -> checked.value
            is OperationResult.Failed -> return@withContext checked
            else -> return@withContext OperationResult.Cancelled
        }
        var owned: Bitmap? = null
        try {
            currentCoroutineContext().ensureActive()
            owned = if (Build.VERSION.SDK_INT >= 28) modern(file, side) else {
                val options = BitmapFactory.Options().also {
                    it.inPreferredConfig = Bitmap.Config.ARGB_8888
                    it.inScaled = false
                    it.inSampleSize = 1
                    while ((maxOf(info.width, info.height).toLong() + it.inSampleSize - 1) / it.inSampleSize > side) it.inSampleSize *= 2
                }
                val decoded = BitmapFactory.decodeFile(file.path, options) ?: return@withContext failed(ProblemCode.INVALID_DATA)
                owned = decoded
                val matrix = if (info.mediaType == "image/jpeg") orientation(file.path) else Matrix()
                if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { if (it !== decoded) decoded.recycle() }
            }
            val bitmap = owned ?: return@withContext failed(ProblemCode.INVALID_DATA)
            if (bitmap.width !in 1..side || bitmap.height !in 1..side) return@withContext failed(ProblemCode.LIMIT_EXCEEDED)
            if (bitmap.config != Bitmap.Config.ARGB_8888) {
                owned = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                bitmap.recycle()
            }
            currentCoroutineContext().ensureActive()
            val result = checkNotNull(owned).asImageBitmap()
            owned = null
            OperationResult.Completed(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed(ProblemCode.IO_FAILURE) }
        finally { owned?.recycle() }
    }

    @RequiresApi(28)
    private fun modern(file: LocalFile, side: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(file.path))) { decoder, info, _ ->
        val factor = minOf(1.0, side.toDouble() / maxOf(info.size.width, info.size.height))
        decoder.setTargetSize(maxOf(1, (info.size.width * factor).toInt()), maxOf(1, (info.size.height * factor).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
    }

    private fun orientation(path: String): Matrix = Matrix().apply {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
        }
    }

    private fun failed(code: ProblemCode) = OperationResult.Failed(Problem(code))
}
