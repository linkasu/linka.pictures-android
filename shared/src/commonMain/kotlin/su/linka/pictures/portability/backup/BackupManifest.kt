package su.linka.pictures.portability.backup

import kotlinx.serialization.Serializable
import su.linka.pictures.application.ports.SnapshotCounts
import su.linka.pictures.domain.PortableSettings
import su.linka.pictures.domain.LibraryOrganization

@Serializable
data class BackupManifest(
    val revision: Long,
    val settings: PortableSettings,
    val counts: SnapshotCounts,
    val format: String = "su.linka.pictures.backup",
    val version: String = "1.1",
    val organization: LibraryOrganization = LibraryOrganization(),
) {
    init {
        require(revision >= 0)
        require(format == "su.linka.pictures.backup" && version == "1.1")
    }
}


/** Frozen 1.0 header, parsed strictly before conversion to current settings. */
@Serializable
internal data class LegacyBackupManifest(
    val revision: Long,
    val settings: su.linka.pictures.domain.LegacyPortableSettings,
    val counts: SnapshotCounts,
    val format: String = "su.linka.pictures.backup",
    val version: String = "1.0",
) {
    init { require(revision >= 0 && format == "su.linka.pictures.backup" && version == "1.0") }
    fun current() = BackupManifest(revision, settings.current(), counts)
}
