@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.DelicateCoroutinesApi::class,
    kotlinx.coroutines.ObsoleteCoroutinesApi::class,
)

package su.linka.pictures.storage

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.*
import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import su.linka.pictures.application.*
import su.linka.pictures.application.authoring.AuthoringLease
import su.linka.pictures.application.communication.MessageLease
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

class StorageRuntimeTest {
    private val fs = FileSystem.SYSTEM
    private fun permit(epoch: Long = 0) = WritePermit(epoch) { OperationResult.Completed(Unit) }
    private fun board(id: String = "board", asset: AssetId? = null) = BoardDocument(
        BoardId(id), "Доска $id", listOf(BoardPage(PageId("page"), 2, 2, listOf(
            BoardCell(0, CellKind.CONTENT, CardDocument(CardId("card"), "Хочу", asset)),
        ))),
    )
    private fun draft(document: BoardDocument, form: EditorForm? = null) = EditorDraft(DraftId("draft"), null, null, document, form)
    private fun <T> OperationResult<T>.ok(): T = assertIs<OperationResult.Completed<T>>(this).value
    private fun OperationResult<*>.problem(code: ProblemCode) { assertEquals(code, assertIs<OperationResult.Failed>(this).problem.code) }
    private suspend fun fresh(block: suspend (Path) -> Unit) {
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "linka-p04-test-${token()}"
        fs.createDirectory(root, mustCreate = true)
        try { block(root) } finally { fs.deleteRecursively(root) }
    }
    private suspend fun open(root: Path, fileSystem: FileSystem = fs, driver: SQLiteDriver = BundledSQLiteDriver()) = StorageRuntime.open(root, fileSystem, driver).ok()
    private suspend fun media(runtime: StorageRuntime, bytes: String = "immutable") = runtime.media.prepare(Buffer().writeUtf8(bytes), MediaKind.IMAGE, "image/test").ok()
    private fun message(asset: AssetId, epoch: Long = 0, version: Long = 1) = StoredMessage(epoch, version, MessageDraft(listOf(
        MessagePart.Card(MessagePartId("part"), CardSelectionSnapshot(SelectionOrigin(BoardId("board"), 1, PageId("page"), CardId("card")), "Снимок", asset, SpeechSource.None)),
    )))

    @Test fun openCloseReopenPersistsActualSqlite() = runTest { fresh { root ->
        var runtime = open(root)
        try {
            val prepared = media(runtime)
            runtime.library.addBoard(board(asset = prepared.asset.id), permit()).ok()
            runtime.library.saveSettings(PortableSettings(highContrast = true), 1, permit()).ok()
            runtime.messages.saveMessage(message(prepared.asset.id, version = 7)).ok()
            prepared.lease.close()
        } finally { runtime.close() }
        runtime = open(root)
        try {
            val leased = runtime.library.board(BoardId("board")).ok()
            assertEquals(1, leased.board.revision)
            leased.lease.close()
            assertEquals(2, runtime.library.listing.value.revision)
            assertTrue(runtime.library.settings().ok().highContrast)
            assertEquals(7, runtime.messages.message().ok().version)
        } finally { runtime.close() }
    } }

