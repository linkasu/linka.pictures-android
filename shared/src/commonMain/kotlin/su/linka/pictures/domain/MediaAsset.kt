package su.linka.pictures.domain

import kotlinx.serialization.Serializable

@Serializable enum class MediaKind { IMAGE, AUDIO, ARCHIVE }

@Serializable
data class MediaAsset(val id: AssetId, val kind: MediaKind, val mediaType: String, val byteCount: Long) {
    init {
        require(mediaType.isNotBlank() && mediaType.length <= 256)
        require(byteCount >= 0)
    }
}
