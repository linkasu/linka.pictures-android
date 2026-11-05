@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package su.linka.pictures.migration

import androidx.sqlite.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.random.Random
import kotlin.concurrent.atomics.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.*
import okio.ByteString.Companion.toByteString
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.linka.*
import su.linka.pictures.storage.StorageRuntime
import su.linka.pictures.storage.StorageAbort

// Real SQLite and filesystem; callback credentials/inspection are NOT access-store/native proof.
class LegacyUpgradeTest {
    private val fs = FileSystem.SYSTEM
    private fun <T> OperationResult<T>.ok(): T = assertIs<OperationResult.Completed<T>>(this, toString()).value
    private fun OperationResult<*>.problem(code: ProblemCode) = assertEquals(code, assertIs<OperationResult.Failed>(this).problem.code)

    private inner class Rig(val home: Path) {
        val root = home / "new"
        val legacy = home / "sets"
        val codecFiles = CodecTestRig()
        var sequence = 0
        val ids = IdSource { "synthetic-${++sequence}" }
        var settings = LegacyUpgradeSettings(emptyMap(), false)
        var reads = 0
        var decoded = 0
        var prepares = 0
        var validations = 0
        var initialized = false
        var afterDecode: suspend () -> Unit = {}
        var prepare: suspend () -> OperationResult<Unit> = { initialized = true; OperationResult.Completed(Unit) }
        var validate: suspend () -> OperationResult<Unit> = {
            if (initialized) OperationResult.Completed(Unit) else OperationResult.Failed(Problem(ProblemCode.INTEGRITY_FAILURE))
        }

        fun coordinator(files: FileSystem = fs, driver: SQLiteDriver = BundledSQLiteDriver()) = LegacyUpgrade(root, legacy, files, driver, { codecFiles }, codecFiles, ids,
            readSettings = { reads++; settings },
            prepareCredentials = { prepares++; prepare() }, validateCredentials = { validations++; validate() },
            readBoard = { file, name, media, registered ->
                assertTrue(file.path in codecFiles.temporary, "legacy path must never bypass owned native temporaries")
                decoded++
                LinkaCodec(media, registered, codecFiles, ids).read(file, name).also { afterDecode() }
            })

        fun source(name: String, version: String = "1.2", withoutSpace: Boolean = false): ByteArray {
            val json = """{"version":"$version","rows":"1","columns":"1","withoutSpace":$withoutSpace,"cards":[{"id":7,"cardType":0,"title":"synthetic"}]}"""
            return raw(name, syntheticZip(listOf(configEntry(json))))
        }
        fun raw(name: String, bytes: ByteArray): ByteArray {
            fs.createDirectories(legacy)
            fs.write(legacy / name) { write(bytes) }
            return bytes
        }
        fun journal() = AdmissionFiles(root, fs, ids).read("journal")!!
    }

    private suspend fun owned(block: suspend (Rig) -> Unit) {
        val home = fs.canonicalize(FileSystem.SYSTEM_TEMPORARY_DIRECTORY) / "p09-synthetic-${Random.nextLong().toULong()}"
        fs.createDirectory(home, mustCreate = true)
        try { block(Rig(home)) } finally { fs.deleteRecursively(home) }
    }

