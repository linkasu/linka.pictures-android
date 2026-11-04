package su.linka.pictures.portability.backup

import kotlinx.serialization.Serializable
import su.linka.pictures.application.ports.SnapshotCounts
import su.linka.pictures.domain.PortableSettings

@Serializable
data class BackupManifest(
    val revision: Long,
    val settings: PortableSettings,
    val counts: SnapshotCounts,
    val format: String = "su.linka.pictures.backup",
    val version: String = "1.0",
) {
    init {
        require(revision >= 0)
        require(format == "su.linka.pictures.backup" && version == "1.0")
    }
}
