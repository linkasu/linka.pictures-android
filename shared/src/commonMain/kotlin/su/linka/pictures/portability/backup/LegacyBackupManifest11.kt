package su.linka.pictures.portability.backup

import kotlinx.serialization.Serializable
import su.linka.pictures.application.ports.SnapshotCounts
import su.linka.pictures.domain.*

/** Frozen 1.1 wire model: new settings and board fields must not become valid here. */
@Serializable
internal data class LegacyBackupManifest11(
    val revision: Long,
    val settings: LegacyPortableSettings11,
    val counts: SnapshotCounts,
    val format: String = "su.linka.pictures.backup",
    val version: String = "1.1",
    val organization: LibraryOrganization = LibraryOrganization(),
) {
    init { require(revision >= 0 && format == "su.linka.pictures.backup" && version == "1.1") }
    fun current() = BackupManifest(revision, settings.current(), counts, organization = organization)
}

@Serializable
internal data class LegacyPortableSettings11(
    val textScale: Float = 1f,
    val highContrast: Boolean = false,
    val languageTag: String = "ru-RU",
    val boards: Map<BoardId, LegacyBoardPresentation11> = emptyMap(),
) {
    fun current() = PortableSettings(textScale, highContrast, languageTag,
        boards.mapValues { BoardPresentation(it.value.navigation, it.value.dragToCompose) })
}

@Serializable
internal data class LegacyBoardPresentation11(
    val navigation: PageNavigation = PageNavigation.BOTTOM,
    val dragToCompose: Boolean = false,
)