    @Test fun actualLegacyCodecSettingsCaseIdentityAndExactCorruptRetention() = runTest { owned { rig ->
        // macOS checkout may be case-insensitive: distinct real files advertise controlled source names.
        val original = rig.source("upper", "1.0", withoutSpace = true)
        rig.source("lower", "1.2")
        val corrupt = rig.raw("broken", "original corrupt bytes".encodeToByteArray())
        rig.source("unknown", "2.99")
        rig.settings = LegacyUpgradeSettings(mapOf("A.linka" to 0, "a.linka" to 3), false)
        val caseFiles = object : ForwardingFileSystem(fs) {
            private fun backing(path: Path) = when (path) {
                rig.legacy / "A.linka" -> rig.legacy / "upper"
                rig.legacy / "a.linka" -> rig.legacy / "lower"
                else -> path
            }
            override fun list(dir: Path): List<Path> = super.list(dir).map {
                if (dir != rig.legacy) it else when (it.name) {
                    "upper" -> rig.legacy / "A.linka"
                    "lower" -> rig.legacy / "a.linka"
                    else -> it
                }
            }
            override fun metadataOrNull(path: Path) = super.metadataOrNull(backing(path))
            override fun source(file: Path) = super.source(backing(file))
            override fun canonicalize(path: Path): Path = if (backing(path) != path) path else super.canonicalize(path)
        }
        val result = rig.coordinator(caseFiles).open().ok()
        try {
            assertEquals(2, result.report.imported)
            assertEquals(2, result.report.retained)
            assertEquals(MigrationPhase.COMMITTED, result.report.phase)
            val entries = result.runtime.library.listing.value.items
            assertEquals(listOf("A.linka", "a.linka", "broken", "unknown"), entries.map { it.name })
            assertEquals(4, entries.map { it.id }.toSet().size)
            for ((index, mode) in listOf(InteractionMode.DIRECT, InteractionMode.COMPOSE).withIndex()) {
                val board = result.runtime.library.board(entries[index].id).ok()
                assertEquals(mode, board.board.document.behavior.defaultMode)
                assertEquals(if (index == 0) InputStyle.LITERAL else InputStyle.CARDS, board.board.document.behavior.inputStyle)
                board.lease.close()
            }
            assertFalse(result.runtime.library.settings().ok().boards.getValue(entries[0].id).showPageButtons)
            assertTrue(result.runtime.library.settings().ok().boards.getValue(entries[1].id).showPageButtons)
            result.runtime.library.snapshot().ok().use { snapshot ->
                val retained = snapshot.retained().toList()
                val broken = retained.single { it.name == "broken" }
                assertEquals(RetainedReason.INVALID_ARCHIVE, broken.reason)
                assertEquals(corrupt.toByteString().sha256().hex(), broken.archive.value)
                assertContentEquals(corrupt, result.runtime.media.source(broken.archive, snapshot.lease).ok().buffer().use { it.readByteArray() })
                assertEquals(RetainedReason.UNSUPPORTED_VERSION, retained.single { it.name == "unknown" }.reason)
            }
            assertContentEquals(original, fs.read(rig.legacy / "upper") { readByteArray() })
            assertContentEquals(corrupt, fs.read(rig.legacy / "broken") { readByteArray() })
            assertEquals(0, rig.codecFiles.openLeases)
            assertTrue(rig.codecFiles.temporary.isEmpty())
        } finally { result.runtime.close() }
    } }

    @Test fun gridValuesZeroThroughThreeInvalidAndDefaultPreserveCodecInputStyle() = runTest { owned { rig ->
        val values = listOf(0, 1, 2, 3, -1, 4)
        for (i in values.indices) rig.source("$i")
        rig.source("default")
        rig.settings = LegacyUpgradeSettings(values.mapIndexed { index, value -> "$index" to value }.toMap(), true)
        val result = rig.coordinator().open().ok()
        try {
            val presentation = result.runtime.library.settings().ok().boards
            for ((index, entry) in result.runtime.library.listing.value.items.withIndex()) {
                val grid = values.getOrElse(index) { 3 }
                val board = result.runtime.library.board(entry.id).ok()
                assertEquals(if (grid == 1 || grid == 3) InteractionMode.COMPOSE else InteractionMode.DIRECT, board.board.document.behavior.defaultMode)
                assertEquals(grid == 2 || grid == 3, presentation.getValue(entry.id).showPageButtons)
                board.lease.close()
            }
        } finally { result.runtime.close() }
    } }

    @Test fun emptyNewInstallStillInitializesCredentialsAndActivatesRealSqlite() = runTest { owned { rig ->
        val result = rig.coordinator().open().ok()
        assertEquals(0, result.report.total)
        assertTrue(rig.initialized)
        assertEquals(1, rig.prepares)
        result.runtime.close()
        assertTrue(fs.exists(rig.root / "libraries" / rig.journal().migrationId / "library.sqlite3"))
    } }

