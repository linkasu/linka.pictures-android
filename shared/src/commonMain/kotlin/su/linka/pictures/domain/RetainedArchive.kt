package su.linka.pictures.domain

import kotlinx.serialization.Serializable

@Serializable enum class RetainedReason { INVALID_ARCHIVE, UNSUPPORTED_VERSION, UNSUPPORTED_MEDIA, IO_FAILURE }

@Serializable
data class RetainedArchive(
    val id: BoardId,
    val name: String,
    val revision: Long,
    val archive: AssetId,
    val reason: RetainedReason,
) {
    init { require(revision > 0) }
}
