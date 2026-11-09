@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package su.linka.pictures.bootstrap

import okio.FileSystem
import platform.Foundation.NSUUID
import su.linka.pictures.migration.*
import kotlin.test.*

/** Root selection only; not a UIKit or native-media acceptance test. */
class IosStarterAdmissionTest {
    private val fs = FileSystem.SYSTEM

    @Test fun freshRootIsDistinctAndSelectionDoesNotCreateALibrary() {
        val application = fs.canonicalize(FileSystem.SYSTEM_TEMPORARY_DIRECTORY) / "ios-starter-${NSUUID().UUIDString}"
        fs.createDirectory(application, mustCreate = true)
        try {
            assertEquals(application / "starter-v1", IosProductRoot.startupRoot(application, fs))
            assertTrue(fs.list(application).isEmpty())
        } finally { fs.deleteRecursively(application) }
    }

    @Test fun existingDirectLibraryStaysInPlaceAndIsNeverSelectedForSeeding() {
        val application = fs.canonicalize(FileSystem.SYSTEM_TEMPORARY_DIRECTORY) / "ios-starter-${NSUUID().UUIDString}"
        val direct = application / "library-v1"
        fs.createDirectories(direct)
        fs.write(direct / "user-data") { writeUtf8("unchanged") }
        fs.createDirectory(application / "starter-v1")
        try {
            assertEquals(direct, IosProductRoot.startupRoot(application, fs))
            assertEquals("unchanged", fs.read(direct / "user-data") { readUtf8() })
            assertTrue(fs.list(application / "starter-v1").isEmpty())
            fs.write(application / "starter-v1" / "journal") { writeUtf8("owned admission") }
            assertFails { IosProductRoot.startupRoot(application, fs) }
            assertEquals("unchanged", fs.read(direct / "user-data") { readUtf8() })
            assertEquals("owned admission", fs.read(application / "starter-v1" / "journal") { readUtf8() })
        } finally { fs.deleteRecursively(application) }
    }

    private fun journal(phase: MigrationPhase) = AdmissionRecord(migrationId = "interrupted-starter",
        sourceDigest = "a".repeat(64), phase = phase, total = 5, sourceKind = MigrationSourceKind.BUNDLED)

    @Test fun interruptedSetupRequiresExplicitResumeButFreshAndActiveDoNot() {
        assertFalse(IosProductRoot.startupNeedsResume(null, null))
        for (phase in listOf(MigrationPhase.INVENTORY, MigrationPhase.COPYING_VALIDATING,
            MigrationPhase.ACCESS_READY, MigrationPhase.READY_TO_PUBLISH)) {
            assertTrue(IosProductRoot.startupNeedsResume(null, journal(phase)))
        }
        val active = journal(MigrationPhase.COMMITTED).copy(imported = 5, credentialsInitialized = true)
        assertFalse(IosProductRoot.startupNeedsResume(active, journal(MigrationPhase.UNCONFIRMED)))
    }

    @Test fun unknownCredentialOutcomeAndForeignJournalNeverOfferResume() {
        assertFails { IosProductRoot.startupNeedsResume(null, journal(MigrationPhase.UNCONFIRMED)) }
        assertFails { IosProductRoot.startupNeedsResume(null, journal(MigrationPhase.COMMITTED)) }
        assertFails { IosProductRoot.startupNeedsResume(null, journal(MigrationPhase.INVENTORY).copy(total = 7)) }
        assertFails { IosProductRoot.startupNeedsResume(null, journal(MigrationPhase.INVENTORY).copy(sourceKind = MigrationSourceKind.LEGACY_FILES)) }
    }
}
