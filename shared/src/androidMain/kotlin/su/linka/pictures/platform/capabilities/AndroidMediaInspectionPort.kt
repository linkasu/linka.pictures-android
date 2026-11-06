package su.linka.pictures.platform.capabilities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import kotlinx.coroutines.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import java.io.File
import java.io.RandomAccessFile

internal class AndroidMediaInspectionPort : MediaInspectionPort {
    override suspend fun image(file: LocalFile): OperationResult<ImageInfo> = inspect(file) { source, check ->
        MediaContainer.image(source, check)
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        source.seek(0)
        if (source.readInt() == 0x47494638) {
            // GIF dimensions are fixed header fields; validate metadata before any native decoder.
            source.seek(6)
            bounds.outWidth = source.readUnsignedByte() or (source.readUnsignedByte() shl 8)
            bounds.outHeight = source.readUnsignedByte() or (source.readUnsignedByte() shl 8)
            bounds.outMimeType = "image/gif"
        } else BitmapFactory.decodeFile(file.path, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) MediaContainer.invalid()
        if (width > 8192 || height > 8192 || width.toLong() * height > 16_000_000) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
        val options = BitmapFactory.Options().also { it.inPreferredConfig = Bitmap.Config.RGB_565; it.inSampleSize = 1 }
        while (((width + options.inSampleSize - 1) / options.inSampleSize).toLong() * ((height + options.inSampleSize - 1) / options.inSampleSize) > 1_048_576) options.inSampleSize *= 2
        check()
        val bitmap = if (bounds.outMimeType == "image/gif") AndroidGifDecoder.decode(file, ImageInfo(width, height, "image/gif"), 1024)
            else BitmapFactory.decodeFile(file.path, options) ?: MediaContainer.invalid()
        try {
            if (bitmap.width <= 0 || bitmap.height <= 0) MediaContainer.invalid()
            check()
            ImageInfo(width, height, bounds.outMimeType ?: MediaContainer.unsupported())
        } finally { bitmap.recycle() }
    }

