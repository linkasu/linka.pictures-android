@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.audio

import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.AudioToolbox.*
import platform.CoreAudioTypes.*
import platform.CoreFoundation.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*

internal class IosSynthWriterFailure(val operation: String, val status: Int) : Exception("$operation: OSStatus=$status")

// Worker-only C lifetime; disposal finalizes the WAV before inspection or ownership transfer.
internal class IosSynthWriter(files: PlatformFilesPort, file: TemporaryFile, private val format: AVAudioFormat) {
    private var handle: ExtAudioFileRef? = null
    private var frames = 0L

    init {
        validateAudioReservation(files, file)
        if (!format.sampleRate.isFinite() || format.sampleRate !in 8000.0..192000.0 || format.channelCount !in 1u..8u)
            throw IosAudioFailure(ProblemCode.UNSUPPORTED_MEDIA)
        try {
            memScoped {
                val output = alloc<AudioStreamBasicDescription>()
                output.mSampleRate = format.sampleRate
                output.mFormatID = kAudioFormatLinearPCM
                output.mFormatFlags = kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
                output.mBytesPerPacket = format.channelCount * 2u
                output.mFramesPerPacket = 1u
                output.mBytesPerFrame = format.channelCount * 2u
                output.mChannelsPerFrame = format.channelCount
                output.mBitsPerChannel = 16u
                output.mReserved = 0u
                val reference = alloc<ExtAudioFileRefVar>()
                reference.value = null
                val path = file.path.encodeToByteArray()
                val url = path.usePinned {
                    CFURLCreateFromFileSystemRepresentation(null, it.addressOf(0).reinterpret(), path.size.toLong(), false)
                } ?: throw IosAudioFailure(ProblemCode.IO_FAILURE)
                // This is a CFCreate-owned C object, never a bridged/released Foundation object.
                try {
                    val status = ExtAudioFileCreateWithURL(url, kAudioFileWAVEType, output.ptr, null, kAudioFileFlags_EraseFile, reference.ptr)
                    handle = reference.value
                    checkStatus("ExtAudioFileCreateWithURL", status)
                } finally { CFRelease(url) }
                val target = handle ?: throw IosAudioFailure(ProblemCode.IO_FAILURE)
                checkStatus("ExtAudioFileSetProperty(ClientDataFormat)", ExtAudioFileSetProperty(target,
                    kExtAudioFileProperty_ClientDataFormat, sizeOf<AudioStreamBasicDescription>().toUInt(), format.streamDescription))
            }
        } catch (failure: Throwable) {
            try { close() } catch (_: Exception) { /* Keep the original create/format error. */ }
            throw failure
        }
    }

    fun write(buffer: AVAudioPCMBuffer) {
        val target = handle ?: throw IosAudioFailure(ProblemCode.IO_FAILURE)
        if (!format.isEqual(buffer.format)) throw IosAudioFailure(ProblemCode.INVALID_DATA)
        if (buffer.frameLength == 0u) throw IosAudioFailure(ProblemCode.INVALID_DATA)
        val nextFrames = frames + buffer.frameLength.toLong()
        if (nextFrames * format.channelCount.toLong() * 2 + 4096 > AUDIO_BYTE_LIMIT || nextFrames / format.sampleRate > AUDIO_SECONDS_LIMIT)
            throw IosAudioFailure(ProblemCode.LIMIT_EXCEEDED)
        checkStatus("ExtAudioFileWrite", ExtAudioFileWrite(target, buffer.frameLength, buffer.audioBufferList))
        frames = nextFrames
    }

    fun close() {
        val target = handle ?: return
        handle = null // Dispose is attempted once, including when the native close reports failure.
        checkStatus("ExtAudioFileDispose", ExtAudioFileDispose(target))
    }

    private fun checkStatus(operation: String, status: Int) {
        if (status != 0) throw IosSynthWriterFailure(operation, status)
    }

}
