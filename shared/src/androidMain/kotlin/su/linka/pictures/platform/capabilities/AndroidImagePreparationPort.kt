package su.linka.pictures.platform.capabilities

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import okio.Buffer
import okio.buffer
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.text.isNativeText

private class ImagePreparationFailure(val result: OperationResult<Nothing>) : Exception() {
    constructor(code: ProblemCode) : this(OperationResult.Failed(Problem(code)))
}

internal class AndroidImagePreparationPort(
    private val files: PlatformFilesPort,
    private val inspection: MediaInspectionPort,
) : ImagePreparationPort {
    override suspend fun prepareImage(file: LocalFile): OperationResult<TemporaryFile> = produce { owned ->
        val info = completed(inspection.image(file))
        currentCoroutineContext().ensureActive()
        val target = temporary(owned, "image", info.mediaType)
        completed(files.source(file)).use { input ->
            completed(files.sink(target)).use { output ->
                val buffer = Buffer()
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 8192)
                    if (count < 0) break
                    if (count == 0L) throw ImagePreparationFailure(ProblemCode.IO_FAILURE)
                    if (count > MediaContainer.MAX_BYTES - total) throw ImagePreparationFailure(ProblemCode.LIMIT_EXCEEDED)
                    output.write(buffer, count)
                    total += count
                }
                output.flush()
            }
        }
        currentCoroutineContext().ensureActive()
        completed(inspection.image(target))
        currentCoroutineContext().ensureActive()
        target
    }

    override suspend fun imageFromText(request: TextImageRequest): OperationResult<TemporaryFile> = produce { owned ->
        currentCoroutineContext().ensureActive()
        val layout = textLayout(request)
        val height = layout.height.toLong() + 64
        if (height > 4096 || height <= 0 || 1024L * height > 16_000_000) throw ImagePreparationFailure(ProblemCode.LIMIT_EXCEEDED)
        currentCoroutineContext().ensureActive()
        val bitmap = Bitmap.createBitmap(1024, height.toInt(), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(request.backgroundArgb.toInt())
            canvas.translate(32f, 32f)
            layout.draw(canvas)
            currentCoroutineContext().ensureActive()
            val target = temporary(owned, "text", "image/png", ".png")
            encode(bitmap, target)
            currentCoroutineContext().ensureActive()
            val info = completed(inspection.image(target))
            if (info.mediaType != "image/png" || info.width != 1024 || info.height != height.toInt()) throw ImagePreparationFailure(ProblemCode.INVALID_DATA)
            currentCoroutineContext().ensureActive()
            target
        } finally {
            try { bitmap.recycle() } catch (_: Exception) { /* Best-effort native cleanup must preserve the primary outcome. */ }
        }
    }

    @Suppress("DEPRECATION")
    internal fun textLayout(request: TextImageRequest): StaticLayout {
        if (request.text.length > 4096) throw ImagePreparationFailure(ProblemCode.LIMIT_EXCEEDED)
        if (request.text.isEmpty() || !isNativeText(request.text) || request.foregroundArgb !in 0L..0xffffffffL || request.backgroundArgb !in 0L..0xffffffffL) {
            throw ImagePreparationFailure(ProblemCode.INVALID_DATA)
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
            it.textSize = 64f
            it.typeface = Typeface.DEFAULT
            it.color = request.foregroundArgb.toInt()
        }
        return StaticLayout(request.text, paint, 960, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, true)
    }

    private suspend fun encode(bitmap: Bitmap, file: TemporaryFile) {
        val context = currentCoroutineContext()
        completed(files.sink(file)).buffer().use { output ->
            var total = 0L
            var writeFailure: Exception? = null
            val stream = object : OutputStream() {
                private fun writeChecked(count: Int, action: () -> Unit) {
                    writeFailure?.let { throw it }
                    try {
                        context.ensureActive()
                        if (count > MediaContainer.MAX_BYTES - total) throw ImagePreparationFailure(ProblemCode.LIMIT_EXCEEDED)
                        action()
                        total += count
                    } catch (failure: Exception) {
                        if (writeFailure == null) writeFailure = failure
                        throw failure
                    }
                }
                override fun write(value: Int) = writeChecked(1) { output.writeByte(value) }
                override fun write(bytes: ByteArray, offset: Int, count: Int) = writeChecked(count) { output.write(bytes, offset, count) }
            }
            // Some native PNG encoders return false instead of propagating OutputStream exceptions.
            val success = try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                catch (failure: Exception) { throw writeFailure ?: failure }
            writeFailure?.let { throw it }
            context.ensureActive()
            if (!success) throw ImagePreparationFailure(ProblemCode.IO_FAILURE)
            output.flush()
        }
    }

    private fun temporary(owned: AtomicReference<TemporaryFile?>, prefix: String, type: String, suffix: String = ""): TemporaryFile {
        val target = completed(files.createTemporaryFile("$prefix-${UUID.randomUUID()}$suffix", type))
        val file = File(target.path)
        if (!file.isFile || file.length() != 0L) throw ImagePreparationFailure(ProblemCode.CONFLICT)
        owned.set(target)
        return target
    }

    private suspend fun produce(operation: suspend (AtomicReference<TemporaryFile?>) -> TemporaryFile): OperationResult<TemporaryFile> {
        val owned = AtomicReference<TemporaryFile?>(null)
        try {
            val result = withContext(Dispatchers.IO) { currentCoroutineContext().ensureActive(); operation(owned) }
            currentCoroutineContext().ensureActive()
            // No suspension after transfer; cancellation at the IO->caller dispatch boundary still leaves ownership here.
            owned.set(null)
            return OperationResult.Completed(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ImagePreparationFailure) { return failure.result }
        catch (_: OutOfMemoryError) { return OperationResult.Failed(Problem(ProblemCode.LIMIT_EXCEEDED)) }
        catch (_: Exception) { return OperationResult.Failed(Problem(ProblemCode.IO_FAILURE)) }
        finally {
            owned.getAndSet(null)?.let { file -> withContext(NonCancellable + Dispatchers.IO) {
                try { files.discard(file) } catch (_: Exception) { /* Physical deletion is best-effort, never a replacement outcome. */ }
            } }
        }
    }

    private fun <T> completed(result: OperationResult<T>): T = when (result) {
        is OperationResult.Completed -> result.value
        is OperationResult.Failed -> throw ImagePreparationFailure(result)
        OperationResult.Cancelled -> throw ImagePreparationFailure(OperationResult.Cancelled)
        OperationResult.UnconfirmedCommit -> throw ImagePreparationFailure(OperationResult.UnconfirmedCommit)
    }
}