    override suspend fun audio(file: LocalFile): OperationResult<AudioInfo> = inspect(file) { source, check ->
        val mediaType = MediaContainer.audio(source, check)
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var cleanupFailed = false
        val result = try {
            val rawAmr = if (mediaType == "audio/amr") RawAmrPackets(source, check) else null
            var track = -1
            val format = if (rawAmr != null) {
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8000, 1)
            } else {
                val parsed = MediaExtractor().also { extractor = it }
                parsed.setDataSource(source.fd)
                val tracks = (0 until parsed.trackCount).filter { parsed.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                if (tracks.size != 1) MediaContainer.unsupported()
                track = tracks.single()
                parsed.getTrackFormat(track)
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: MediaContainer.invalid()
            val name = MediaCodecList(MediaCodecList.ALL_CODECS).findDecoderForFormat(format) ?: MediaContainer.unsupported()
            val decoder = MediaCodec.createByCodecName(name).also { codec = it }
            val capabilities = decoder.codecInfo.getCapabilitiesForType(mime).audioCapabilities ?: MediaContainer.unsupported()
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (channels <= 0 || rate <= 0) MediaContainer.invalid()
            if (channels > capabilities.maxInputChannelCount || !capabilities.isSampleRateSupported(rate)) MediaContainer.unsupported()
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) > 600_000_000L) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
            extractor?.selectTrack(track)
            decoder.configure(format, null, null, 0)
            decoder.start()
            var inputEnded = false
            var outputEnded = false
            var outputFormat: MediaFormat? = null
            var outputRate = 0
            var outputChannels = 0
            var frames = 0L
            var bytes = 0L
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                check()
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: MediaContainer.invalid()
                        buffer.clear()
                        val count = if (rawAmr != null) rawAmr.read(buffer) else extractor!!.readSampleData(buffer, 0)
                        if (count < 0) {
                            decoder.queueInputBuffer(index, 0, 0, rawAmr?.sampleTimeUs ?: 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            if (count > buffer.capacity()) MediaContainer.invalid()
                            bytes += count
                            if (bytes > MediaContainer.MAX_BYTES) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
                            decoder.queueInputBuffer(index, 0, count, rawAmr?.sampleTimeUs ?: maxOf(0, extractor!!.sampleTime), 0)
                            extractor?.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) outputFormat = decoder.outputFormat
                if (index >= 0) {
                    try {
                        if (info.offset < 0 || info.size < 0) MediaContainer.invalid()
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val pcm = outputFormat ?: decoder.outputFormat
                            if (pcm.getString(MediaFormat.KEY_MIME) != "audio/raw") MediaContainer.unsupported()
                            val currentRate = pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val currentChannels = pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (currentRate <= 0 || currentChannels <= 0) MediaContainer.invalid()
                            if (!capabilities.isSampleRateSupported(currentRate) || currentChannels > capabilities.maxInputChannelCount) MediaContainer.unsupported()
                            if (frames > 0 && (currentRate != outputRate || currentChannels != outputChannels)) MediaContainer.unsupported()
                            outputRate = currentRate; outputChannels = currentChannels
                            val encoding = if (pcm.containsKey("pcm-encoding")) pcm.getInteger("pcm-encoding") else AudioFormat.ENCODING_PCM_16BIT
                            val sampleBytes = when (encoding) {
                                AudioFormat.ENCODING_PCM_8BIT -> 1
                                AudioFormat.ENCODING_PCM_16BIT -> 2
                                AudioFormat.ENCODING_PCM_FLOAT -> 4
                                else -> MediaContainer.unsupported()
                            }
                            val frameBytes = currentChannels.toLong() * sampleBytes
                            val buffer = decoder.getOutputBuffer(index) ?: MediaContainer.invalid()
                            if (info.offset < 0 || info.size < 0 || info.offset.toLong() + info.size > buffer.capacity() || info.size % frameBytes != 0L) MediaContainer.invalid()
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) validateFloatPcm(buffer, info.offset, info.size, check)
                            // Count only validated PCM, then discard without allocating/playing a whole clip.
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            buffer.position(buffer.limit())
                            frames += info.size / frameBytes
                            if (frames > currentRate.toLong() * 600) throw InvalidMedia(ProblemCode.LIMIT_EXCEEDED)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
            }
            if (frames == 0L || outputRate == 0) MediaContainer.invalid()
            AudioInfo(maxOf(1, (frames * 1000 + outputRate - 1) / outputRate), mediaType)
        } finally {
            try { codec?.release() } catch (_: Exception) { cleanupFailed = true }
            try { extractor?.release() } catch (_: Exception) { cleanupFailed = true }
        }
        if (cleanupFailed) throw InvalidMedia(ProblemCode.IO_FAILURE)
        result
    }

    private suspend fun <T> inspect(file: LocalFile, operation: suspend (RandomAccessFile, () -> Unit) -> T): OperationResult<T> = withContext(Dispatchers.IO) {
        try {
            val source = File(file.path)
            if (!source.isFile || source.length() <= 0) return@withContext OperationResult.Failed(Problem(ProblemCode.INVALID_DATA))
            if (source.length() > MediaContainer.MAX_BYTES) return@withContext OperationResult.Failed(Problem(ProblemCode.LIMIT_EXCEEDED))
            val context = currentCoroutineContext()
            val deadline = SystemClock.elapsedRealtime() + 30_000
            val check = {
                context.ensureActive()
                if (SystemClock.elapsedRealtime() >= deadline) throw InvalidMedia(ProblemCode.IO_FAILURE)
            }
            RandomAccessFile(source, "r").use { OperationResult.Completed(operation(it, check)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: InvalidMedia) { OperationResult.Failed(Problem(invalid.code)) }
        catch (_: OutOfMemoryError) { OperationResult.Failed(Problem(ProblemCode.LIMIT_EXCEEDED)) }
        catch (_: Exception) { OperationResult.Failed(Problem(ProblemCode.INVALID_DATA)) }
    }
}