    @Test fun interruptedCredentialCallbackRequiresExplicitReopenAndSkipsCommittedBoards() = runTest {
        for (after in listOf(false, true)) owned { rig ->
            rig.source("board")
            rig.prepare = {
                if (after) rig.initialized = true
                throw CancellationException("synthetic termination")
            }
            assertFailsWith<CancellationException> { rig.coordinator().open() }
            assertFalse(fs.exists(rig.root / "active"))
            assertEquals(MigrationPhase.UNCONFIRMED, rig.journal().phase)
            rig.coordinator().open().problem(ProblemCode.INTERRUPTED)
            rig.prepare = { rig.initialized = true; OperationResult.Completed(Unit) }
            val decodedBefore = rig.decoded
            val result = rig.coordinator().open(resumeInterrupted = true).ok()
            assertEquals(decodedBefore, rig.decoded)
            assertEquals(1, result.runtime.library.listing.value.items.size)
            result.runtime.close()
        }
    }

    @Test fun unknownCredentialCallbackFreezesInstanceAndDoesNotActivate() = runTest { owned { rig ->
        rig.source("board")
        rig.prepare = { OperationResult.UnconfirmedCommit }
        val coordinator = rig.coordinator()
        assertEquals(OperationResult.UnconfirmedCommit, coordinator.open())
        assertEquals(MigrationPhase.UNCONFIRMED, coordinator.state.value.phase)
        assertEquals(OperationResult.UnconfirmedCommit, coordinator.open(resumeInterrupted = true))
        assertEquals(1, rig.prepares)
        assertFalse(fs.exists(rig.root / "active"))
        rig.prepare = { rig.initialized = true; OperationResult.Completed(Unit) }
        rig.coordinator().open(true).ok().runtime.close()
    } }

    @Test fun activeStartupPreservesNewWorkAndNeverReadsSettingsOrPreparesCredentials() = runTest { owned { rig ->
        rig.source("board")
        val first = rig.coordinator().open().ok()
        val entry = first.runtime.library.listing.value.items.single()
        first.runtime.library.renameBoard(entry.id, "new work", entry.revision, WritePermit(0) { OperationResult.Completed(Unit) }).ok()
        first.runtime.close()
        val reads = rig.reads
        val prepares = rig.prepares
        rig.prepare = { error("must not reimport a modern credential") }
        rig.raw("board", byteArrayOf(9))
        rig.settings = LegacyUpgradeSettings(mapOf("board" to 0), true)
        fs.write(rig.root / "journal") { writeUtf8("postcommit progress lost") }
        val reopened = rig.coordinator().open().ok()
        assertEquals("new work", reopened.runtime.library.listing.value.items.single().name)
        assertEquals(reads, rig.reads)
        assertEquals(prepares, rig.prepares)
        reopened.runtime.close()
        rig.initialized = false
        rig.coordinator().open().problem(ProblemCode.INTEGRITY_FAILURE)
        assertEquals(reads, rig.reads)
    } }

    @Test fun activeRenameExactOldNewUnknownAndPostcommitProgressFailure() = runTest {
        for (mode in listOf("old", "new", "unknown", "progress")) owned { rig ->
            rig.source("board")
            var observing = false
            val fault = object : ForwardingFileSystem(fs) {
                override fun atomicMove(source: Path, target: Path) {
                    if (mode == "progress" && target.name == "journal" && fs.exists(rig.root / "active")) throw IOException("synthetic progress failure")
                    if (target.name != "active") return super.atomicMove(source, target)
                    if (mode != "old") super.atomicMove(source, target)
                    if (mode != "progress") { observing = true; throw IOException("synthetic active acknowledgement loss") }
                }
                override fun source(file: Path): Source {
                    if (mode == "unknown" && observing && file.name == "active") throw IOException("synthetic observer unavailable")
                    return super.source(file)
                }
            }
            val result = rig.coordinator(fault).open()
            when (mode) {
                "old" -> { result.problem(ProblemCode.IO_FAILURE); assertFalse(fs.exists(rig.root / "active")) }
                "unknown" -> assertEquals(OperationResult.UnconfirmedCommit, result)
                else -> result.ok().runtime.close()
            }
            if (mode != "old") {
                val decoded = rig.decoded
                rig.coordinator().open().ok().runtime.close()
                assertEquals(decoded, rig.decoded)
            }
        }
    }

    @Test fun sourceOrSettingsMutationDuringCredentialPreparationBlocksActivationAndResume() = runTest {
        for (kind in listOf("source", "settings")) owned { rig ->
            rig.source("board")
            rig.prepare = {
                rig.initialized = true
                if (kind == "source") rig.raw("board", byteArrayOf(1)) else rig.settings = LegacyUpgradeSettings(mapOf("board" to 0), false)
                OperationResult.Completed(Unit)
            }
            rig.coordinator().open().problem(ProblemCode.INTEGRITY_FAILURE)
            assertFalse(fs.exists(rig.root / "active"))
            rig.coordinator().open(true).problem(ProblemCode.CONFLICT)
        }
    }