    @Test fun competingExpectedRevisionWritersHaveOneWinner() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            runtime.library.addBoard(board(), permit()).ok()
            val results = coroutineScope { listOf("left", "right").map { name -> async(Dispatchers.Default) { runtime.library.renameBoard(BoardId("board"), name, 1, permit()) } }.awaitAll() }
            assertEquals(1, results.count { it is OperationResult.Completed })
            assertEquals(1, results.count { it is OperationResult.Failed && it.problem.code == ProblemCode.CONFLICT })
            assertEquals(2, runtime.library.listing.value.items.single().revision)
        } finally { runtime.close() }
    } }

    @Test fun draftFormRootsAndUnfinishedPublicationPreserveWork() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val prepared = media(runtime)
            val form = EditorForm.Card(PageId("page"), 1, CardId("new"), image = prepared.asset.id)
            val value = draft(board(), form)
            runtime.library.saveDraft(value, null, permit()).ok()
            prepared.lease.close()
            assertEquals(0, runtime.media.collectGarbage().ok().removedAssets)
            runtime.library.publishDraft(value.id, 1, null, permit()).problem(ProblemCode.UNFINISHED_FORM)
            runtime.library.saveDraft(value, null, permit()).problem(ProblemCode.CONFLICT)
            val loaded = runtime.library.draft(value.id).ok()
            assertEquals(value, loaded.draft.draft)
            assertEquals(setOf(prepared.asset.id), loaded.lease.assets)
            loaded.lease.close()
            runtime.library.saveDraft(value.copy(form = null), 1, permit()).ok()
            runtime.library.publishDraft(value.id, 1, null, permit()).problem(ProblemCode.CONFLICT)
            runtime.library.publishDraft(value.id, 2, null, permit()).ok()
            runtime.library.draft(value.id).problem(ProblemCode.NOT_FOUND)
            assertEquals(1, runtime.media.collectGarbage().ok().removedAssets)
        } finally { runtime.close() }
    } }

    @Test fun sourceRenameAndDeleteDoNotUnpinMessageOrIndependentLease() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val prepared = media(runtime)
            runtime.library.addBoard(board(asset = prepared.asset.id), permit()).ok()
            runtime.messages.saveMessage(message(prepared.asset.id)).ok()
            val independent = prepared.lease.retain(setOf(prepared.asset.id)).ok()
            prepared.lease.close()
            prepared.lease.close()
            runtime.library.renameBoard(BoardId("board"), "new", 1, permit()).ok()
            runtime.library.deleteBoard(BoardId("board"), 2, permit()).ok()
            assertEquals(0, runtime.media.collectGarbage().ok().removedAssets)
            runtime.messages.saveMessage(StoredMessage(0, 2, MessageDraft())).ok()
            assertEquals(0, runtime.media.collectGarbage().ok().removedAssets)
            independent.close()
            assertEquals(1, runtime.media.collectGarbage().ok().removedAssets)
        } finally { runtime.close() }
    } }

    @Test fun messageCoalescesWithoutRollbackAndIsIdempotent() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val value = StoredMessage(0, 10, MessageDraft(mode = InteractionMode.DIRECT))
            runtime.messages.saveMessage(value).ok()
            runtime.messages.saveMessage(value).ok()
            runtime.messages.saveMessage(value.copy(version = 9)).problem(ProblemCode.CONFLICT)
            runtime.messages.saveMessage(value.copy(draft = MessageDraft())).problem(ProblemCode.CONFLICT)
            assertEquals(value, runtime.messages.message().ok())
        } finally { runtime.close() }
    } }

    @Test fun snapshotIsRepeatableAndExcludesMessageExclusiveMedia() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val boardMedia = media(runtime, "board")
            val formMedia = runtime.media.prepare(Buffer().writeUtf8("form"), MediaKind.AUDIO, "audio/test").ok()
            val messageMedia = media(runtime, "message")
            val archive = runtime.media.prepare(Buffer().writeUtf8("archive"), MediaKind.ARCHIVE, "application/zip").ok()
            runtime.library.addBoard(board(asset = boardMedia.asset.id), permit()).ok()
            runtime.library.saveDraft(draft(board("draftBoard"), EditorForm.Card(PageId("page"), 1, CardId("form"), recording = formMedia.asset.id)), null, permit()).ok()
            runtime.library.retainArchive(RetainedArchive(BoardId("retained"), "Unreadable", 1, archive.asset.id, RetainedReason.INVALID_ARCHIVE), permit()).ok()
            runtime.messages.saveMessage(message(messageMedia.asset.id)).ok()
            val snapshot = runtime.library.snapshot().ok()
            try {
                assertEquals(SnapshotCounts(1, 1, 1, 3, boardMedia.asset.byteCount + formMedia.asset.byteCount + archive.asset.byteCount), snapshot.counts)
                assertEquals(0, snapshot.epoch)
                assertEquals(3, snapshot.revision)
                runtime.library.renameBoard(BoardId("board"), "Changed", 1, permit()).ok()
                val first = snapshot.boards().toList()
                assertEquals(first, snapshot.boards().toList())
                assertEquals("Доска board", first.single().document.name)
                assertEquals(setOf(boardMedia.asset.id, formMedia.asset.id, archive.asset.id), snapshot.assets().toList().map { it.id }.toSet())
                assertEquals(1, snapshot.drafts().toList().size)
                assertEquals(1, snapshot.retained().toList().size)
            } finally { snapshot.close() }
            assertFailsWith<SnapshotReadException> { snapshot.boards().toList() }
            listOf(boardMedia, formMedia, messageMedia, archive).forEach { it.lease.close() }
        } finally { runtime.close() }
    } }

    @Test fun builderRequiresExactRootsIncludingFormAndRetained() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val used = media(runtime, "used")
            val extra = media(runtime, "extra")
            val builder = runtime.library.createSnapshotBuilder().ok()
            try {
                builder.addBoard(PublishedBoard(board(asset = used.asset.id), 8)).ok()
                builder.seal(12, PortableSettings()).problem(ProblemCode.INTEGRITY_FAILURE)
                builder.addAsset(used.asset).ok()
                builder.addAsset(extra.asset).ok()
                builder.seal(12, PortableSettings()).problem(ProblemCode.INTEGRITY_FAILURE)
                used.lease.close(); extra.lease.close()
                assertEquals(0, runtime.media.collectGarbage().ok().removedAssets)
            } finally { builder.close() }
            assertEquals(2, runtime.media.collectGarbage().ok().removedAssets)
        } finally { runtime.close() }
    } }

    @Test fun recoveryConflictSwapDeleteAndOldEpochCannotReviveMessage() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            runtime.library.addBoard(board("old"), permit()).ok()
            runtime.messages.saveMessage(StoredMessage(0, 8, MessageDraft(mode = InteractionMode.DIRECT))).ok()
            val builder = runtime.library.createSnapshotBuilder().ok()
            builder.addBoard(PublishedBoard(board("new"), 4)).ok()
            val reader = builder.seal(90, PortableSettings(highContrast = true)).ok()
            builder.close()
            try {
                val recovery = runtime.library.replaceLibrary(reader, 1, MessageStamp(0, 8), permit()).ok()
                assertEquals("new", runtime.library.listing.value.items.single().id.value)
                assertEquals(1, runtime.library.listing.value.epoch)
                assertEquals(StoredMessage(1, 0, MessageDraft()), runtime.messages.message().ok())
                runtime.messages.saveMessage(StoredMessage(0, 100, MessageDraft())).problem(ProblemCode.STALE_REQUEST)
                runtime.library.renameBoard(BoardId("new"), "stale", 4, permit()).problem(ProblemCode.STALE_REQUEST)
                runtime.library.replaceLibrary(reader, 2, MessageStamp(1, 0), permit(1)).problem(ProblemCode.CONFLICT)
                val swapped = runtime.library.returnToRecovery(recovery.id, 2, MessageStamp(1, 0), permit(1)).ok()
                assertNotEquals(recovery.id, swapped.id)
                assertEquals("old", runtime.library.listing.value.items.single().id.value)
                assertFalse(runtime.library.settings().ok().highContrast)
                val swappedAgain = runtime.library.returnToRecovery(swapped.id, 3, MessageStamp(2, 0), permit(2)).ok()
                assertEquals("new", runtime.library.listing.value.items.single().id.value)
                runtime.library.deleteRecovery(swappedAgain.id, permit(3)).ok()
                assertNull(runtime.library.recovery().ok())
            } finally { reader.close() }
        } finally { runtime.close() }
    } }

    @Test fun newerAndUnknownZeroSchemasRemainByteForByteUnchanged() = runTest {
        for (version in listOf(0, 3)) fresh { root ->
            val path = root / "library.sqlite3"
            BundledSQLiteDriver().open(path.toString()).use { db -> db.exec("CREATE TABLE unrelated(secret TEXT)"); db.exec("PRAGMA user_version=$version") }
            val before = fs.read(path) { readByteString() }
            StorageRuntime.open(root, fs, BundledSQLiteDriver()).problem(if (version == 3) ProblemCode.NEWER_SCHEMA else ProblemCode.INVALID_DATA)
            assertEquals(before, fs.read(path) { readByteString() })
        }
    }

    @Test fun incompleteSchemaOneIsRefusedWithoutFallback() = runTest { fresh { root ->
        val path = root / "library.sqlite3"
        BundledSQLiteDriver().open(path.toString()).use { db -> db.exec("CREATE TABLE entries(id TEXT)"); db.exec("PRAGMA user_version=1") }
        val before = fs.read(path) { readByteString() }
        StorageRuntime.open(root, fs, BundledSQLiteDriver()).problem(ProblemCode.INVALID_DATA)
        assertEquals(before, fs.read(path) { readByteString() })
    } }

    @Test fun missingAndCorruptMediaAreNotAnEmptyLibrary() = runTest {
        for (corrupt in listOf(false, true)) fresh { root ->
            var runtime = open(root)
            val id: AssetId
            try {
                val prepared = media(runtime)
                id = prepared.asset.id
                runtime.library.addBoard(board(asset = id), permit()).ok()
                prepared.lease.close()
            } finally { runtime.close() }
            val path = root / "media" / id.value.take(2) / id.value
            if (corrupt) fs.write(path) { writeUtf8("corrupted") } else fs.delete(path)
            StorageRuntime.open(root, fs, BundledSQLiteDriver()).problem(if (corrupt) ProblemCode.INTEGRITY_FAILURE else ProblemCode.MISSING_ASSET)
        }
    }

    private class FaultDriver : SQLiteDriver {
        var before: ((String) -> Unit)? = null
        var after: ((String) -> Unit)? = null
        var failClose = false
        var opens = 0
        var successfulCloses = 0
        override fun open(fileName: String): SQLiteConnection {
            opens++
            val connection = BundledSQLiteDriver().open(fileName)
            return object : SQLiteConnection by connection {
                override fun close() {
                    if (failClose) { failClose = false; throw IOException("injected close") }
                    connection.close()
                    successfulCloses++
                }
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            before?.invoke(sql)
                            val result = statement.step()
                            after?.invoke(sql)
                            return result
                        }
                    }
                }
            }
        }
        fun failOnce(prefix: String, afterCommit: Boolean = false, cancel: Boolean = false) {
            val hook: (String) -> Unit = { sql -> if (sql.startsWith(prefix)) {
                before = null; after = null
                if (cancel) throw CancellationException("injected") else throw IOException("injected")
            } }
            if (afterCommit) after = hook else before = hook
        }
        fun unconfirmedAfterCommit(cancel: Boolean = false) {
            after = { sql -> if (sql == "COMMIT") {
                after = null
                before = { query -> if (query == "PRAGMA query_only=ON") throw IOException("injected observer") }
                if (cancel) throw CancellationException("injected") else throw IOException("injected after commit")
            } }
        }
    }

    @Test fun publicationFaultsBeforeCommitDoNotLeakNewRefs() = runTest {
        for (point in listOf("BEGIN IMMEDIATE", "INSERT OR REPLACE INTO entries", "INSERT INTO asset_refs", "COMMIT")) fresh { root ->
            val driver = FaultDriver()
            val runtime = open(root, driver = driver)
            try {
                val prepared = media(runtime)
                driver.failOnce(point)
                runtime.library.addBoard(board(asset = prepared.asset.id), permit()).problem(ProblemCode.IO_FAILURE)
                assertTrue(runtime.library.listing.value.items.isEmpty())
                assertEquals(0, runtime.library.listing.value.revision)
                prepared.lease.close()
                assertEquals(1, runtime.media.collectGarbage().ok().removedAssets)
            } finally { runtime.close() }
        }
    }

    @Test fun errorOrCancellationReportedAfterCommitStillCompletesPublication() = runTest {
        for (cancel in listOf(false, true)) fresh { root ->
            val driver = FaultDriver()
            val runtime = open(root, driver = driver)
            try {
                driver.failOnce("COMMIT", afterCommit = true, cancel = cancel)
                runtime.library.addBoard(board(), permit()).ok()
                assertEquals(1, runtime.library.listing.value.revision)
                assertEquals(1, runtime.library.listing.value.items.size)
            } finally { runtime.close() }
        }
    }

    @Test fun secondGuardRevocationOrCancellationRollsBackPayloadAndRoots() = runTest {
        for (cancel in listOf(false, true)) fresh { root ->
            val runtime = open(root)
            try {
                val prepared = media(runtime)
                var calls = 0
                val guarded = WritePermit(0) {
                    if (++calls == 2) {
                        if (cancel) throw CancellationException("injected")
                        OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
                    } else OperationResult.Completed(Unit)
                }
                if (cancel) assertFailsWith<CancellationException> { runtime.library.addBoard(board(asset = prepared.asset.id), guarded) }
                else runtime.library.addBoard(board(asset = prepared.asset.id), guarded).problem(ProblemCode.UNAUTHORIZED)
                assertEquals(2, calls)
                assertTrue(runtime.library.listing.value.items.isEmpty())
                prepared.lease.close()
                assertEquals(1, runtime.media.collectGarbage().ok().removedAssets)
            } finally { runtime.close() }
        }
    }

    @Test fun failedRecoveryOrSwitchPreservesLibraryAndMessage() = runTest {
        for (point in listOf("INSERT INTO recovery SELECT", "INSERT INTO recovery_entries", "DELETE FROM entries", "INSERT INTO entries SELECT", "UPDATE message", "COMMIT")) fresh { root ->
            val driver = FaultDriver()
            val runtime = open(root, driver = driver)
            try {
                runtime.library.addBoard(board("old"), permit()).ok()
                val message = StoredMessage(0, 4, MessageDraft(mode = InteractionMode.DIRECT))
                runtime.messages.saveMessage(message).ok()
                val builder = runtime.library.createSnapshotBuilder().ok()
                builder.addBoard(PublishedBoard(board("new"), 1)).ok()
                val reader = builder.seal(1, PortableSettings()).ok()
                try {
                    // Staging also commits; arm only when the live recovery transaction starts.
                    driver.before = { sql -> if (sql.startsWith("INSERT INTO recovery SELECT")) driver.failOnce(point) }
                    if (point == "INSERT INTO recovery SELECT") driver.failOnce(point)
                    runtime.library.replaceLibrary(reader, 1, message.stamp, permit()).problem(ProblemCode.IO_FAILURE)
                    assertEquals("old", runtime.library.listing.value.items.single().id.value)
                    assertEquals(message, runtime.messages.message().ok())
                    assertNull(runtime.library.recovery().ok())
                } finally { reader.close(); builder.close() }
            } finally { runtime.close() }
        }
    }

    @Test fun foreignOrClosedLeaseCannotReadOwnedMedia() = runTest { fresh { left -> fresh { right ->
        val first = open(left)
        val second = open(right)
        try {
            val one = media(first)
            val two = media(second)
            second.media.file(two.asset.id, one.lease).problem(ProblemCode.STALE_REQUEST)
            first.media.file(one.asset.id, one.lease).ok()
            one.lease.close()
            first.media.source(one.asset.id, one.lease).problem(ProblemCode.STALE_REQUEST)
            one.lease.retain(setOf(one.asset.id)).problem(ProblemCode.STALE_REQUEST)
            two.lease.close()
        } finally { first.close(); second.close() }
    } } }

    @Test fun composedMessageAndAuthoringLeasesReadOnlyLiveOwnedLeaves() = runTest { fresh { root ->
        val runtime = open(root)
        val leases = mutableListOf<MediaLease>()
        try {
            val one = media(runtime, "one").also { leases += it.lease }
            val two = media(runtime, "two").also { leases += it.lease }
            val ids = setOf(one.asset.id, two.asset.id)
            val composite = MessageLease.retainFrom(ids, listOf(one.lease, two.lease)).ok()
            val authoring = AuthoringLease(composite) { it() }.also { leases += it }
            val retained = authoring.retain(setOf(one.asset.id)).ok().also { leases += it }
            one.lease.close(); two.lease.close()
            assertEquals(0, runtime.media.collectGarbage().ok().removedAssets)
            for ((asset, bytes) in listOf(one.asset to "one", two.asset to "two")) {
                runtime.media.file(asset.id, authoring).ok()
                assertEquals(bytes, runtime.media.source(asset.id, authoring).ok().buffer().use { it.readUtf8() })
            }
            val forged = object : MediaLease {
                override val assets = ids
                override fun retain(assets: Set<AssetId>): OperationResult<MediaLease> = error("A read must not reacquire proofs")
                override fun close() = Unit
            }
            runtime.media.file(one.asset.id, forged).problem(ProblemCode.STALE_REQUEST)
            val foreign = AuthoringLease(MessageLease(listOf(StoragePins().pin(ids)))) { it() }.also { leases += it }
            runtime.media.source(one.asset.id, foreign).problem(ProblemCode.STALE_REQUEST)
            authoring.close()
            runtime.media.file(one.asset.id, authoring).problem(ProblemCode.STALE_REQUEST)
            runtime.media.file(two.asset.id, retained).problem(ProblemCode.STALE_REQUEST)
            assertEquals("one", runtime.media.source(one.asset.id, retained).ok().buffer().use { it.readUtf8() })
            assertEquals(1, runtime.media.collectGarbage().ok().removedAssets)
            retained.close()
            runtime.media.source(one.asset.id, retained).problem(ProblemCode.STALE_REQUEST)
            assertEquals(1, runtime.media.collectGarbage().ok().removedAssets)
        } finally { leases.forEach { it.close() }; runtime.close() }
    } }

    @Test fun wrapperCleanupFailureStillRevokesReadsOfItsLiveLeaf() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val prepared = media(runtime)
            try {
                for (message in listOf(false, true)) {
                    val leaf = prepared.lease.retain(setOf(prepared.asset.id)).ok()
                    try {
                        val wrapper = if (message) MessageLease(listOf(object : MediaLease {
                            override val assets = emptySet<AssetId>()
                            override fun retain(assets: Set<AssetId>): OperationResult<MediaLease> = error("unused")
                            override fun close(): Unit = error("cleanup failed")
                        }, leaf)) else AuthoringLease(leaf) { error("cleanup failed") }
                        assertFailsWith<IllegalStateException> { wrapper.close() }
                        runtime.media.file(prepared.asset.id, wrapper).problem(ProblemCode.STALE_REQUEST)
                        runtime.media.source(prepared.asset.id, wrapper).problem(ProblemCode.STALE_REQUEST)
                        runtime.media.file(prepared.asset.id, leaf).ok()
                    } finally { leaf.close() }
                }
            } finally { prepared.lease.close() }
        } finally { runtime.close() }
    } }

    @Test fun closeRejectsNewOperationsAndConsumesRejectedPrepareSource() = runTest { fresh { root ->
        val runtime = open(root)
        runtime.close()
        runtime.close()
        runtime.library.settings().problem(ProblemCode.STALE_REQUEST)
        var closes = 0
        val source = object : ForwardingSource(Buffer().writeUtf8("test")) { override fun close() { closes++; super.close() } }
        runtime.media.prepare(source, MediaKind.IMAGE, "image/test").problem(ProblemCode.STALE_REQUEST)
        assertEquals(1, closes)
    } }

    private class FaultFileSystem : ForwardingFileSystem(FileSystem.SYSTEM) {
        var moveFault: Boolean? = null
        var deleteFault: Boolean? = null
        override fun atomicMove(source: Path, target: Path) {
            val after = moveFault
            moveFault = null
            if (after == false) throw IOException("injected")
            super.atomicMove(source, target)
            if (after == true) throw IOException("injected")
        }
        override fun delete(path: Path, mustExist: Boolean) {
            val after = if (path.parent?.parent?.name == "media") deleteFault else null
            if (after != null) deleteFault = null
            if (after == false) throw IOException("injected")
            super.delete(path, mustExist)
            if (after == true) throw IOException("injected")
        }
    }

    @Test fun fileFinalizationFailuresCloseInputAndNeverPublishOrLeakRefs() = runTest {
        for (after in listOf(false, true)) fresh { root ->
            val fileSystem = FaultFileSystem()
            val runtime = open(root, fileSystem)
            try {
                fileSystem.moveFault = after
                var closes = 0
                val source = object : ForwardingSource(Buffer().writeUtf8("faulted")) { override fun close() { closes++; super.close() } }
                runtime.media.prepare(source, MediaKind.IMAGE, "image/test").problem(ProblemCode.IO_FAILURE)
                assertEquals(1, closes)
                assertEquals(0, runtime.db.number("SELECT count(*) FROM assets"))
                assertEquals(0, runtime.db.number("SELECT count(*) FROM asset_refs"))
                assertEquals(0, fs.list(root / "staging").size)
                val files = fs.list(root / "media").flatMap { fs.list(it) }
                assertTrue(files.isEmpty())
            } finally { runtime.close() }
        }
    }

    @Test fun sourceCloseFailureDoesNotLeakPreparedLeaseOrAsset() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val source = object : ForwardingSource(Buffer().writeUtf8("faulted")) { override fun close() { super.close(); throw IOException("injected") } }
            runtime.media.prepare(source, MediaKind.IMAGE, "image/test").problem(ProblemCode.IO_FAILURE)
            assertEquals(0, runtime.db.number("SELECT count(*) FROM assets"))
            assertEquals(0, fs.list(root / "staging").size)
        } finally { runtime.close() }
    } }

    @Test fun gcFileFaultsHaveHonestCountersAndDoNotFailPriorPublication() = runTest {
        for (after in listOf(false, true)) fresh { root ->
            val fileSystem = FaultFileSystem()
            val runtime = open(root, fileSystem)
            try {
                val orphan = media(runtime)
                orphan.lease.close()
                runtime.library.addBoard(board(), permit()).ok()
                fileSystem.deleteFault = after
                val result = runtime.media.collectGarbage().ok()
                if (after) assertEquals(GarbageCollectionReport(1, orphan.asset.byteCount, 0), result)
                else {
                    assertEquals(GarbageCollectionReport(0, 0, 1), result)
                    assertEquals(1, runtime.media.collectGarbage().ok().removedAssets)
                }
                assertEquals(1, runtime.library.listing.value.items.size)
                assertEquals(1, runtime.library.listing.value.revision)
            } finally { runtime.close() }
        }
    }

    @Test fun gcMetadataFailureRetriesAfterReopenUsingOwnedIntent() = runTest { fresh { root ->
        val driver = FaultDriver()
        var runtime = open(root, driver = driver)
        val bytes: Long
        try {
            val orphan = media(runtime)
            bytes = orphan.asset.byteCount
            orphan.lease.close()
            driver.failOnce("DELETE FROM assets")
            assertEquals(GarbageCollectionReport(0, 0, 1), runtime.media.collectGarbage().ok())
        } finally { runtime.close() }
        runtime = open(root)
        try {
            assertEquals(GarbageCollectionReport(1, bytes, 0), runtime.media.collectGarbage().ok())
            assertEquals(0, runtime.db.number("SELECT count(*) FROM assets"))
        } finally { runtime.close() }
    } }

    @Test fun gcDoesNotHideMissingFileWithoutOwnedDeletionIntent() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val orphan = media(runtime)
            orphan.lease.close()
            fs.delete(runtime.mediaStore.path(orphan.asset.id))
            assertEquals(GarbageCollectionReport(0, 0, 1), runtime.media.collectGarbage().ok())
            assertEquals(1, runtime.db.number("SELECT count(*) FROM assets"))
        } finally { runtime.close() }
    } }

    @Test fun acquirePrepareAndGcShareOneLeasePublicationBoundary() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val prepared = media(runtime)
            coroutineScope {
                val readers = (1..20).map { async(Dispatchers.Default) {
                    val lease = runtime.media.acquire(setOf(prepared.asset.id)).ok()
                    val retained = lease.retain(setOf(prepared.asset.id)).ok()
                    lease.close()
                    runtime.media.source(prepared.asset.id, retained).ok().use { assertEquals("immutable", it.buffer().readUtf8()) }
                    retained.close()
                } }
                val collector = async(Dispatchers.Default) { repeat(20) { assertEquals(0, runtime.media.collectGarbage().ok().removedAssets) } }
                readers.awaitAll(); collector.await()
            }
            prepared.lease.close()
            assertEquals(1, runtime.media.collectGarbage().ok().removedAssets)
        } finally { runtime.close() }
    } }

    @Test fun restoreFaultAfterDurableCommitReturnsCompletedAndNewEpoch() = runTest { fresh { root ->
        val driver = FaultDriver()
        val runtime = open(root, driver = driver)
        try {
            runtime.library.addBoard(board("old"), permit()).ok()
            val builder = runtime.library.createSnapshotBuilder().ok()
            builder.addBoard(PublishedBoard(board("new"), 1)).ok()
            val reader = builder.seal(1, PortableSettings()).ok()
            try {
                driver.before = { sql -> if (sql.startsWith("INSERT INTO recovery SELECT")) { driver.before = null; driver.failOnce("COMMIT", afterCommit = true, cancel = true) } }
                val recovery = runtime.library.replaceLibrary(reader, 1, MessageStamp(0, 0), permit()).ok()
                assertEquals(1, runtime.library.listing.value.epoch)
                assertEquals("new", runtime.library.listing.value.items.single().id.value)
                driver.failOnce("COMMIT", afterCommit = true)
                runtime.library.returnToRecovery(recovery.id, 2, MessageStamp(1, 0), permit(1)).ok()
                assertEquals("old", runtime.library.listing.value.items.single().id.value)
                assertEquals(2, runtime.library.listing.value.epoch)
            } finally { reader.close(); builder.close() }
        } finally { runtime.close() }
    } }

    @Test fun unknownViewOnlySchemaZeroAndLostDatabaseAreNotFreshLibraries() = runTest {
        fresh { root ->
            val path = root / "library.sqlite3"
            BundledSQLiteDriver().open(path.toString()).use { it.exec("CREATE VIEW unrelated AS SELECT 1") }
            val bytes = fs.read(path) { readByteString() }
            StorageRuntime.open(root, fs, BundledSQLiteDriver()).problem(ProblemCode.INVALID_DATA)
            assertEquals(bytes, fs.read(path) { readByteString() })
        }
        fresh { root ->
            fs.createDirectory(root / "media")
            StorageRuntime.open(root, fs, BundledSQLiteDriver()).problem(ProblemCode.INVALID_DATA)
            assertFalse(fs.exists(root / "library.sqlite3"))
        }
    }

    @Test fun corruptRefsAndMissingInternalSchemaMarkerAreRejectedOnOpen() = runTest {
        for (refs in listOf(false, true)) fresh { root ->
            val runtime = open(root)
            try {
                val prepared = media(runtime)
                runtime.library.addBoard(board(asset = prepared.asset.id), permit()).ok()
                prepared.lease.close()
                if (refs) runtime.db.exec("DELETE FROM asset_refs")
                else runtime.db.exec("UPDATE entries SET payload_json=?", "{\"value\":${storageJson.encodeToString(BoardDocument.serializer(), board(asset = prepared.asset.id))}}")
            } finally { runtime.close() }
            StorageRuntime.open(root, fs, BundledSQLiteDriver()).problem(if (refs) ProblemCode.INTEGRITY_FAILURE else ProblemCode.INVALID_DATA)
        }
    }

    @Test fun failedCommitAndRollbackMustNotReportUncommittedStateAsCompleted() = runTest { fresh { root ->
        val driver = FaultDriver()
        val runtime = open(root, driver = driver)
        try {
            driver.before = { sql ->
                if (sql == "COMMIT") throw IOException("injected")
                if (sql == "ROLLBACK") { driver.before = null; throw IOException("injected") }
            }
            val result = runtime.library.addBoard(board(), permit())
            BundledSQLiteDriver().open((root / "library.sqlite3").toString()).use { observer ->
                assertEquals(0, observer.number("SELECT revision FROM library_meta WHERE singleton=1"))
                assertEquals(0, observer.number("SELECT count(*) FROM entries"))
            }
            // Safety regression: the writer's own connection still sees the uncommitted revision.
            result.problem(ProblemCode.IO_FAILURE)
        } finally {
            driver.before = null
            runCatching { runtime.db.exec("ROLLBACK") }
            runtime.close()
        }
    } }

    @Test fun durableCommitWithUnavailableVerificationMustNotReportFailed() = runTest { fresh { root ->
        val driver = FaultDriver()
        val runtime = open(root, driver = driver)
        try {
            driver.after = { sql -> if (sql == "COMMIT") {
                driver.after = null
                driver.before = { query -> if (query == "PRAGMA query_only=ON") throw IOException("injected") }
                throw IOException("injected after durable commit")
            } }
            val result = runtime.library.addBoard(board(), permit())
            BundledSQLiteDriver().open((root / "library.sqlite3").toString()).use { observer ->
                assertEquals(1, observer.number("SELECT revision FROM library_meta WHERE singleton=1"))
                assertEquals(1, observer.number("SELECT count(*) FROM entries"))
            }
            assertEquals(OperationResult.UnconfirmedCommit, result)
            assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN, runtime.library.listing.value.refreshProblem?.code)
        } finally {
            driver.before = null
            driver.after = null
            runtime.close()
        }
    } }

    @Test fun quarantineBlocksWritesGcAndReadsWithoutLosingPublishedAssetOrReplaying() = runTest { fresh { root ->
        val driver = FaultDriver()
        var runtime = open(root, driver = driver)
        val prepared = media(runtime)
        try {
            driver.unconfirmedAfterCommit()
            assertEquals(OperationResult.UnconfirmedCommit, runtime.library.addBoard(board(asset = prepared.asset.id), permit()))
            assertEquals(OperationResult.UnconfirmedCommit, runtime.library.addBoard(board("retry"), permit()))
            assertEquals(OperationResult.UnconfirmedCommit, runtime.messages.saveMessage(StoredMessage(0, 1, MessageDraft())))
            assertEquals(OperationResult.UnconfirmedCommit, runtime.media.collectGarbage())
            runtime.library.board(BoardId("board")).problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
            runtime.library.settings().problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
            runtime.messages.message().problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
            runtime.media.metadata(prepared.asset.id).problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
            assertTrue(fs.exists(runtime.mediaStore.path(prepared.asset.id)))
            val retained = prepared.lease.retain(setOf(prepared.asset.id)).ok()
            runtime.media.source(prepared.asset.id, retained).ok().use { assertEquals("immutable", it.buffer().readUtf8()) }
            retained.close()
        } finally { prepared.lease.close(); runtime.close() }
        runtime = open(root)
        try {
            assertEquals(listOf(BoardId("board")), runtime.library.listing.value.items.map { it.id })
            assertEquals(1, runtime.library.listing.value.revision)
            assertEquals(0, runtime.media.collectGarbage().ok().removedAssets)
            val loaded = runtime.library.board(BoardId("board")).ok()
            assertEquals(setOf(prepared.asset.id), loaded.lease.assets)
            loaded.lease.close()
        } finally { runtime.close() }
    } }

    @Test fun uncertainRollbackReopensAuthoritativeOldStateWithoutCommandReplay() = runTest { fresh { root ->
        val driver = FaultDriver()
        var runtime = open(root, driver = driver)
        try {
            driver.before = { sql ->
                if (sql == "COMMIT") throw IOException("injected commit")
                if (sql == "ROLLBACK") {
                    driver.before = { query -> if (query == "PRAGMA query_only=ON") throw IOException("injected observer") }
                    throw CancellationException("injected rollback")
                }
            }
            assertEquals(OperationResult.UnconfirmedCommit, runtime.library.addBoard(board(), permit()))
            assertEquals(OperationResult.UnconfirmedCommit, runtime.media.collectGarbage())
        } finally { runtime.close() }
        runtime = open(root)
        try {
            assertEquals(0, runtime.library.listing.value.revision)
            assertTrue(runtime.library.listing.value.items.isEmpty())
        } finally { runtime.close() }
    } }

    @Test fun cancellationDuringUncertainOutcomeIsNotCancelledOrFailed() = runTest { fresh { root ->
        val driver = FaultDriver()
        val runtime = open(root, driver = driver)
        try {
            driver.unconfirmedAfterCommit(cancel = true)
            assertEquals(OperationResult.UnconfirmedCommit, runtime.library.addBoard(board(), permit()))
            assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN, runtime.library.listing.value.refreshProblem?.code)
            BundledSQLiteDriver().open((root / "library.sqlite3").toString()).use { observer ->
                assertEquals(1, observer.number("SELECT count(*) FROM entries"))
            }
        } finally { runtime.close() }
    } }

    @Test fun failedWriterClosePreventsObserverAndQuarantinesUntilExplicitReopen() = runTest { fresh { root ->
        val driver = FaultDriver()
        var runtime = open(root, driver = driver)
        try {
            val opens = driver.opens
            driver.failOnce("COMMIT", afterCommit = true)
            driver.failClose = true
            assertEquals(OperationResult.UnconfirmedCommit, runtime.library.addBoard(board(), permit()))
            assertEquals(opens, driver.opens)
            assertEquals(OperationResult.UnconfirmedCommit, runtime.media.collectGarbage())
        } finally { runtime.close() }
        runtime = open(root)
        try { assertEquals(1, runtime.library.listing.value.items.size) } finally { runtime.close() }
    } }

    @Test fun exactRefMismatchCannotConfirmCommitByRevisionAlone() = runTest { fresh { root ->
        val driver = FaultDriver()
        val runtime = open(root, driver = driver)
        val prepared = media(runtime)
        try {
            driver.after = { sql -> if (sql == "COMMIT") {
                driver.after = null
                BundledSQLiteDriver().open((root / "library.sqlite3").toString()).use { it.exec("DELETE FROM asset_refs") }
                throw IOException("injected after damaged commit")
            } }
            assertEquals(OperationResult.UnconfirmedCommit, runtime.library.addBoard(board(asset = prepared.asset.id), permit()))
            prepared.lease.close()
            assertEquals(OperationResult.UnconfirmedCommit, runtime.media.collectGarbage())
            assertTrue(fs.exists(runtime.mediaStore.path(prepared.asset.id)))
        } finally { prepared.lease.close(); runtime.close() }
        StorageRuntime.open(root, fs, BundledSQLiteDriver()).problem(ProblemCode.INTEGRITY_FAILURE)
    } }

    @Test fun observedCommitCompletesEvenIfListingRefreshFails() = runTest { fresh { root ->
        val driver = FaultDriver()
        val runtime = open(root, driver = driver)
        try {
            driver.after = { sql -> if (sql == "COMMIT") {
                driver.after = null
                driver.before = { query -> if (query.startsWith("SELECT id,name,kind,revision,payload_json,order_index")) throw IOException("injected refresh") }
            } }
            val result = runtime.library.addBoard(board(), permit()).ok()
            assertEquals(1, result.revision)
            assertEquals(0, runtime.library.listing.value.epoch)
            assertEquals(1, runtime.library.listing.value.revision)
            assertEquals(ProblemCode.IO_FAILURE, runtime.library.listing.value.refreshProblem?.code)
            assertFalse(runtime.quarantined)
            driver.before = null
            runtime.library.renameBoard(BoardId("board"), "updated", 1, permit()).ok()
            assertEquals(2, runtime.library.listing.value.revision)
        } finally { runtime.close() }
    } }

    @Test fun closeFinishesAcceptedCopyBeforeClosingDatabase() = runTest { fresh { root ->
        val runtime = open(root)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = object : ForwardingSource(Buffer().writeUtf8("accepted")) {
            var first = true
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (first) { first = false; entered.complete(Unit); runBlocking { release.await() } }
                return super.read(sink, byteCount)
            }
        }
        val copy = async(Dispatchers.Default) { runtime.media.prepare(source, MediaKind.IMAGE, "image/test") }
        entered.await()
        val closing = async(start = CoroutineStart.UNDISPATCHED) { runtime.close() }
        try {
            assertFalse(closing.isCompleted)
            release.complete(Unit)
            copy.await().ok().lease.close()
            closing.await()
        } finally { release.complete(Unit); runtime.close() }
        val reopened = open(root)
        try { assertEquals(1, reopened.media.collectGarbage().ok().removedAssets) } finally { reopened.close() }
    } }

    @Test fun twoDraftWritersAndBoardConflictPreserveEditorWork() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val document = board()
            runtime.library.addBoard(document, permit()).ok()
            val value = EditorDraft(DraftId("draft"), document.id, 1, document)
            runtime.library.saveDraft(value, null, permit()).ok()
            val results = coroutineScope { listOf("left", "right").map { name -> async(Dispatchers.Default) {
                runtime.library.saveDraft(value.copy(document = document.copy(name = name)), 1, permit())
            } }.awaitAll() }
            assertEquals(1, results.count { it is OperationResult.Completed })
            assertEquals(1, results.count { it is OperationResult.Failed && it.problem.code == ProblemCode.CONFLICT })
            runtime.library.renameBoard(document.id, "source changed", 1, permit()).ok()
            runtime.library.publishDraft(value.id, 2, 1, permit()).problem(ProblemCode.CONFLICT)
            val saved = runtime.library.draft(value.id).ok()
            assertEquals(2, saved.draft.version)
            saved.lease.close()
            runtime.library.deleteBoard(document.id, 2, permit()).ok()
            val preserved = runtime.library.draft(value.id).ok()
            assertEquals(2, preserved.draft.version)
            preserved.lease.close()
        } finally { runtime.close() }
    } }

    @Test fun snapshotCorruptionFailsInsteadOfSilentlyEndingStream() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            runtime.library.addBoard(board(), permit()).ok()
            val reader = runtime.library.snapshot().ok()
            try {
                val stage = fs.list(root / "staging").single { it.name.startsWith("snapshot-") }
                fs.write(stage / "board-0.json") { writeUtf8("{broken}") }
                val error = assertFailsWith<SnapshotReadException> { reader.boards().toList() }
                assertEquals(ProblemCode.INVALID_DATA, error.problem.code)
            } finally { reader.close() }
        } finally { runtime.close() }
    } }

    @Test fun actualMediaHashingNeverRunsUnderWriterLock() = runTest { fresh { root ->
        var runtime: StorageRuntime? = null
        val fileSystem = object : ForwardingFileSystem(fs) {
            override fun source(file: Path): Source {
                if (file.parent?.parent?.name == "media") assertFalse(runtime?.writer?.isLocked ?: false)
                return super.source(file)
            }
        }
        val active = open(root, fileSystem)
        runtime = active
        try {
            val prepared = media(active)
            val duplicate = media(active)
            duplicate.lease.close()
            active.library.addBoard(board(asset = prepared.asset.id), permit()).ok()
            active.library.renameBoard(BoardId("board"), "rename", 1, permit()).ok()
            active.messages.saveMessage(message(prepared.asset.id)).ok()
            active.messages.message().ok()
            active.library.board(BoardId("board")).ok().lease.close()
            active.media.acquire(setOf(prepared.asset.id)).ok().close()
            active.library.snapshot().ok().close()
            active.library.deleteBoard(BoardId("board"), 2, permit()).ok()
            active.messages.saveMessage(StoredMessage(0, 2, MessageDraft())).ok()
            prepared.lease.close()
            assertEquals(1, active.media.collectGarbage().ok().removedAssets)
        } finally { active.close() }
    } }

    @Test fun uncertainPrepareRollbackLeavesBytesUntilAuthoritativeReopenGc() = runTest { fresh { root ->
        val driver = FaultDriver()
        var runtime = open(root, driver = driver)
        try {
            driver.before = { sql ->
                if (sql == "COMMIT") throw IOException("injected commit")
                if (sql == "ROLLBACK") {
                    driver.before = { query -> if (query == "PRAGMA query_only=ON") throw IOException("injected observer") }
                    throw IOException("injected rollback")
                }
            }
            assertEquals(OperationResult.UnconfirmedCommit, runtime.media.prepare(Buffer().writeUtf8("orphan"), MediaKind.IMAGE, "image/test"))
            assertEquals(1, fs.list(root / "media").sumOf { fs.list(it).size })
            assertEquals(OperationResult.UnconfirmedCommit, runtime.media.collectGarbage())
        } finally { runtime.close() }
        runtime = open(root)
        try {
            assertEquals(0, runtime.db.number("SELECT count(*) FROM assets"))
            assertEquals(GarbageCollectionReport(1, 6, 0), runtime.media.collectGarbage().ok())
        } finally { runtime.close() }
    } }

    @Test fun uncertainGcOutcomeIsNotAnOrdinaryFailedAssetReport() = runTest { fresh { root ->
        val driver = FaultDriver()
        var runtime = open(root, driver = driver)
        try {
            media(runtime).lease.close()
            driver.unconfirmedAfterCommit()
            assertEquals(OperationResult.UnconfirmedCommit, runtime.media.collectGarbage())
            assertEquals(OperationResult.UnconfirmedCommit, runtime.library.saveSettings(PortableSettings(highContrast = true), 0, permit()))
        } finally { runtime.close() }
        runtime = open(root)
        try {
            assertEquals(0, runtime.db.number("SELECT count(*) FROM assets"))
            assertTrue(fs.list(root / "staging").isEmpty())
            assertEquals(GarbageCollectionReport(0, 0, 0), runtime.media.collectGarbage().ok())
        } finally { runtime.close() }
    } }

    @Test fun recoverySwapPreservesOverlappingIdsOrderDraftsAndExactMediaRoots() = runTest { fresh { root ->
        val runtime = open(root)
        val old = media(runtime, "old-media")
        val new = media(runtime, "new-media")
        val archive = runtime.media.prepare(Buffer().writeUtf8("old-archive"), MediaKind.ARCHIVE, "application/zip").ok()
        try {
            runtime.library.addBoard(board("a", old.asset.id), permit()).ok()
            runtime.library.retainArchive(RetainedArchive(BoardId("b"), "retained", 1, archive.asset.id, RetainedReason.INVALID_ARCHIVE), permit()).ok()
            runtime.library.addBoard(board("c", old.asset.id), permit()).ok()
            val form = EditorForm.Card(PageId("page"), 1, CardId("form"), image = old.asset.id)
            runtime.library.saveDraft(draft(board("draft-source"), form), null, permit()).ok()
            val builder = runtime.library.createSnapshotBuilder().ok()
            builder.addBoard(PublishedBoard(board("c", new.asset.id), 8)).ok()
            builder.addBoard(PublishedBoard(board("a", new.asset.id), 6)).ok()
            builder.addDraft(StoredDraft(12, draft(board("draft-source"), form.copy(image = new.asset.id)))).ok()
            builder.addAsset(new.asset).ok()
            val reader = builder.seal(200, PortableSettings(highContrast = true)).ok()
            builder.close()
            val recovery = try { runtime.library.replaceLibrary(reader, 4, MessageStamp(0, 0), permit()).ok() } finally { reader.close() }
            old.lease.close(); new.lease.close(); archive.lease.close()
            assertEquals(0, runtime.media.collectGarbage().ok().removedAssets)
            assertEquals(listOf("c", "a"), runtime.library.listing.value.items.map { it.id.value })
            val replacement = runtime.library.returnToRecovery(recovery.id, 5, MessageStamp(1, 0), permit(1)).ok()
            assertEquals(listOf("a", "b", "c"), runtime.library.listing.value.items.map { it.id.value })
            val saved = runtime.library.draft(DraftId("draft")).ok()
            assertEquals(old.asset.id, (saved.draft.draft.form as EditorForm.Card).image)
            saved.lease.close()
            runtime.mediaStore.validatePersistentRoots()
            runtime.library.deleteRecovery(replacement.id, permit(2)).ok()
            assertEquals(GarbageCollectionReport(1, new.asset.byteCount, 0), runtime.media.collectGarbage().ok())
            runtime.mediaStore.validatePersistentRoots()
        } finally { old.lease.close(); new.lease.close(); archive.lease.close(); runtime.close() }
    } }

    @Test fun snapshotBuilderMetadataMismatchAndMissingAssetsNeverSeal() = runTest { fresh { root ->
        val runtime = open(root)
        val prepared = media(runtime)
        try {
            val builder = runtime.library.createSnapshotBuilder().ok()
            try {
                builder.addBoard(PublishedBoard(board(asset = prepared.asset.id), 1)).ok()
                builder.addAsset(prepared.asset.copy(byteCount = prepared.asset.byteCount + 1)).problem(ProblemCode.INTEGRITY_FAILURE)
                builder.seal(1, PortableSettings()).problem(ProblemCode.INTEGRITY_FAILURE)
                fs.delete(runtime.mediaStore.path(prepared.asset.id))
                builder.addAsset(prepared.asset).problem(ProblemCode.MISSING_ASSET)
            } finally { builder.close() }
        } finally { prepared.lease.close(); runtime.close() }
    } }

    @Test fun immutableMediaTypeConflictAndCorruptOrphanAreNotOverwrittenOrDeleted() = runTest { fresh { root ->
        val runtime = open(root)
        val prepared = media(runtime)
        try {
            runtime.media.prepare(Buffer().writeUtf8("immutable"), MediaKind.AUDIO, "audio/test").problem(ProblemCode.CONFLICT)
            assertEquals(prepared.asset, runtime.media.metadata(prepared.asset.id).ok())
            val id = AssetId("0".repeat(64))
            val path = runtime.mediaStore.path(id)
            fs.createDirectories(path.parent!!)
            fs.write(path) { writeUtf8("does not match hash") }
            assertEquals(GarbageCollectionReport(0, 0, 1), runtime.media.collectGarbage().ok())
            assertTrue(fs.exists(path))
        } finally { prepared.lease.close(); runtime.close() }
    } }

    @Test fun everyLibraryWriterHonorsTheSecondGuardWithoutChangingRevision() = runTest {
        val actions: List<suspend (StorageRuntime, WritePermit, AssetId) -> OperationResult<*>> = listOf(
            { r, p, _ -> r.library.saveDraft(EditorDraft(DraftId("draft"), BoardId("board"), 1, board()), 1, p) },
            { r, p, _ -> r.library.deleteDraft(DraftId("draft"), 1, p) },
            { r, p, _ -> r.library.publishDraft(DraftId("draft"), 1, 1, p) },
            { r, p, _ -> r.library.addBoard(board("new"), p) },
            { r, p, _ -> r.library.replaceBoard(board().copy(name = "replace"), 1, p) },
            { r, p, _ -> r.library.renameBoard(BoardId("board"), "rename", 1, p) },
            { r, p, _ -> r.library.deleteBoard(BoardId("board"), 1, p) },
            { r, p, a -> r.library.retainArchive(RetainedArchive(BoardId("retained"), "retained", 1, a, RetainedReason.INVALID_ARCHIVE), p) },
            { r, p, _ -> r.library.saveSettings(PortableSettings(highContrast = true), 2, p) },
        )
        for (action in actions) fresh { root ->
            val runtime = open(root)
            val archive = runtime.media.prepare(Buffer().writeUtf8("archive"), MediaKind.ARCHIVE, "application/zip").ok()
            try {
                runtime.library.addBoard(board(), permit()).ok()
                runtime.library.saveDraft(EditorDraft(DraftId("draft"), BoardId("board"), 1, board()), null, permit()).ok()
                var checks = 0
                val guarded = WritePermit(0) { if (++checks == 2) OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED)) else OperationResult.Completed(Unit) }
                action(runtime, guarded, archive.asset.id).problem(ProblemCode.UNAUTHORIZED)
                assertEquals(2, checks)
                assertEquals(2, runtime.library.listing.value.revision)
                assertEquals(1, runtime.library.listing.value.items.size)
                assertEquals(1, runtime.library.drafts().ok().single().version)
                runtime.mediaStore.validatePersistentRoots()
            } finally { archive.lease.close(); runtime.close() }
        }
    }

    @Test fun observerCleanupFailureCannotRevokeExactDurableCommitProof() = runTest { fresh { root ->
        val driver = FaultDriver()
        val runtime = open(root, driver = driver)
        try {
            driver.after = { sql -> if (sql == "COMMIT") {
                driver.after = null
                driver.before = { query -> if (query == "PRAGMA query_only=ON") {
                    driver.before = null
                    driver.failClose = true
                } }
                throw IOException("injected after commit")
            } }
            runtime.library.addBoard(board(), permit()).ok()
            assertFalse(runtime.quarantined)
            assertEquals(1, runtime.library.listing.value.revision)
        } finally { runtime.close() }
    } }

    @Test fun alteredSchemaOneConstraintsAreRefusedWithoutRepair() = runTest { fresh { root ->
        val path = root / "library.sqlite3"
        BundledSQLiteDriver().open(path.toString()).use { db ->
            for (definition in schemaStatements) db.exec(if (definition.startsWith("CREATE TABLE assets")) definition.replace("CHECK(byte_count>=0)", "CHECK(byte_count>=-1)") else definition)
            db.exec("PRAGMA user_version=1")
        }
        val bytes = fs.read(path) { readByteString() }
        StorageRuntime.open(root, fs, BundledSQLiteDriver()).problem(ProblemCode.INVALID_DATA)
        assertEquals(bytes, fs.read(path) { readByteString() })
    } }

    @Test fun acceptedProbeClosesAndCleansBeforeDriverCanOpenSource() = runTest { fresh { root ->
        val path = root / "library.sqlite3"
        BundledSQLiteDriver().open(path.toString()).close()
        val driver = TrackingDriver()
        var probe: Path? = null
        var closed = false
        driver.onClose = { file -> if (file != path.toString()) closed = true }
        driver.onOpen = { file ->
            if (file == path.toString()) {
                assertTrue(closed)
                assertFalse(fs.exists(probe!!))
            } else {
                probe = file.toPath().parent!!
                assertTrue(probe!!.name.startsWith("probe-"))
            }
        }
        val runtime = open(root, driver = driver)
        try {
            assertEquals(2, driver.paths.size)
            assertEquals(path.toString(), driver.paths.last())
            assertTrue(fs.list(root).none { it.name.startsWith("probe-") })
        } finally { runtime.close() }
    } }

    @Test fun newerSchemaWithRecoverableWalMustRemainPhysicallyUnchanged() = runTest { fresh { source -> fresh { root ->
        val sourcePath = source / "library.sqlite3"
        BundledSQLiteDriver().open(sourcePath.toString()).use { writer ->
            writer.exec("PRAGMA journal_mode=WAL")
            writer.exec("PRAGMA wal_autocheckpoint=0")
            writer.exec("CREATE TABLE protected_payload(value TEXT)")
            writer.exec("PRAGMA user_version=3")
            writer.exec("INSERT INTO protected_payload VALUES('owned synthetic fixture')")
            val mainOnly = source / "main-only"
            fs.createDirectory(mainOnly)
            fs.copy(sourcePath, mainOnly / "library.sqlite3")
            BundledSQLiteDriver().open((mainOnly / "library.sqlite3").toString()).use { control ->
                assertEquals(0, control.number("PRAGMA user_version"))
            }
            // Idle writer keeps a stable WAL. Copy only our own fixture to simulate crash leftovers.
            val database = root / "library.sqlite3"
            val wal = root / "library.sqlite3-wal"
            fs.copy(sourcePath, database)
            fs.copy(source / "library.sqlite3-wal", wal)
            fs.copy(source / "library.sqlite3-shm", root / "library.sqlite3-shm")
            fs.write(root / "future.bin") { writeUtf8("opaque future data") }
            fs.createDirectory(root / "staging")
            fs.write(root / "staging" / "media-${"a".repeat(32)}.tmp") { writeUtf8("not this open's staging") }
            val before = tree(root)
            val driver = TrackingDriver()
            driver.onOpen = { file ->
                assertNotEquals(database.toString(), file)
                assertFalse(fs.exists(file.toPath().parent!! / "library.sqlite3-shm"))
            }
            StorageRuntime.open(root, fs, driver).problem(ProblemCode.NEWER_SCHEMA)
            assertEquals(before, tree(root))
            assertEquals(1, driver.paths.size)
        }
    } } }

    private class TrackingDriver : SQLiteDriver {
        val paths = mutableListOf<String>()
        var onOpen: ((String) -> Unit)? = null
        var afterStep: ((String, String) -> Unit)? = null
        var onClose: ((String) -> Unit)? = null
        override fun open(fileName: String): SQLiteConnection {
            paths += fileName
            onOpen?.invoke(fileName)
            val connection = BundledSQLiteDriver().open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean = statement.step().also { afterStep?.invoke(fileName, sql) }
                    }
                }
                override fun close() { connection.close(); onClose?.invoke(fileName) }
            }
        }
    }

    private fun tree(root: Path): Map<String, String> = buildMap {
        for (path in fs.listRecursively(root)) {
            val name = path.relativeTo(root).toString()
            if (fs.metadata(path).isDirectory) put(name, "directory") else {
                val hashing = HashingSource.sha256(fs.source(path))
                hashing.use { input ->
                    val buffer = Buffer()
                    while (input.read(buffer, 8192) != -1L) buffer.clear()
                }
                put(name, "${fs.metadata(path).size}:${hashing.hash.hex()}")
            }
        }
    }

    @Test fun hotRollbackJournalRecoversOnlyOnProbeAndPreservesUnknownSourceTree() = runTest { fresh { fixture -> fresh { root ->
        val path = fixture / "library.sqlite3"
        BundledSQLiteDriver().open(path.toString()).use { writer ->
            writer.exec("PRAGMA journal_mode=DELETE")
            writer.exec("PRAGMA synchronous=FULL")
            writer.exec("PRAGMA cache_size=1")
            writer.exec("PRAGMA cache_spill=ON")
            writer.exec("CREATE TABLE protected_payload(value BLOB)")
            writer.exec("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<64) INSERT INTO protected_payload SELECT zeroblob(4096) FROM n")
            writer.exec("PRAGMA user_version=3")
            writer.exec("BEGIN IMMEDIATE")
            writer.exec("PRAGMA user_version=1")
            writer.exec("UPDATE protected_payload SET value=randomblob(4096)")
            fs.copy(path, root / "library.sqlite3")
            fs.copy(fixture / "library.sqlite3-journal", root / "library.sqlite3-journal")
            val before = tree(root)
            val driver = TrackingDriver()
            var recovered = false
            driver.onOpen = { file ->
                assertNotEquals((root / "library.sqlite3").toString(), file)
                assertTrue(fs.exists(file.toPath().parent!! / "library.sqlite3-journal"))
            }
            driver.afterStep = { file, sql -> if (sql == "PRAGMA user_version") {
                recovered = !fs.exists(file.toPath().parent!! / "library.sqlite3-journal")
            } }
            StorageRuntime.open(root, fs, driver).problem(ProblemCode.NEWER_SCHEMA)
            assertTrue(recovered, "real SQLite must have recovered the copied hot journal")
            assertEquals(before, tree(root))
            assertEquals(1, driver.paths.size)
            writer.exec("ROLLBACK")
        }
    } } }

    @Test fun probeTreatsQuestionHashAndUnicodeRootAsOrdinaryFilename() = runTest {
        fresh { outer ->
            val root = outer / "библиотека ? # медь"
            fs.createDirectory(root)
            var runtime = open(root)
            try { runtime.library.addBoard(board(), permit()).ok() } finally { runtime.close() }
            val driver = TrackingDriver()
            runtime = open(root, driver = driver)
            try {
                assertEquals(1, runtime.library.listing.value.items.size)
                assertTrue(driver.paths.all { it.startsWith(root.toString()) && !it.startsWith("file:") })
                assertTrue(fs.list(root).none { it.name.startsWith("probe-") })
            } finally { runtime.close() }
        }
    }

    @Test fun copySpaceFailureNeverCallsDriverOrChangesSource() = runTest { fresh { root ->
        BundledSQLiteDriver().open((root / "library.sqlite3").toString()).close()
        fs.write(root / "opaque") { writeUtf8("keep") }
        val before = tree(root)
        val driver = TrackingDriver()
        val fault = object : ForwardingFileSystem(fs) {
            override fun openReadWrite(file: Path, mustCreate: Boolean, mustExist: Boolean): FileHandle {
                if (file.parent?.name?.startsWith("probe-") == true) throw IOException("injected ENOSPC")
                return super.openReadWrite(file, mustCreate, mustExist)
            }
        }
        StorageRuntime.open(root, fault, driver).problem(ProblemCode.IO_FAILURE)
        assertTrue(driver.paths.isEmpty())
        assertEquals(before, tree(root))
    } }

    @Test fun sourceMutationDuringCopyIsDetectedWithoutOpeningOrRevertingSource() = runTest { fresh { root ->
        val path = root / "library.sqlite3"
        BundledSQLiteDriver().open(path.toString()).close()
        val driver = TrackingDriver()
        var reads = 0
        var external: Map<String, String>? = null
        val fault = object : ForwardingFileSystem(fs) {
            override fun source(file: Path): Source {
                if (file == path && ++reads == 2) {
                    // Deliberate violation of the single-writer prerequisite, in an owned fixture only.
                    BundledSQLiteDriver().open(path.toString()).use { it.exec("PRAGMA user_version=3") }
                    external = tree(root).filterKeys { !it.startsWith("probe-") }
                }
                return super.source(file)
            }
        }
        StorageRuntime.open(root, fault, driver).problem(ProblemCode.CONFLICT)
        assertTrue(driver.paths.isEmpty())
        assertNotNull(external)
        assertEquals(external, tree(root))
    } }

    @Test fun sourceMutationAfterCopyValidationStillForbidsLiveOpen() = runTest { fresh { root ->
        val path = root / "library.sqlite3"
        BundledSQLiteDriver().open(path.toString()).close()
        val driver = TrackingDriver()
        var external: Map<String, String>? = null
        driver.afterStep = { file, sql -> if (sql == "PRAGMA user_version") {
            assertNotEquals(path.toString(), file)
            driver.afterStep = null
            BundledSQLiteDriver().open(path.toString()).use { it.exec("PRAGMA user_version=3") }
            external = tree(root).filterKeys { !it.startsWith("probe-") }
        } }
        StorageRuntime.open(root, fs, driver).problem(ProblemCode.CONFLICT)
        assertEquals(1, driver.paths.size)
        assertNotNull(external)
        assertEquals(external, tree(root))
    } }

    @Test fun probeCleanupFailureForbidsLiveOpenAndDoesNotClaimOldProbeFolders() = runTest { fresh { root ->
        val path = root / "library.sqlite3"
        open(root).close() // Known schema1 preserves foreign folders; version0 must now refuse them.
        val unknown = root / "probe-existing-unknown"
        fs.createDirectory(unknown)
        fs.write(unknown / "sentinel") { writeUtf8("not owned by this open") }
        val before = tree(root)
        val driver = TrackingDriver()
        val fault = object : ForwardingFileSystem(fs) {
            override fun delete(path: Path, mustExist: Boolean) {
                if (path != unknown && path.name.startsWith("probe-")) throw IOException("injected cleanup")
                super.delete(path, mustExist)
            }
        }
        StorageRuntime.open(root, fault, driver).problem(ProblemCode.IO_FAILURE)
        assertEquals(1, driver.paths.size)
        val own = driver.paths.single().toPath().parent!!
        assertTrue(fs.exists(own))
        assertEquals(before, tree(root).filterKeys { !it.startsWith(own.name) })
        val retry = TrackingDriver()
        val runtime = open(root, driver = retry)
        try {
            assertTrue(retry.paths.none { it.toPath().parent == own || it.toPath().parent == unknown })
            assertTrue(fs.exists(own))
            assertEquals("not owned by this open", fs.read(unknown / "sentinel") { readUtf8() })
        } finally { runtime.close() }
    } }

    @Test fun corruptInvalidZeroAndIncompleteOneNeverReachLiveDriver() = runTest {
        for (kind in listOf("corrupt", "zero", "one")) fresh { root ->
            val path = root / "library.sqlite3"
            if (kind == "corrupt") fs.write(path) { writeUtf8("not SQLite") } else {
                BundledSQLiteDriver().open(path.toString()).use { db ->
                    db.exec("CREATE TABLE unrelated(value TEXT)")
                    if (kind == "one") db.exec("PRAGMA user_version=1")
                }
            }
            val before = tree(root)
            val driver = TrackingDriver()
            driver.onOpen = { assertNotEquals(path.toString(), it) }
            StorageRuntime.open(root, fs, driver).problem(if (kind == "corrupt") ProblemCode.IO_FAILURE else ProblemCode.INVALID_DATA)
            assertEquals(before, tree(root))
            assertEquals(1, driver.paths.size)
        }
    }

    @Test fun knownSchemaWalIsAcceptedBeforeLiveRecoveryAndLoadsAuthoritativeState() = runTest { fresh { fixture -> fresh { root ->
        val seeded = open(fixture)
        try { seeded.library.addBoard(board(), permit()).ok() } finally { seeded.close() }
        val path = fixture / "library.sqlite3"
        BundledSQLiteDriver().open(path.toString()).use { writer ->
            writer.exec("PRAGMA wal_autocheckpoint=0")
            val updated = board().copy(name = "committed in WAL")
            writer.exec("UPDATE entries SET name=?,revision=2,payload_json=? WHERE id=?", updated.name, encode(updated), updated.id.value)
            writer.exec("UPDATE library_meta SET revision=2")
            for (suffix in listOf("", "-wal", "-shm")) fs.copy(fixture / "library.sqlite3$suffix", root / "library.sqlite3$suffix")
            val before = tree(root)
            val driver = TrackingDriver()
            var live = false
            driver.onOpen = { file -> if (file == (root / "library.sqlite3").toString()) {
                live = true
                assertEquals(before, tree(root))
            } }
            val runtime = open(root, driver = driver)
            try {
                assertTrue(live)
                assertEquals(2, runtime.library.listing.value.revision)
                assertEquals("committed in WAL", runtime.library.listing.value.items.single().name)
                assertEquals(2, driver.paths.size)
            } finally { runtime.close() }
        }
    } } }

    @Test fun explicitCloseFailureRetainsHandleForExplicitRetry() = runTest { fresh { root ->
        val driver = FaultDriver()
        val runtime = open(root, driver = driver)
        runtime.library.addBoard(board(), permit()).ok()
        driver.failClose = true
        assertFailsWith<IOException> { runtime.close() }
        assertEquals(0, driver.successfulCloses)
        runtime.library.settings().problem(ProblemCode.STALE_REQUEST)
        runtime.close()
        runtime.close()
        assertEquals(1, driver.successfulCloses)
        val reopened = open(root)
        try { assertEquals(1, reopened.library.listing.value.items.size) } finally { reopened.close() }
    } }

    @Test fun probeCancellationIsNotConvertedToIoFailureByCleanupError() = runTest { fresh { root ->
        val path = root / "library.sqlite3"
        BundledSQLiteDriver().open(path.toString()).close()
        val before = tree(root)
        val driver = TrackingDriver()
        var reads = 0
        val fault = object : ForwardingFileSystem(fs) {
            override fun source(file: Path): Source {
                if (file == path && ++reads == 5) throw CancellationException("injected final stability cancellation")
                return super.source(file)
            }
            override fun delete(path: Path, mustExist: Boolean) {
                if (path.name.startsWith("probe-")) throw IOException("injected cleanup")
                super.delete(path, mustExist)
            }
        }
        assertFailsWith<CancellationException> { StorageRuntime.open(root, fault, driver) }
        assertEquals(1, driver.paths.size)
        assertTrue(driver.paths.none { it == path.toString() })
        assertEquals(before, tree(root).filterKeys { !it.startsWith("probe-") })
    } }

    private class IoGate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fun block() { entered.complete(Unit); runBlocking { release.await() } }
    }

    private suspend fun <T> responsiveMain(main: CoroutineDispatcher, gate: IoGate, action: suspend () -> T): T = coroutineScope {
        val operation = async(main) { action() }
        try {
            withContext(Dispatchers.Default) {
                withTimeout(10_000) { gate.entered.await() }
                val heartbeat = async(main) { true }
                assertTrue(withTimeout(10_000) { heartbeat.await() })
            }
            gate.release.complete(Unit)
            operation.await()
        } finally {
            gate.release.complete(Unit)
            withContext(NonCancellable) { operation.join() }
        }
    }

    @Test fun mainCallerRemainsResponsiveDuringSqlHashProbeSnapshotAndClose() = runTest { fresh { root ->
        val main = newSingleThreadContext("P04 test main")
        val driver = TrackingDriver()
        var readGate: Pair<(Path) -> Boolean, IoGate>? = null
        var deleteGate: IoGate? = null
        var rejectReads = false
        var runtime: StorageRuntime? = null
        val files = object : ForwardingFileSystem(fs) {
            override fun source(file: Path): Source {
                check(!rejectReads) { "synchronous handle/ref methods must not read files" }
                val source = super.source(file)
                val selected = readGate?.takeIf { it.first(file) }?.second ?: return source
                readGate = null
                return object : ForwardingSource(source) {
                    var first = true
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (first) { first = false; selected.block() }
                        return super.read(sink, byteCount)
                    }
                }
            }
            override fun delete(path: Path, mustExist: Boolean) {
                if (path.parent?.name?.startsWith("snapshot-") == true) deleteGate?.let { deleteGate = null; it.block() }
                super.delete(path, mustExist)
            }
        }
        try {
            val opening = IoGate()
            driver.onOpen = { driver.onOpen = null; opening.block() }
            responsiveMain(main, opening) { open(root, files, driver).also { runtime = it } }
            val active = runtime!!

            val sql = IoGate()
            driver.afterStep = { _, query -> if (query == "BEGIN IMMEDIATE") { driver.afterStep = null; sql.block() } }
            responsiveMain(main, sql) { active.library.addBoard(board(), permit()).ok() }

            val copying = IoGate()
            val input = object : ForwardingSource(Buffer().writeUtf8("worker bytes")) {
                var first = true
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (first) { first = false; copying.block() }
                    return super.read(sink, byteCount)
                }
            }
            val prepared = responsiveMain(main, copying) { active.media.prepare(input, MediaKind.IMAGE, "image/test").ok() }
            try {
                for (metadata in listOf(false, true)) {
                    val hashing = IoGate()
                    readGate = Pair({ file: Path -> file.parent?.parent?.name == "media" }, hashing)
                    responsiveMain(main, hashing) {
                        if (metadata) active.media.metadata(prepared.asset.id).ok()
                        else active.media.acquire(setOf(prepared.asset.id)).ok().close()
                    }
                }
                rejectReads = true
                withContext(main) {
                    active.media.file(prepared.asset.id, prepared.lease).ok()
                    prepared.lease.retain(setOf(prepared.asset.id)).ok().close()
                }
                rejectReads = false
            } finally { rejectReads = false; prepared.lease.close() }

            val snapshot = withContext(main) { active.library.snapshot().ok() }
            val streaming = IoGate()
            readGate = Pair({ file: Path -> file.name == "board-0.json" }, streaming)
            assertEquals(1, responsiveMain(main, streaming) { snapshot.boards().toList().size })
            val cleanup = IoGate()
            deleteGate = cleanup
            responsiveMain(main, cleanup) { snapshot.close() }

            val closing = IoGate()
            driver.onClose = { driver.onClose = null; closing.block() }
            responsiveMain(main, closing) { active.close() }
            val rejectedClose = IoGate()
            val rejected = object : ForwardingSource(Buffer()) { override fun close() { rejectedClose.block(); super.close() } }
            responsiveMain(main, rejectedClose) { active.media.prepare(rejected, MediaKind.IMAGE, "image/test") }.problem(ProblemCode.STALE_REQUEST)

            val probing = IoGate()
            readGate = Pair({ file: Path -> file == root / "library.sqlite3" }, probing)
            responsiveMain(main, probing) { open(root, files, driver).also { runtime = it } }
        } finally {
            readGate?.second?.release?.complete(Unit)
            deleteGate?.release?.complete(Unit)
            runtime?.close()
            main.close()
        }
    } }

    @Test fun actualMainJobCancellationAfterCommitKeepsCompletedOrUnconfirmedOutcome() = runTest {
        for (unknown in listOf(false, true)) fresh { root ->
            val main = newSingleThreadContext("P04 cancelled main")
            val parent = Job()
            val driver = FaultDriver()
            val runtime = open(root, driver = driver)
            val outcome = AtomicReference<OperationResult<PublishedBoard>?>(null)
            val failure = AtomicReference<Throwable?>(null)
            driver.after = { sql -> if (sql == "COMMIT") {
                driver.after = null
                parent.cancel()
                if (unknown) {
                    driver.before = { query -> if (query == "PRAGMA query_only=ON") throw IOException("injected unavailable proof") }
                    throw IOException("injected after commit")
                }
            } }
            val operation = CoroutineScope(main + parent).launch {
                try { outcome.store(runtime.library.addBoard(board(), permit())) } catch (error: Throwable) { failure.store(error) }
            }
            try {
                operation.join()
                assertNull(failure.load())
                if (unknown) assertEquals(OperationResult.UnconfirmedCommit, outcome.load()) else outcome.load()!!.ok()
                BundledSQLiteDriver().open((root / "library.sqlite3").toString()).use { observer ->
                    assertEquals(1, observer.number("SELECT count(*) FROM entries"))
                }
            } finally {
                parent.cancel()
                withContext(NonCancellable) { operation.join(); runtime.close() }
                main.close()
            }
        }
    }

    @Test fun actualMainJobCancellationBeforeCommitRollsBackOnWorker() = runTest { fresh { root ->
        val main = newSingleThreadContext("P04 precommit main")
        val parent = Job()
        val driver = FaultDriver()
        val runtime = open(root, driver = driver)
        val failure = AtomicReference<Throwable?>(null)
        driver.before = { sql -> if (sql.startsWith("INSERT OR REPLACE INTO entries")) { driver.before = null; parent.cancel() } }
        val operation = CoroutineScope(main + parent).launch {
            try { runtime.library.addBoard(board(), permit()) } catch (error: Throwable) { failure.store(error) }
        }
        try {
            operation.join()
            assertIs<CancellationException>(failure.load())
            assertFalse(runtime.quarantined)
            assertTrue(runtime.library.listing.value.items.isEmpty())
            assertEquals(0, runtime.library.listing.value.revision)
            BundledSQLiteDriver().open((root / "library.sqlite3").toString()).use { observer ->
                assertEquals(0, observer.number("SELECT count(*) FROM entries"))
            }
        } finally {
            parent.cancel()
            withContext(NonCancellable) { operation.join(); runtime.close() }
            main.close()
        }
    } }

    @Test fun retainedArchiveBudgetUsesLongTwoGiBWithoutPhysicalDiskFill() {
        val mib = 1024L * 1024
        val archiveLimit = 2L * 1024 * mib
        assertEquals(100 * mib, mediaByteCount(MediaKind.ARCHIVE, 64 * mib, 36 * mib))
        assertEquals(archiveLimit, mediaByteCount(MediaKind.ARCHIVE, archiveLimit - 8192, 8192))
        assertTrue(archiveLimit > Int.MAX_VALUE)
        assertEquals(ProblemCode.LIMIT_EXCEEDED, assertFailsWith<StorageFailure> { mediaByteCount(MediaKind.ARCHIVE, archiveLimit, 1) }.code)
        assertEquals(ProblemCode.LIMIT_EXCEEDED, assertFailsWith<StorageFailure> { mediaByteCount(MediaKind.ARCHIVE, Long.MAX_VALUE, 1) }.code)
        for (kind in listOf(MediaKind.IMAGE, MediaKind.AUDIO)) {
            assertEquals(64 * mib, mediaByteCount(kind, 64 * mib - 1, 1))
            assertEquals(ProblemCode.LIMIT_EXCEEDED, assertFailsWith<StorageFailure> { mediaByteCount(kind, 64 * mib, 1) }.code)
            assertEquals(ProblemCode.LIMIT_EXCEEDED, assertFailsWith<StorageFailure> { mediaByteCount(kind, 0, 100 * mib) }.code)
        }
    }

    private fun recordingBoard(id: String, asset: AssetId, image: AssetId? = null): BoardDocument = BoardDocument(
        BoardId(id), id, listOf(BoardPage(PageId("page"), 1, 1, listOf(
            BoardCell(0, CellKind.CONTENT, CardDocument(CardId("card"), "recording", image, SpeechSource.Recording(asset))),
        ))),
    )

    private suspend fun fingerprint(runtime: StorageRuntime): String = runtime.libraryStore.locked { runtime.db.fingerprint() }.ok()

    @Test fun wrongKindsNeverChangeBoardDraftMessageRetainedEpochRevisionsOrRefs() = runTest { fresh { root ->
        val runtime = open(root)
        val image = media(runtime, "image")
        val audio = runtime.media.prepare(Buffer().writeUtf8("audio"), MediaKind.AUDIO, "audio/test").ok()
        val archive = runtime.media.prepare(Buffer().writeUtf8("archive"), MediaKind.ARCHIVE, "application/octet-stream").ok()
        try {
            runtime.library.addBoard(board("old", image.asset.id), permit()).ok()
            val saved = draft(board("saved", image.asset.id))
            runtime.library.saveDraft(saved, null, permit()).ok()
            val oldMessage = message(image.asset.id)
            runtime.messages.saveMessage(oldMessage).ok()
            val before = fingerprint(runtime)
            suspend fun unchanged(code: ProblemCode = ProblemCode.INTEGRITY_FAILURE, action: suspend () -> OperationResult<*>) {
                action().problem(code)
                assertEquals(before, fingerprint(runtime))
                assertEquals(0, runtime.library.listing.value.epoch)
                assertEquals(2, runtime.library.listing.value.revision)
            }
            unchanged { runtime.library.addBoard(board("bad-image", audio.asset.id), permit()) }
            unchanged { runtime.library.replaceBoard(board("old", audio.asset.id), 1, permit()) }
            unchanged { runtime.library.addBoard(recordingBoard("bad-recording", image.asset.id), permit()) }
            unchanged { runtime.library.saveDraft(saved.copy(document = board("saved", audio.asset.id)), 1, permit()) }
            val form = EditorForm.Card(PageId("page"), 0, CardId("form"))
            unchanged { runtime.library.saveDraft(saved.copy(form = form.copy(image = audio.asset.id)), 1, permit()) }
            unchanged { runtime.library.saveDraft(saved.copy(form = form.copy(recording = archive.asset.id)), 1, permit()) }
            unchanged { runtime.messages.saveMessage(message(audio.asset.id, version = 2)) }
            val part = assertIs<MessagePart.Card>(message(image.asset.id).draft.parts.single())
            val wrongRecording = StoredMessage(0, 2, MessageDraft(listOf(part.copy(snapshot = part.snapshot.copy(image = null, speech = SpeechSource.Recording(image.asset.id))))))
            unchanged { runtime.messages.saveMessage(wrongRecording) }
            unchanged { runtime.library.retainArchive(RetainedArchive(BoardId("retained"), "raw", 1, image.asset.id, RetainedReason.INVALID_ARCHIVE), permit()) }
            unchanged(ProblemCode.INVALID_DATA) { runtime.library.addBoard(recordingBoard("conflict", image.asset.id, image.asset.id), permit()) }
            unchanged(ProblemCode.INVALID_DATA) { runtime.library.saveDraft(saved.copy(form = form.copy(recording = image.asset.id)), 1, permit()) }
            unchanged(ProblemCode.INVALID_DATA) { runtime.messages.saveMessage(wrongRecording.copy(draft = MessageDraft(listOf(part.copy(snapshot = part.snapshot.copy(speech = SpeechSource.Recording(image.asset.id))))))) }
            assertEquals(oldMessage, runtime.messages.message().ok())
            val preserved = runtime.library.draft(saved.id).ok()
            assertEquals(saved, preserved.draft.draft)
            preserved.lease.close()
            image.lease.close(); audio.lease.close(); archive.lease.close()
            assertEquals(2, runtime.media.collectGarbage().ok().removedAssets)
        } finally { image.lease.close(); audio.lease.close(); archive.lease.close(); runtime.close() }
    } }

    @Test fun snapshotSealChecksRoleKindsEvenWithAnExactAssetManifest() = runTest {
        for (role in listOf("image", "recording", "form-image", "form-recording", "retained")) fresh { root ->
            val runtime = open(root)
            val wrongKind = if (role.endsWith("image")) MediaKind.AUDIO else MediaKind.IMAGE
            val wrong = runtime.media.prepare(Buffer().writeUtf8(role), wrongKind, "application/test").ok()
            val builder = runtime.library.createSnapshotBuilder().ok()
            try {
                when (role) {
                    "image" -> builder.addBoard(PublishedBoard(board(asset = wrong.asset.id), 1)).ok()
                    "recording" -> builder.addBoard(PublishedBoard(recordingBoard("recording", wrong.asset.id), 1)).ok()
                    "form-image" -> builder.addDraft(StoredDraft(1, draft(board(), EditorForm.Card(PageId("page"), 0, CardId("form"), image = wrong.asset.id)))).ok()
                    "form-recording" -> builder.addDraft(StoredDraft(1, draft(board(), EditorForm.Card(PageId("page"), 0, CardId("form"), recording = wrong.asset.id)))).ok()
                    else -> builder.addRetained(RetainedArchive(BoardId("retained"), "raw", 1, wrong.asset.id, RetainedReason.INVALID_ARCHIVE)).ok()
                }
                builder.addAsset(wrong.asset).ok()
                val before = fingerprint(runtime)
                builder.seal(1, PortableSettings()).problem(ProblemCode.INTEGRITY_FAILURE)
                assertEquals(before, fingerprint(runtime))
            } finally { builder.close(); wrong.lease.close() }
            try { assertEquals(1, runtime.media.collectGarbage().ok().removedAssets) } finally { runtime.close() }
        }
    }

    @Test fun conflictingRolesAcrossSnapshotDocumentsAreRejectedWithoutLeakingRoots() = runTest { fresh { root ->
        val runtime = open(root)
        val image = media(runtime)
        val builder = runtime.library.createSnapshotBuilder().ok()
        try {
            builder.addBoard(PublishedBoard(board(asset = image.asset.id), 1)).ok()
            builder.addDraft(StoredDraft(1, draft(recordingBoard("draft", image.asset.id)))).problem(ProblemCode.INVALID_DATA)
            assertEquals(0, runtime.library.listing.value.revision)
        } finally { builder.close(); image.lease.close() }
        try { assertEquals(1, runtime.media.collectGarbage().ok().removedAssets) } finally { runtime.close() }
    } }

    @Test fun untrustedRestoreWrongRolePreservesLibraryMessageSettingsAndRefs() = runTest { fresh { root ->
        val runtime = open(root)
        val audio = runtime.media.prepare(Buffer().writeUtf8("audio"), MediaKind.AUDIO, "audio/test").ok()
        val builder = runtime.library.createSnapshotBuilder().ok()
        var source: SnapshotReader? = null
        try {
            runtime.library.addBoard(board("old"), permit()).ok()
            val oldMessage = StoredMessage(0, 3, MessageDraft(mode = InteractionMode.DIRECT))
            runtime.messages.saveMessage(oldMessage).ok()
            builder.addBoard(PublishedBoard(recordingBoard("incoming", audio.asset.id), 1)).ok()
            builder.addAsset(audio.asset).ok()
            val valid = builder.seal(17, PortableSettings(highContrast = true)).ok()
            source = valid
            val untrusted = object : SnapshotReader by valid {
                override fun boards() = flowOf(PublishedBoard(board("incoming", audio.asset.id), 1))
            }
            val before = fingerprint(runtime)
            runtime.library.replaceLibrary(untrusted, 1, oldMessage.stamp, permit()).problem(ProblemCode.INTEGRITY_FAILURE)
            assertEquals(before, fingerprint(runtime))
            assertEquals(0, runtime.library.listing.value.epoch)
            assertEquals(1, runtime.library.listing.value.revision)
            assertEquals("old", runtime.library.listing.value.items.single().id.value)
            assertEquals(oldMessage, runtime.messages.message().ok())
            assertFalse(runtime.library.settings().ok().highContrast)
            assertNull(runtime.library.recovery().ok())
        } finally { source?.close(); builder.close(); audio.lease.close() }
        try { assertEquals(1, runtime.media.collectGarbage().ok().removedAssets) } finally { runtime.close() }
    } }

    @Test fun recoverySwapRefusesCorruptRoleMetadataBeforeSwitchingEpoch() = runTest { fresh { root ->
        val runtime = open(root)
        val image = media(runtime)
        val builder = runtime.library.createSnapshotBuilder().ok()
        var reader: SnapshotReader? = null
        try {
            runtime.library.addBoard(board("old", image.asset.id), permit()).ok()
            builder.addBoard(PublishedBoard(board("new"), 1)).ok()
            val snapshot = builder.seal(1, PortableSettings()).ok()
            reader = snapshot
            val recovery = runtime.library.replaceLibrary(snapshot, 1, MessageStamp(0, 0), permit()).ok()
            runtime.libraryStore.locked { runtime.db.exec("UPDATE assets SET kind=? WHERE id=?", MediaKind.AUDIO.name, image.asset.id.value) }.ok()
            val before = fingerprint(runtime)
            runtime.library.returnToRecovery(recovery.id, 2, MessageStamp(1, 0), permit(1)).problem(ProblemCode.INTEGRITY_FAILURE)
            assertEquals(before, fingerprint(runtime))
            assertEquals(1, runtime.library.listing.value.epoch)
            assertEquals("new", runtime.library.listing.value.items.single().id.value)
            assertEquals(recovery.id, runtime.library.recovery().ok()!!.id)
        } finally { reader?.close(); builder.close(); image.lease.close(); runtime.close() }
        val before = tree(root)
        val driver = TrackingDriver()
        driver.onOpen = { assertNotEquals((root / "library.sqlite3").toString(), it) }
        StorageRuntime.open(root, fs, driver).problem(ProblemCode.INTEGRITY_FAILURE)
        assertEquals(before, tree(root))
    } }

    @Test fun correctImageAudioAndUnfinishedFormRolesPersistAndSeal() = runTest { fresh { root ->
        var runtime = open(root)
        val image = media(runtime, "image")
        val audio = runtime.media.prepare(Buffer().writeUtf8("audio"), MediaKind.AUDIO, "audio/test").ok()
        try {
            val document = recordingBoard("valid", audio.asset.id, image.asset.id)
            runtime.library.addBoard(document, permit()).ok()
            runtime.library.saveDraft(draft(document.copy(id = BoardId("draft-source")), EditorForm.Card(PageId("page"), 0, CardId("form"), image = image.asset.id, recording = audio.asset.id)), null, permit()).ok()
            val part = assertIs<MessagePart.Card>(message(image.asset.id).draft.parts.single())
            runtime.messages.saveMessage(StoredMessage(0, 1, MessageDraft(listOf(part.copy(snapshot = part.snapshot.copy(speech = SpeechSource.Recording(audio.asset.id))))))).ok()
            val snapshot = runtime.library.snapshot().ok()
            try {
                assertEquals(SnapshotCounts(1, 1, 0, 2, image.asset.byteCount + audio.asset.byteCount), snapshot.counts)
                assertEquals(setOf(MediaKind.IMAGE, MediaKind.AUDIO), snapshot.assets().toList().map { it.kind }.toSet())
            } finally { snapshot.close() }
        } finally { image.lease.close(); audio.lease.close(); runtime.close() }
        runtime = open(root)
        try { runtime.mediaStore.validatePersistentRoots() } finally { runtime.close() }
    } }

    @Test fun nativeProbeCannotOpenFailureIsIoAndNeverFallsBackToSource() = runTest { fresh { root ->
        val path = root / "library.sqlite3"
        BundledSQLiteDriver().open(path.toString()).close()
        val before = tree(root)
        val driver = TrackingDriver()
        driver.onOpen = { file ->
            assertNotEquals(path.toString(), file)
            // Actual native SQLITE_CANTOPEN, without permission changes or synthetic exception text.
            BundledSQLiteDriver().open((file.toPath().parent!! / "missing-parent" / "db").toString()).close()
        }
        StorageRuntime.open(root, fs, driver).problem(ProblemCode.IO_FAILURE)
        assertEquals(before, tree(root))
        assertEquals(1, driver.paths.size)
    } }

    @Test fun restoreRefreshFailurePublishesPrecommitStampAndInvalidatesLateOldBoard() = runTest { fresh { root ->
        val driver = FaultDriver()
        val gate = IoGate()
        var delay = false
        val files = object : ForwardingFileSystem(fs) {
            override fun source(file: Path): Source {
                val source = super.source(file)
                if (!delay || file.parent?.parent?.name != "media") return source
                delay = false
                return object : ForwardingSource(source) {
                    override fun read(sink: Buffer, byteCount: Long): Long { gate.block(); return super.read(sink, byteCount) }
                }
            }
        }
        val runtime = open(root, files, driver)
        val audio = runtime.media.prepare(Buffer().writeUtf8("old audio"), MediaKind.AUDIO, "audio/test").ok()
        var late: Deferred<OperationResult<LeasedBoard>>? = null
        var snapshot: SnapshotReader? = null
        val builder = runtime.library.createSnapshotBuilder().ok()
        try {
            runtime.library.addBoard(recordingBoard("old", audio.asset.id), permit()).ok()
            runtime.messages.saveMessage(StoredMessage(0, 3, MessageDraft(mode = InteractionMode.DIRECT))).ok()
            builder.addBoard(PublishedBoard(board("new"), 1)).ok()
            snapshot = builder.seal(17, PortableSettings()).ok()
            val cached = runtime.library.listing.value.items
            delay = true
            late = async(Dispatchers.Default) { runtime.library.board(BoardId("old")) }
            withContext(Dispatchers.Default) { withTimeout(10_000) { gate.entered.await() } }
            var postcommitStampReads = 0
            driver.after = { sql -> if (sql == "COMMIT") {
                driver.after = null
                driver.before = { query ->
                    if (query.startsWith("SELECT epoch FROM library_meta") || query.startsWith("SELECT revision FROM library_meta")) {
                        postcommitStampReads++
                        throw IOException("stamp must already be captured")
                    }
                    if (query.startsWith("SELECT id,name,kind,revision,payload_json,order_index")) throw IOException("injected listing refresh")
                }
            } }
            runtime.library.replaceLibrary(snapshot, 1, MessageStamp(0, 3), permit()).ok()
            val listing = runtime.library.listing.value
            assertEquals(1, listing.epoch)
            assertEquals(2, listing.revision)
            assertEquals(cached, listing.items)
            assertEquals(ProblemCode.IO_FAILURE, listing.refreshProblem?.code)
            assertEquals(0, postcommitStampReads)
            driver.before = null
            runtime.library.renameBoard(BoardId("new"), "stale", 1, permit()).problem(ProblemCode.STALE_REQUEST)
            runtime.messages.saveMessage(StoredMessage(0, 99, MessageDraft())).problem(ProblemCode.STALE_REQUEST)
            gate.release.complete(Unit)
            val old = late.await().ok()
            try { assertNotEquals(runtime.library.listing.value.epoch, old.epoch) } finally { old.lease.close() }
            assertEquals(StoredMessage(1, 0, MessageDraft()), runtime.messages.message().ok())
        } finally {
            gate.release.complete(Unit)
            driver.before = null; driver.after = null
            late?.join()
            snapshot?.close(); builder.close(); audio.lease.close(); runtime.close()
        }
    } }

    @Test fun registryThousandsOfLivePinsRetainAndCloseIndependently() = runTest {
        val pins = StoragePins()
        val ids = List(5000) { AssetId(it.toString(16).padStart(64, '0')) }
        val leases = ids.map { pins.pin(setOf(it)) }
        assertTrue(ids.all(pins::pinned))
        coroutineScope {
            (0 until 8).map { lane -> async(Dispatchers.Default) {
                for (index in lane until ids.size step 8) {
                    val id = ids[index]
                    val retained = leases[index].retain(setOf(id)).ok()
                    leases[index].close(); leases[index].close()
                    assertTrue(pins.pinned(id))
                    assertTrue(pins.allows(id, retained))
                    assertFalse(pins.allows(id, leases[index]))
                    retained.close(); retained.close()
                    assertFalse(pins.pinned(id))
                }
            } }.awaitAll()
        }
        assertTrue(ids.none(pins::pinned))
        val foreign = StoragePins().pin(setOf(ids[0]))
        assertFalse(pins.allows(ids[0], foreign))
        foreign.close()
        val own = pins.pin(setOf(ids[0]))
        pins.stopRetains()
        own.retain(setOf(ids[0])).problem(ProblemCode.STALE_REQUEST)
        assertTrue(pins.pinned(ids[0]))
        own.close()
        assertFalse(pins.pinned(ids[0]))
    }

    @Test fun registryRetainRacingParentCloseNeverResurrectsClosedHandle() = runTest {
        val pins = StoragePins()
        val id = AssetId("a".repeat(64))
        repeat(100) {
            val parent = pins.pin(setOf(id))
            val children = coroutineScope {
                val retainers = (0 until 8).map { async(Dispatchers.Default) { parent.retain(setOf(id)) } }
                val closing = async(Dispatchers.Default) { parent.close() }
                retainers.awaitAll().also { closing.await() }
            }
            for (child in children) when (child) {
                is OperationResult.Completed -> { assertTrue(pins.allows(id, child.value)); child.value.close(); child.value.close() }
                else -> child.problem(ProblemCode.STALE_REQUEST)
            }
            assertFalse(pins.pinned(id))
            parent.retain(setOf(id)).problem(ProblemCode.STALE_REQUEST)
        }
    }

    @Test fun stageThousandsOfPinsSealOrCloseLinearlyWhileGcKeepsOwnersSafe() = runTest {
        for (seal in listOf(false, true)) fresh { root ->
            val runtime = open(root)
            val media = media(runtime)
            val stage = SnapshotStage.create(runtime)
            var reader: SnapshotReader? = null
            try {
                stage.addBoard(PublishedBoard(board(asset = media.asset.id), 1)).ok()
                stage.addAsset(media.asset).ok()
                coroutineScope {
                    val collector = async(Dispatchers.Default) { repeat(16) { assertEquals(0, runtime.media.collectGarbage().ok().removedAssets) } }
                    (0 until 8).map { async(Dispatchers.Default) {
                        repeat(625) { stage.attach(media.lease.retain(setOf(media.asset.id)).ok()) }
                    } }.awaitAll()
                    collector.await()
                }
                if (seal) reader = stage.seal(0, PortableSettings()).ok() else stage.close()
                media.lease.close()
                if (seal) assertEquals(0, runtime.media.collectGarbage().ok().removedAssets)
                reader?.close()
                stage.close()
                val late = runtime.mediaStore.pin(setOf(media.asset.id))
                assertEquals(ProblemCode.STALE_REQUEST, assertFailsWith<StorageFailure> { stage.attach(late) }.code)
                assertEquals(1, runtime.media.collectGarbage().ok().removedAssets)
            } finally { reader?.close(); stage.close(); media.lease.close(); runtime.close() }
        }
    }

    @Test fun snapshotCaptureCancellationStopsAfterEachCurrentMetadataWriteAndReleasesPins() = runTest {
        for (target in listOf("board-0.json", "draft-0.json", "asset-0.json")) fresh { root ->
            val gate = IoGate()
            val names = mutableListOf<String>()
            var armed = false
            val files = object : ForwardingFileSystem(fs) {
                override fun sink(file: Path, mustCreate: Boolean): Sink {
                    val sink = super.sink(file, mustCreate)
                    if (!armed || file.parent?.name?.startsWith("snapshot-") != true) return sink
                    names += file.name
                    if (file.name != target) return sink
                    return object : Sink by sink { override fun close() { sink.close(); gate.block() } }
                }
            }
            val runtime = open(root, files)
            val media = listOf(media(runtime, "first"), media(runtime, "second"))
            val parent = Job()
            var work: Job? = null
            val handedOff = AtomicReference<SnapshotReader?>(null)
            val failure = AtomicReference<Throwable?>(null)
            try {
                for (index in 0..1) {
                    runtime.library.addBoard(board("board-$index", media[index].asset.id), permit()).ok()
                    runtime.library.saveDraft(draft(board("draft-doc-$index", media[index].asset.id)).copy(id = DraftId("draft-$index")), null, permit()).ok()
                    media[index].lease.close()
                }
                val oldMessage = message(media[0].asset.id)
                runtime.messages.saveMessage(oldMessage).ok()
                val before = fingerprint(runtime)
                armed = true
                work = CoroutineScope(Dispatchers.Default + parent).launch {
                    try { handedOff.store(runtime.library.snapshot().ok()) } catch (error: Throwable) { failure.store(error) }
                }
                withContext(Dispatchers.Default) { withTimeout(10_000) { gate.entered.await() } }
                parent.cancel()
                gate.release.complete(Unit)
                work.join()
                assertIs<CancellationException>(failure.load())
                assertNull(handedOff.load())
                val expected = listOf("board-0.json", "board-1.json", "draft-0.json", "draft-1.json", "asset-0.json")
                assertEquals(expected.take(expected.indexOf(target) + 1), names)
                assertEquals(before, fingerprint(runtime))
                assertEquals(oldMessage, runtime.messages.message().ok())
                for (index in 0..1) {
                    runtime.library.deleteBoard(BoardId("board-$index"), 1, permit()).ok()
                    runtime.library.deleteDraft(DraftId("draft-$index"), 1, permit()).ok()
                }
                runtime.messages.saveMessage(StoredMessage(0, 2, MessageDraft())).ok()
                assertEquals(2, runtime.media.collectGarbage().ok().removedAssets)
            } finally {
                parent.cancel(); gate.release.complete(Unit)
                withContext(NonCancellable) { work?.join(); handedOff.load()?.close(); media.forEach { it.lease.close() }; runtime.close() }
            }
            assertTrue(fs.list(root / "staging").none { it.name.startsWith("snapshot-") })
        }
    }

    @Test fun emptyVersionZeroRefusesUnrecognizedOriginalEntriesWithoutSourceOpen() = runTest {
        for (name in listOf("legacyfile.maybe.zip", "future.bin", "probe-old-empty")) fresh { root ->
            val path = root / "library.sqlite3"
            BundledSQLiteDriver().open(path.toString()).close()
            if (name.startsWith("probe-")) fs.createDirectory(root / name) else fs.write(root / name) { writeUtf8("owned synthetic legacy bytes") }
            val before = tree(root)
            val driver = TrackingDriver()
            driver.onOpen = { assertNotEquals(path.toString(), it) }
            StorageRuntime.open(root, fs, driver).problem(ProblemCode.INVALID_DATA)
            assertEquals(before, tree(root))
            assertEquals(1, driver.paths.size)
        }
    }

    @Test fun emptyVersionZeroAcceptsOnlyKnownEmptyOwnedDirectoriesAndItsOwnProbe() = runTest { fresh { root ->
        BundledSQLiteDriver().open((root / "library.sqlite3").toString()).close()
        fs.createDirectory(root / "media")
        fs.createDirectory(root / "staging")
        val runtime = open(root)
        try {
            assertEquals(0, runtime.library.listing.value.epoch)
            assertEquals(0, runtime.library.listing.value.revision)
            assertTrue(runtime.library.listing.value.items.isEmpty())
            assertTrue(fs.list(root).none { it.name.startsWith("probe-") })
        } finally { runtime.close() }
    } }

    @Test fun malformedMessageJsonNeverChangesVersionOrReferences() = runTest { fresh { root ->
        val runtime = open(root)
        val image = media(runtime)
        try {
            val old = message(image.asset.id, version = 7)
            runtime.messages.saveMessage(old).ok()
            val before = fingerprint(runtime)
            for (bad in listOf("left\uD800right", "left\uDC00right")) {
                val value = StoredMessage(0, 8, MessageDraft(listOf(MessagePart.Text(MessagePartId("text"), bad))))
                runtime.messages.saveMessage(value).problem(ProblemCode.INVALID_DATA)
                assertEquals(before, fingerprint(runtime))
                assertEquals(old, runtime.messages.message().ok())
            }
        } finally { image.lease.close(); runtime.close() }
    } }

    @Test fun malformedCardLabelJsonCannotPublishOrReplaceAValidBoard() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val original = board()
            runtime.library.addBoard(original, permit()).ok()
            val before = fingerprint(runtime)
            for (bad in listOf("left\uD800right", "left\uDC00right")) {
                val invalid = original.copy(name = "valid e\u0301 😀", pages = original.pages.map { page ->
                    page.copy(cells = page.cells.map { cell -> cell.copy(card = cell.card?.copy(label = bad)) })
                })
                runtime.library.replaceBoard(invalid, 1, permit()).problem(ProblemCode.INVALID_DATA)
                runtime.library.addBoard(invalid.copy(id = BoardId("new")), permit()).problem(ProblemCode.INVALID_DATA)
                assertEquals(before, fingerprint(runtime))
                assertEquals(1, runtime.library.listing.value.revision)
                val preserved = runtime.library.board(original.id).ok()
                assertEquals(original, preserved.board.document)
                preserved.lease.close()
            }
        } finally { runtime.close() }
    } }

    @Test fun escapedNulJsonDataAndValidUnicodePersistExactlyWithoutNormalization() = runTest { fresh { root ->
        var runtime = open(root)
        val text = "  left\u0000right e\u0301 😀  "
        val original = board().copy(name = "  valid e\u0301 😀  ", pages = board().pages.map { page ->
            page.copy(cells = page.cells.map { cell -> cell.copy(card = cell.card?.copy(label = text, extensionsJson = "{\"opaque\":\"left\\u0000right\"}")) })
        })
        val stored = StoredMessage(0, 3, MessageDraft(listOf(MessagePart.Text(MessagePartId("text"), text))))
        try {
            assertTrue(encode(original).contains("\\u0000"))
            assertFalse('\u0000' in encode(original))
            runtime.library.addBoard(original, permit()).ok()
            runtime.messages.saveMessage(stored).ok()
            runtime.library.snapshot().ok().close()
        } finally { runtime.close() }
        runtime = open(root)
        try {
            val loaded = runtime.library.board(original.id).ok()
            try { assertEquals(original, loaded.board.document) } finally { loaded.lease.close() }
            assertEquals(stored, runtime.messages.message().ok())
            assertEquals(original.name, runtime.library.listing.value.items.single().name)
        } finally { runtime.close() }
    } }

    @Test fun corruptRawJsonTextIsRejectedButEscapedNulDataIsDecodedExactly() {
        for (bad in listOf("left\uD800right", "left\uDC00right", "left\u0000right")) {
            val raw = "{\"schema\":1,\"value\":\"$bad\"}"
            assertEquals(ProblemCode.INVALID_DATA, assertFailsWith<StorageFailure> { decode<String>(raw) }.code)
        }
        assertEquals("left\u0000right", decode<String>("{\"schema\":1,\"value\":\"left\\u0000right\"}"))
    }

    @Test fun nulRootIsRejectedBeforeFilesystemOrDriverCalls() = runTest { fresh { root ->
        var metadataCalls = 0
        val guarded = object : ForwardingFileSystem(fs) {
            override fun metadataOrNull(path: Path): FileMetadata? {
                metadataCalls++
                error("invalid root must not reach FS")
            }
        }
        val driver = TrackingDriver()
        val before = tree(root)
        StorageRuntime.open((root.toString() + "\u0000tail").toPath(), guarded, driver).problem(ProblemCode.INVALID_DATA)
        assertEquals(0, metadataCalls)
        assertTrue(driver.paths.isEmpty())
        assertEquals(before, tree(root))
    } }

    @Test fun sharedStringBindingRejectsUnsafeTextWithoutChangingTheActualDriver() = runTest { fresh { root ->
        val runtime = open(root)
        try {
            val before = fingerprint(runtime)
            for (bad in listOf("left\u0000right", "left\uD800right", "left\uDC00right")) {
                runtime.libraryStore.locked { runtime.db.rows("SELECT ?", bad) }.problem(ProblemCode.INVALID_DATA)
                runtime.libraryStore.locked { runtime.db.exec("SELECT ?", bad) }.problem(ProblemCode.INVALID_DATA)
                assertEquals(before, fingerprint(runtime))
            }
            for (good in listOf("", "  Кириллица e\u0301 😀  ", "'\"; --\n", "\\u0000", encode("left\u0000right"))) {
                val actual = runtime.libraryStore.locked { runtime.db.rows("SELECT ?", good).single().single() }.ok()
                assertEquals(good, actual)
                runtime.libraryStore.locked { runtime.db.exec("SELECT ?", good) }.ok()
            }
        } finally { runtime.close() }
    } }
}
