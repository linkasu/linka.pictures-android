package su.linka.pictures.application.ports

import su.linka.pictures.application.OperationResult

// Inspect actual bytes through bounded native decoders, not extensions or provider MIME hints.
interface MediaInspectionPort {
    suspend fun image(file: LocalFile): OperationResult<ImageInfo>
    suspend fun audio(file: LocalFile): OperationResult<AudioInfo>
}

data class ImageInfo(val width: Int, val height: Int, val mediaType: String) {
    init { require(width > 0 && height > 0 && mediaType.isNotBlank()) }
}

data class AudioInfo(val durationMillis: Long, val mediaType: String) {
    init { require(durationMillis > 0 && mediaType.isNotBlank()) }
}
