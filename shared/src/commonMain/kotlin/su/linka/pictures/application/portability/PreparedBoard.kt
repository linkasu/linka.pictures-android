package su.linka.pictures.application.portability

import su.linka.pictures.application.ports.MediaLease
import su.linka.pictures.domain.BoardDocument

data class BoardTransferReport(
    val pages: Int,
    val cards: Int,
    val images: Int,
    val recordings: Int,
    val mediaBytes: Long,
    val layoutNormalized: Boolean,
    val ignoredInactiveMediaReferences: Int = 0,
) {
    init { require(pages > 0 && cards >= 0 && images >= 0 && recordings >= 0 && mediaBytes >= 0 && ignoredInactiveMediaReferences >= 0) }
}

class PreparedBoard(
    val document: BoardDocument,
    val report: BoardTransferReport,
    val lease: MediaLease,
) : AutoCloseable {
    override fun close() = lease.close()
}
