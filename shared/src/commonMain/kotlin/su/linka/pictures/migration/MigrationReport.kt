package su.linka.pictures.migration

import kotlinx.serialization.Serializable
import su.linka.pictures.application.Problem
import su.linka.pictures.application.ports.PlatformFilesPort
import su.linka.pictures.domain.BoardId
import su.linka.pictures.storage.StorageRuntime

@Serializable
internal enum class MigrationPhase {
    INVENTORY, COPYING_VALIDATING, ACCESS_READY, READY_TO_PUBLISH, COMMITTED,
    BLOCKED, FAILED, CANCELLED, UNCONFIRMED,
}

internal data class MigrationReport(
    val phase: MigrationPhase = MigrationPhase.INVENTORY,
    val total: Int = 0,
    val imported: Int = 0,
    val retained: Int = 0,
    val detailProblems: Map<BoardId, Problem> = emptyMap(),
    val error: Problem? = null,
    val requiresRestart: Boolean = false,
)

internal data class LegacyUpgradeSettings(val boardGrid: Map<String, Int>, val parentEnabled: Boolean)

internal class LegacyOpenedLibrary(
    val runtime: StorageRuntime,
    val files: PlatformFilesPort,
    val report: MigrationReport,
)

@Serializable
internal enum class MigrationSourceKind { LEGACY_FILES, BUNDLED }

@Serializable
internal data class AdmissionRecord(
    val schema: Int = 2,
    val migrationId: String,
    val sourceDigest: String,
    val phase: MigrationPhase,
    val total: Int,
    val imported: Int = 0,
    val retained: Int = 0,
    val credentialsInitialized: Boolean = false,
    val sourceKind: MigrationSourceKind = MigrationSourceKind.LEGACY_FILES,
) {
    init {
        require(schema in 1..2 && safeMigrationId(migrationId))
        require(schema != 1 || sourceKind == MigrationSourceKind.LEGACY_FILES)
        require(sourceDigest.length == 64 && sourceDigest.all { it in '0'..'9' || it in 'a'..'f' })
        require(total in 0..100000 && imported >= 0 && retained >= 0 && imported.toLong() + retained <= total)
    }
}

internal fun safeMigrationId(value: String): Boolean = value.length in 1..128 &&
    value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