    @Test fun oversizedNonregularSymlinkAndDiskFailureNeverActivateOrDeleteSource() = runTest {
        for (kind in listOf("oversize", "directory", "symlink", "disk")) owned { rig ->
            val original = rig.source("board")
            if (kind == "directory") fs.createDirectory(rig.legacy / "folder")
            if (kind == "symlink") fs.createSymlink(rig.legacy / "alias", rig.legacy / "board")
            val fault = object : ForwardingFileSystem(fs) {
                override fun metadataOrNull(path: Path): FileMetadata? {
                    val actual = super.metadataOrNull(path)
                    return if (kind == "oversize" && path == rig.legacy / "board") actual?.copy(size = 2L * 1024 * 1024 * 1024 + 1) else actual
                }
                override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
                    if (kind == "disk") throw IOException("synthetic no space")
                    return super.openReadWrite(file, mustCreate, mustExist)
                }
            }
            assertIs<OperationResult.Failed>(rig.coordinator(fault).open())
            assertFalse(fs.exists(rig.root / "active"))
            assertContentEquals(original, fs.read(rig.legacy / "board") { readByteArray() })
            assertEquals(0, rig.prepares)
        }
    }

    @Test fun unknownJournalForeignGenerationAndMissingStageDatabaseFailClosedWithoutSqliteCreation() = runTest {
        for (kind in listOf("unknown", "foreign", "missing-db")) owned { rig ->
            rig.source("board")
            fs.createDirectories(rig.root / "libraries")
            if (kind == "unknown") {
                fs.write(rig.root / "journal") { writeUtf8("""{"schema":99}""") }
            } else if (kind == "foreign") {
                fs.createDirectory(rig.root / "libraries" / "foreign")
                fs.write(rig.root / "libraries" / "foreign" / "sentinel") { writeUtf8("untouched") }
            } else {
                val inventory = inventory(fs, rig.legacy, rig.settings)
                fs.createDirectory(rig.root / "libraries" / "owned")
                AdmissionFiles(rig.root, fs, rig.ids).write("journal", AdmissionRecord(migrationId = "owned", sourceDigest = inventory.digest, phase = MigrationPhase.INVENTORY, total = 1))
            }
            assertIs<OperationResult.Failed>(rig.coordinator().open(true))
            assertFalse(fs.exists(rig.root / "active"))
            assertFalse(fs.exists(rig.root / "libraries" / "owned" / "library.sqlite3"))
            assertEquals(0, rig.decoded)
            if (kind == "foreign") assertEquals("untouched", fs.read(rig.root / "libraries" / "foreign" / "sentinel") { readUtf8() })
        }
    }

    @Test fun generationJournalGuardDeniesPublicationAfterActualDecode() = runTest { owned { rig ->
        rig.source("board")
        rig.afterDecode = {
            val old = rig.journal()
            AdmissionFiles(rig.root, fs, rig.ids).write("journal", old.copy(phase = MigrationPhase.READY_TO_PUBLISH))
        }
        rig.coordinator().open().problem(ProblemCode.STALE_REQUEST)
        val actual = StorageRuntime.open(rig.root / "libraries" / rig.journal().migrationId, fs, BundledSQLiteDriver()).ok()
        assertTrue(actual.library.listing.value.items.isEmpty())
        actual.close()
        assertEquals(0, rig.prepares)
        assertFalse(fs.exists(rig.root / "active"))
    } }

    @Test fun interruptedJournalBoundariesReopenActualSqliteOrFailClosedBeforeDatabaseExists() = runTest {
        class Termination : Error()
        for (ordinal in 1..7) for (after in listOf(false, true)) owned { rig ->
            val original = rig.source("board")
            var moves = 0
            val fault = object : ForwardingFileSystem(fs) {
                override fun atomicMove(source: Path, target: Path) {
                    if (target.name != "journal" || ++moves != ordinal) return super.atomicMove(source, target)
                    if (after) super.atomicMove(source, target)
                    throw Termination()
                }
            }
            try { rig.coordinator(fault).open().ok().runtime.close() } catch (_: Termination) { }
            val journal = AdmissionFiles(rig.root, fs, rig.ids).read("journal")
            val active = fs.exists(rig.root / "active")
            val result = rig.coordinator().open(resumeInterrupted = true)
            if (!active && journal != null && !fs.exists(rig.root / "libraries" / journal.migrationId / "library.sqlite3")) {
                result.problem(ProblemCode.INTEGRITY_FAILURE)
            } else {
                val opened = result.ok()
                assertEquals(1, opened.runtime.library.listing.value.items.size)
                opened.runtime.close()
            }
            assertContentEquals(original, fs.read(rig.legacy / "board") { readByteArray() })
        }
    }

    @Test fun cancellationAcknowledgedAfterActiveRenameCannotRevokeActivation() = runTest { owned { rig ->
        rig.source("board")
        val fault = object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                super.atomicMove(source, target)
                if (target.name == "active") throw CancellationException("synthetic late cancellation")
            }
        }
        val coordinator = rig.coordinator(fault)
        val result = coordinator.open().ok()
        assertEquals(MigrationPhase.COMMITTED, coordinator.state.value.phase)
        result.runtime.close()
        assertTrue(fs.exists(rig.root / "active"))
    } }

    private class MutationFaultDriver(private val prefix: String, private val after: Boolean, private val unknown: Boolean = false) : SQLiteDriver {
        var fired = false
        private var armed = false
        override fun open(fileName: String): SQLiteConnection {
            if (unknown && fired) throw IOException("synthetic independent observer unavailable")
            val connection = BundledSQLiteDriver().open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            if (!fired && sql.startsWith(prefix)) armed = true
                            val fault = !fired && armed && sql == "COMMIT"
                            if (fault && !after) { fired = true; throw IOException("synthetic precommit failure") }
                            val result = statement.step()
                            if (fault) { fired = true; throw IOException("synthetic postcommit acknowledgement failure") }
                            return result
                        }
                    }
                }
            }
        }
    }

    @Test fun archivePreparationEntryPublicationAndSettingsCommitsResumeWithoutDuplicateOrLostBytes() = runTest {
        for (prefix in listOf("INSERT INTO assets", "INSERT OR REPLACE INTO entries", "INSERT INTO entries", "UPDATE library_meta SET settings_json")) {
            for (after in listOf(false, true)) owned { rig ->
                rig.source("good")
                val corrupt = rig.raw("broken", byteArrayOf(1, 2, 3))
                val driver = MutationFaultDriver(prefix, after)
                val first = rig.coordinator(driver = driver).open()
                assertTrue(driver.fired)
                if (after) first.ok().runtime.close() else first.problem(ProblemCode.IO_FAILURE)
                val reopened = rig.coordinator().open(resumeInterrupted = true).ok()
                try {
                    assertEquals(2, reopened.runtime.library.listing.value.items.size)
                    reopened.runtime.library.snapshot().ok().use { snapshot ->
                        val retained = snapshot.retained().toList().single()
                        assertEquals(corrupt.toByteString().sha256().hex(), retained.archive.value)
                        assertContentEquals(corrupt, reopened.runtime.media.source(retained.archive, snapshot.lease).ok().buffer().use { it.readByteArray() })
                    }
                    assertEquals(2, reopened.runtime.library.settings().ok().boards.size)
                } finally { reopened.runtime.close() }
            }
        }
    }

    @Test fun unknownActualSqliteBoardCommitRequiresReopenThenSkipsAuthoritativelyCommittedId() = runTest { owned { rig ->
        rig.source("board")
        val driver = MutationFaultDriver("INSERT OR REPLACE INTO entries", after = true, unknown = true)
        val coordinator = rig.coordinator(driver = driver)
        assertEquals(OperationResult.UnconfirmedCommit, coordinator.open())
        assertFalse(fs.exists(rig.root / "active"))
        assertEquals(OperationResult.UnconfirmedCommit, coordinator.open(true))
        assertEquals(1, rig.decoded)
        val resumed = rig.coordinator().open(true).ok()
        assertEquals(1, resumed.runtime.library.listing.value.items.size)
        assertEquals(1, rig.decoded)
        resumed.runtime.close()
    } }

    @Test fun ownedInterruptedJournalTemporaryIsPreservedButForeignStageNeverAdmittedOrDeleted() = runTest {
        for (foreign in listOf(false, true)) owned { rig ->
            rig.source("board")
            rig.prepare = { throw CancellationException("synthetic termination") }
            assertFailsWith<CancellationException> { rig.coordinator().open() }
            val record = rig.journal()
            val leftover = rig.root / ".admission-owned-leftover"
            val json = kotlinx.serialization.json.Json { encodeDefaults = true }
            val bytes = json.encodeToString(AdmissionRecord.serializer(), if (foreign) record.copy(migrationId = "foreign") else record).encodeToByteArray()
            fs.write(leftover) { write(bytes) }
            rig.coordinator().open().problem(if (foreign) ProblemCode.CONFLICT else ProblemCode.INTERRUPTED)
            rig.prepare = { rig.initialized = true; OperationResult.Completed(Unit) }
            val result = rig.coordinator().open(true)
            if (foreign) result.problem(ProblemCode.CONFLICT) else result.ok().runtime.close()
            assertContentEquals(bytes, fs.read(leftover) { readByteArray() })
        }
    }

    @Test fun sqlCommitGuardNeverReadsAdmissionFilesystem() = runTest { owned { rig ->
        rig.source("board")
        rig.raw("broken", byteArrayOf(1, 2, 3))
        val inTransaction = AtomicReference(false)
        val driver = object : SQLiteDriver {
            override fun open(fileName: String): SQLiteConnection {
                val connection = BundledSQLiteDriver().open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement = connection.prepare(sql)
                        return object : SQLiteStatement by statement {
                            override fun step(): Boolean {
                                val result = statement.step()
                                if (sql == "BEGIN IMMEDIATE") inTransaction.store(true)
                                if (sql == "COMMIT" || sql == "ROLLBACK") inTransaction.store(false)
                                return result
                            }
                        }
                    }
                }
            }
        }
        val asserting = object : ForwardingFileSystem(fs) {
            private fun check(path: Path) {
                if (path == rig.root || path.parent == rig.root || path.parent == rig.root / "libraries") {
                    assertFalse(inTransaction.load(), "admission filesystem I/O inside SQL transaction: $path")
                }
            }
            override fun metadataOrNull(path: Path): FileMetadata? { check(path); return super.metadataOrNull(path) }
            override fun canonicalize(path: Path): Path { check(path); return super.canonicalize(path) }
            override fun source(file: Path): Source { check(file); return super.source(file) }
        }
        val opened = rig.coordinator(asserting, driver).open().ok()
        assertEquals(1, opened.report.imported)
        assertEquals(1, opened.report.retained)
        opened.runtime.close()
    } }

    private class CloseFaultDriver : SQLiteDriver {
        var opens = 0
        var closeAttempts = 0
        var failClose = false
        var failSnapshot = false
        private val actual = mutableListOf<SQLiteConnection>()
        override fun open(fileName: String): SQLiteConnection {
            opens++
            val connection = BundledSQLiteDriver().open(fileName)
            actual += connection
            return object : SQLiteConnection by connection {
                override fun close() {
                    closeAttempts++
                    if (failClose) throw IOException("synthetic owned close failure")
                    connection.close()
                    actual.remove(connection)
                }
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            if (failSnapshot && sql.startsWith("SELECT order_index,kind,revision,payload_json")) {
                                failClose = true
                                throw StorageAbort(OperationResult.Failed(Problem(ProblemCode.INTEGRITY_FAILURE)))
                            }
                            return statement.step()
                        }
                    }
                }
            }
        }
        fun cleanup() { actual.toList().forEach { it.close() }; actual.clear() }
    }

    @Test fun closeFailureBeforeActivationBlocksSameCoordinatorWithoutNewWriter() = runTest { owned { rig ->
        val original = rig.source("board")
        val driver = CloseFaultDriver()
        rig.prepare = { rig.initialized = true; driver.failClose = true; OperationResult.Completed(Unit) }
        val coordinator = rig.coordinator(driver = driver)
        try {
            coordinator.open().problem(ProblemCode.IO_FAILURE)
            assertFalse(fs.exists(rig.root / "active"))
            assertEquals(MigrationPhase.BLOCKED, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.requiresRestart)
            val opens = driver.opens
            driver.failClose = false
            coordinator.open(true).problem(ProblemCode.IO_FAILURE)
            assertEquals(opens, driver.opens)
            assertTrue(driver.closeAttempts >= 2)
            assertContentEquals(original, fs.read(rig.legacy / "board") { readByteArray() })
        } finally { driver.cleanup() }
    } }

    @Test fun existingActiveReadFailureKeepsPrimaryWhenOwnedCloseFailsAndNeverOpensSecondWriter() = runTest { owned { rig ->
        val original = rig.source("board")
        rig.coordinator().open().ok().runtime.close()
        val active = fs.read(rig.root / "active") { readByteArray() }
        val sentinel = rig.home / "sentinel"
        fs.write(sentinel) { writeUtf8("untouched") }
        val driver = CloseFaultDriver().also { it.failSnapshot = true }
        val coordinator = rig.coordinator(driver = driver)
        try {
            coordinator.open().problem(ProblemCode.INTEGRITY_FAILURE)
            assertEquals(MigrationPhase.BLOCKED, coordinator.state.value.phase)
            assertTrue(coordinator.state.value.requiresRestart)
            val opens = driver.opens
            coordinator.open(true).problem(ProblemCode.IO_FAILURE)
            assertEquals(opens, driver.opens)
            assertTrue(driver.closeAttempts >= 2)
            assertContentEquals(active, fs.read(rig.root / "active") { readByteArray() })
            assertContentEquals(original, fs.read(rig.legacy / "board") { readByteArray() })
            assertEquals("untouched", fs.read(sentinel) { readUtf8() })
        } finally { driver.cleanup() }
    } }

    @Test fun atomicPermitRevokesOnPhaseNonceEpochJobAndCloseWithoutAnyFilesystem() {
        val authority = MigrationAuthority()
        val job = Job()
        fun start(epoch: Long = 0): WritePermit {
            authority.begin("owned", epoch)
            authority.advance("owned", MigrationPhase.COPYING_VALIDATING)
            return authority.permit(job)
        }
        val permit = start()
        permit.check().ok()
        authority.advance("owned", MigrationPhase.ACCESS_READY)
        permit.check().problem(ProblemCode.STALE_REQUEST)
        val oldGeneration = start()
        start()
        oldGeneration.check().problem(ProblemCode.STALE_REQUEST)
        val oldEpoch = start()
        start(1)
        oldEpoch.check().problem(ProblemCode.STALE_REQUEST)
        val revoked = start()
        authority.revoke()
        revoked.check().problem(ProblemCode.STALE_REQUEST)
        val cancelled = start()
        job.cancel()
        assertFailsWith<CancellationException> { cancelled.check() }
    }

    @Test fun atomicRevocationBeforeActualSqliteCommitRollsBackPublication() = runTest { owned { rig ->
        val authority = MigrationAuthority()
        val driver = object : SQLiteDriver {
            override fun open(fileName: String): SQLiteConnection {
                val connection = BundledSQLiteDriver().open(fileName)
                return object : SQLiteConnection by connection {
                    override fun prepare(sql: String): SQLiteStatement {
                        val statement = connection.prepare(sql)
                        return object : SQLiteStatement by statement {
                            override fun step(): Boolean = statement.step().also {
                                if (sql.startsWith("INSERT OR REPLACE INTO entries")) authority.revoke()
                            }
                        }
                    }
                }
            }
        }
        val root = rig.home / "guard-sqlite"
        val runtime = StorageRuntime.open(root, fs, driver).ok()
        try {
            authority.begin("owned", runtime.library.listing.value.epoch)
            authority.advance("owned", MigrationPhase.COPYING_VALIDATING)
            val permit = authority.permit(currentCoroutineContext())
            var checks = 0
            val watched = WritePermit(permit.epoch) { checks++; permit.check() }
            val board = BoardDocument(BoardId("stable"), "synthetic", listOf(BoardPage(PageId("page"), 1, 1, emptyList())))
            runtime.library.addBoard(board, watched).problem(ProblemCode.STALE_REQUEST)
            assertEquals(2, checks, "revoke must deny the final precommit guard, not merely preflight")
        } finally { runtime.close() }
        val reopened = StorageRuntime.open(root, fs, BundledSQLiteDriver()).ok()
        assertTrue(reopened.library.listing.value.items.isEmpty())
        reopened.close()
    } }
}
