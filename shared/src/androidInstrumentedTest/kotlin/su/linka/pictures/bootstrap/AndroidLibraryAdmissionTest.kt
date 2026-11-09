package su.linka.pictures.bootstrap

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import okio.*
import okio.Path.Companion.toPath
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.access.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.migration.*
import su.linka.pictures.platform.capabilities.AndroidMediaInspectionPort
import su.linka.pictures.platform.files.AndroidPlatformFilesPort
import su.linka.pictures.storage.StorageRuntime

/** Real private files, SQLite, preferences, credentials and native media inspection; no product sandbox. */
class AndroidLibraryAdmissionTest {
    private fun <T> OperationResult<T>.ok(): T = when (this) {
        is OperationResult.Completed -> value
        else -> error("Expected success: $this")
    }

    private class Rig {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "admission-${UUID.randomUUID()}"
        val root = File(app.cacheDir, prefix).also { check(it.mkdir()) }
        val preferences = mutableListOf<SharedPreferences>()
        var forbidPreferences = false
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName() = "su.linka.pictures.development"
            override fun getFilesDir() = File(root, "files").also { check(it.isDirectory || it.mkdir()) }
            override fun getNoBackupFilesDir() = File(root, "no-backup").also { check(it.isDirectory || it.mkdir()) }
            override fun getCacheDir() = File(root, "cache").also { check(it.isDirectory || it.mkdir()) }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(!forbidPreferences) { "Active startup must not open legacy preferences" }
                return super.getSharedPreferences("$prefix-$name", mode).also { preferences += it }
            }
        }
        val fs = FileSystem.SYSTEM
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val ids = IdSource { UUID.randomUUID().toString() }
        private var nativeFiles: Pair<Path, PlatformFilesPort>? = null
        fun admission(fileSystem: FileSystem = fs) = AndroidLibraryAdmission(context, fileSystem, BundledSQLiteDriver(), ids, AndroidMediaInspectionPort()) { generation ->
            nativeFiles?.let { check(it.first == generation); return@AndroidLibraryAdmission it.second }
            AndroidPlatformFilesPort(context, context.cacheDir.path.toPath(), generation / "media", fs,
                { _, _, _ -> error("Migration must not present a picker") }, { false }, scope)
                .also { nativeFiles = generation to it }
        }
        suspend fun close() {
            scope.coroutineContext.job.cancelAndJoin()
            withContext(Dispatchers.IO) {
                preferences.forEach { check(it.edit().clear().commit()) }
                check(root.deleteRecursively())
            }
        }
    }

    @Test fun legacyOnePointThreeRealCredentialsAndIndependentActiveReopen() = runBlocking {
        val rig = Rig()
        try {
            val source = File(rig.context.filesDir, "sets/custom.linka").also { check(it.parentFile!!.mkdir()) }
            val bytes = ByteArrayOutputStream().also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("config.json"))
                    zip.write("""{"version":"1.3","rows":1,"columns":1,"cards":[{"id":1,"cardType":0,"title":"original"}]}""".toByteArray())
                    zip.closeEntry()
                }
            }.toByteArray()
            source.writeBytes(bytes)
            rig.context.getSharedPreferences("my", Context.MODE_PRIVATE).edit().putBoolean("ASSETS_LOADER", true).putInt("custom.linka", 2).commit()
            rig.context.getSharedPreferences("${rig.context.packageName}_preferences", Context.MODE_PRIVATE).edit()
                .putBoolean("parent", true).putString("parent_password", "мёд🔑").commit()
            val admission = rig.admission()
            val opened = admission.open(false).ok()
            val entry = opened.runtime.library.listing.value.items.single()
            assertEquals(1, opened.report.imported)
            val board = opened.runtime.library.board(entry.id).ok()
            assertEquals(InteractionMode.DIRECT, board.board.document.behavior.defaultMode)
            board.lease.close()
            assertTrue(opened.runtime.library.settings().ok().boards.getValue(entry.id).showPageButtons)
            opened.runtime.library.renameBoard(entry.id, "new work", entry.revision, WritePermit(0) { OperationResult.Completed(Unit) }).ok()
            opened.runtime.close()
            assertArrayEquals(bytes, source.readBytes())
            val credential = admission.credentials.read().ok()!!.copyOf()
            val unavailable = object : DeviceOwnerVerifier {
                override suspend fun availability() = OwnerVerificationAvailability.UNAVAILABLE
                override suspend fun verify(requestId: RequestId, ownerId: String): OperationResult<Unit> = error("No OS prompt during migration")
                override fun cancel(requestId: RequestId) = Unit
            }
            val access = LocalAssistantAccess.open(admission.credentials, unavailable, rig.ids).ok()
            access.unlock(" мёд🔑 ", "synthetic-owner").ok()
            rig.forbidPreferences = true
            val reopened = rig.admission().open(false).ok()
            assertEquals("new work", reopened.runtime.library.listing.value.items.single().name)
            assertArrayEquals(credential, admission.credentials.read().ok())
            assertArrayEquals(bytes, source.readBytes())
            reopened.runtime.close()
        } finally { rig.close() }
    }

    @Test fun existingDevelopmentLibraryKeepsIdsMessageAndCredentialsInPlace() = runBlocking {
        val rig = Rig()
        try {
            val directory = File(rig.context.filesDir, "pokazhi-development").path.toPath()
            val original = StorageRuntime.open(directory, rig.fs, BundledSQLiteDriver()).ok()
            val board = BoardDocument(BoardId("existing-board"), "existing", listOf(BoardPage(PageId("existing-page"), 1, 1, emptyList())))
            original.library.addBoard(board, WritePermit(0) { OperationResult.Completed(Unit) }).ok()
            val message = StoredMessage(0, 7, MessageDraft(listOf(MessagePart.Text(MessagePartId("existing-text"), "unchanged phrase"))))
            original.messages.saveMessage(message).ok()
            original.close()
            val admission = rig.admission()
            LegacyCredentialImporter(admission.credentials).prepare(false, null) { OperationResult.Completed(Unit) }.ok()
            val credential = admission.credentials.read().ok()!!.copyOf()
            rig.forbidPreferences = true
            val opened = admission.open(false).ok()
            assertEquals("existing-board", opened.runtime.library.listing.value.items.single().id.value)
            assertEquals(message, opened.runtime.messages.message().ok())
            assertTrue(File(directory.toString(), "library.sqlite3").isFile)
            assertFalse(File(rig.context.filesDir, "pokazhi-v1").exists())
            assertArrayEquals(credential, admission.credentials.read().ok())
            opened.runtime.close()
        } finally { rig.close() }
    }

    @Test fun freshInstallSeedsFiveEntriesButDoesNotRecreateDeletedBoards() = runBlocking {
        val rig = Rig()
        try {
            val first = rig.admission().open(false).ok()
            assertEquals(5, first.report.total)
            assertEquals(5, first.report.imported)
            assertEquals(0, first.report.retained)
            assertEquals(5, first.report.imported + first.report.retained)
            val entries = first.runtime.library.listing.value.items
            assertEquals(5, entries.size)
            assertTrue(entries.all { it.kind == LibraryEntryKind.BOARD })
            assertEquals(5, entries.map { it.id }.toSet().size)
            assertEquals(setOf("Общаюсь", "Еда и питьё", "Занятия", "Самочувствие", "Буквы"), entries.map { it.name }.toSet())
            val removed = entries.first()
            first.runtime.library.deleteBoard(removed.id, removed.revision, WritePermit(0) { OperationResult.Completed(Unit) }).ok()
            first.runtime.close()
            rig.forbidPreferences = true
            val next = rig.admission().open(false).ok()
            assertEquals(4, next.runtime.library.listing.value.items.size)
            assertFalse(next.runtime.library.listing.value.items.any { it.id == removed.id })
            next.runtime.close()
        } finally { rig.close() }
    }

    @Test fun missingDevelopmentCredentialNeverCreatesAnUnprotectedReplacement() = runBlocking {
        val rig = Rig()
        try {
            val directory = File(rig.context.filesDir, "pokazhi-development").path.toPath()
            StorageRuntime.open(directory, rig.fs, BundledSQLiteDriver()).ok().close()
            val database = File(directory.toString(), "library.sqlite3")
            val before = database.readBytes()
            val admission = rig.admission()
            val result = admission.open(false) as OperationResult.Failed
            assertEquals(ProblemCode.INTEGRITY_FAILURE, result.problem.code)
            assertArrayEquals(before, database.readBytes())
            assertNull(admission.credentials.read().ok())
        } finally { rig.close() }
    }

    @Test fun interruptedFiveAndPreChangeSevenBoardJournalsResumeTheirOriginalSource() = runBlocking {
        for (total in listOf(5, 7)) {
            val rig = Rig()
            try {
                val source: MigrationSource = if (total == 7) BundledMigrationSource(rig.context.assets) else StarterMigrationSource()
                val initial = inventory(source, LegacyUpgradeSettings(emptyMap(), false))
                val root = rig.context.filesDir.path.toPath() / "pokazhi-v1"
                rig.fs.createDirectories(root)
                val receipts = AdmissionFiles(root, rig.fs, rig.ids)
                receipts.write("journal", AdmissionRecord(migrationId = "resume-$total", sourceDigest = initial.digest,
                    phase = MigrationPhase.INVENTORY, total = total, sourceKind = MigrationSourceKind.BUNDLED))
                class Termination : Error()
                val fault = object : ForwardingFileSystem(rig.fs) {
                    override fun atomicMove(source: Path, target: Path) {
                        super.atomicMove(source, target)
                        if (target.name == "journal" && receipts.read("journal")?.imported == 1) throw Termination()
                    }
                }
                try { rig.admission(fault).open(true); fail("Expected interruption after the first board") }
                catch (_: Termination) { }
                assertEquals(1, receipts.read("journal")!!.imported)
                assertFalse(rig.fs.exists(root / "active"))
                val opened = rig.admission().open(true).ok()
                assertEquals(total, opened.report.total)
                assertEquals(total, opened.report.imported)
                assertEquals(0, opened.report.retained)
                assertEquals(initial.entries.map { it.name }.toSet(), opened.runtime.library.listing.value.items.map { it.name }.toSet())
                opened.runtime.close()
                // A stale unknown journal must not override a confirmed active library.
                rig.fs.write(root / "journal") { writeUtf8("corrupt stale journal") }
                rig.forbidPreferences = true
                val reopened = rig.admission().open(false).ok()
                assertEquals(total, reopened.runtime.library.listing.value.items.size)
                reopened.runtime.close()
            } finally { rig.close() }
        }
    }

    @Test fun unknownBundledJournalTotalIsNotReinterpretedAsFreshStarterData() = runBlocking {
        val rig = Rig()
        try {
            val root = rig.context.filesDir.path.toPath() / "pokazhi-v1"
            rig.fs.createDirectories(root)
            AdmissionFiles(root, rig.fs, rig.ids).write("journal", AdmissionRecord(migrationId = "unknown-source",
                sourceDigest = "0".repeat(64), phase = MigrationPhase.INVENTORY, total = 6, sourceKind = MigrationSourceKind.BUNDLED))
            val before = rig.fs.read(root / "journal") { readByteArray() }
            val failure = rig.admission().open(true) as OperationResult.Failed
            assertEquals(ProblemCode.CONFLICT, failure.problem.code)
            assertArrayEquals(before, rig.fs.read(root / "journal") { readByteArray() })
            assertFalse(rig.fs.exists(root / "active"))
            assertFalse(rig.fs.exists(root / "libraries"))
        } finally { rig.close() }
    }

    @Test fun interruptedBundledSeedNeverIgnoresLegacyWorkCreatedBetweenVersions() = runBlocking {
        val rig = Rig()
        try {
            class Termination : Error()
            val fault = object : ForwardingFileSystem(rig.fs) {
                override fun atomicMove(source: Path, target: Path) {
                    super.atomicMove(source, target)
                    if (target.name == "journal") throw Termination()
                }
            }
            try { rig.admission(fault).open(false); fail("Expected termination after first journal") }
            catch (_: Termination) { }
            val legacy = File(rig.context.filesDir, "sets/new-work.linka")
            check(legacy.parentFile!!.mkdir())
            val sentinel = byteArrayOf(1, 2, 3)
            legacy.writeBytes(sentinel)
            val result = rig.admission().open(true) as OperationResult.Failed
            assertEquals(ProblemCode.CONFLICT, result.problem.code)
            assertArrayEquals(sentinel, legacy.readBytes())
            assertFalse(File(rig.context.filesDir, "pokazhi-v1/active").exists())
        } finally { rig.close() }
    }
}
