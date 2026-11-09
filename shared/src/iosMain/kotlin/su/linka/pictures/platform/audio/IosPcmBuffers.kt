@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.platform.audio

import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.posix.memcpy
import su.linka.pictures.application.ProblemCode

internal const val AUDIO_BYTE_LIMIT = 64L * 1024 * 1024
internal const val AUDIO_SECONDS_LIMIT = 600.0

internal fun waveSettings(rate: Double, channels: UInt): Map<Any?, Any> = mapOf(
    AVFormatIDKey to kAudioFormatLinearPCM,
    AVSampleRateKey to rate,
    AVNumberOfChannelsKey to channels.toInt(),
    AVLinearPCMBitDepthKey to 16,
    AVLinearPCMIsFloatKey to false,
    AVLinearPCMIsBigEndianKey to false,
    AVLinearPCMIsNonInterleaved to false,
)

// AVSpeech owns its callback buffer; retain an independent, strictly bounded copy.
internal fun ownedPcm(source: AVAudioPCMBuffer): AVAudioPCMBuffer {
    val format = source.format
    val frames = source.frameLength
    val channels = format.channelCount
    if (!format.sampleRate.isFinite() || format.sampleRate !in 8000.0..192000.0 || channels !in 1u..8u || frames !in 1u..16384u)
        throw IosAudioFailure(ProblemCode.UNSUPPORTED_MEDIA)
    val bytes = when (format.commonFormat) {
        AVAudioPCMFormatFloat32, AVAudioPCMFormatInt32 -> 4
        AVAudioPCMFormatInt16 -> 2
        else -> throw IosAudioFailure(ProblemCode.UNSUPPORTED_MEDIA)
    }
    val count = if (format.interleaved) 1 else channels.toInt()
    val size = frames.toLong() * bytes * if (format.interleaved) channels.toLong() else 1
    if (size * count > 1024 * 1024) throw IosAudioFailure(ProblemCode.LIMIT_EXCEEDED)
    val copy = AVAudioPCMBuffer(format, frames) ?: throw IosAudioFailure(ProblemCode.IO_FAILURE)
    copy.frameLength = frames
    for (index in 0 until count) {
        val input: COpaquePointer? = when (format.commonFormat) {
            AVAudioPCMFormatFloat32 -> source.floatChannelData?.get(index)
            AVAudioPCMFormatInt32 -> source.int32ChannelData?.get(index)
            else -> source.int16ChannelData?.get(index)
        }
        val output: COpaquePointer? = when (format.commonFormat) {
            AVAudioPCMFormatFloat32 -> copy.floatChannelData?.get(index)
            AVAudioPCMFormatInt32 -> copy.int32ChannelData?.get(index)
            else -> copy.int16ChannelData?.get(index)
        }
        if (input == null || output == null) throw IosAudioFailure(ProblemCode.INVALID_DATA)
        if (format.commonFormat == AVAudioPCMFormatFloat32) {
            val samples = input.reinterpret<FloatVar>()
            for (sample in 0 until (size / 4).toInt()) {
                if (!samples[sample].isFinite()) throw IosAudioFailure(ProblemCode.INVALID_DATA)
            }
        }
        memcpy(output, input, size.toULong())
    }
    return copy
}
