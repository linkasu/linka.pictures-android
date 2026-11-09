package su.linka.pictures.application.portability

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.buffer
import okio.use
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.portability.linka.configEntry
import su.linka.pictures.portability.linka.simpleConfig
import su.linka.pictures.portability.linka.syntheticZip
import kotlin.test.*

class TransferCoordinatorTest {
    private suspend fun fresh(block: suspend TransferTestRig.() -> Unit) {
        val rig = TransferTestRig.fresh()
        try { withContext(Dispatchers.IO) { rig.block() } } finally { rig.clean() }
    }

    @Test fun exportReportCountsUniqueMediaFromCapturedBoardAndCheckedArchiveBeforeConcurrentEdit() = runTest { fresh {
        val imageBytes = "SYNTHETIC_IMAGE_repeated".encodeToByteArray()
        val audioBytes = "SYNTHETIC_AUDIO_repeated".encodeToByteArray()
        val image = runtime.media.prepare(Buffer().write(imageBytes), MediaKind.IMAGE, "image/test").ok()
        val audio = runtime.media.prepare(Buffer().write(audioBytes), MediaKind.AUDIO, "audio/test").ok()
        val original = board().copy(pages = listOf(
            BoardPage(PageId("first"), 1, 2, (0..1).map { position ->
                BoardCell(position, CellKind.CONTENT, CardDocument(CardId("card-$position"), "original-$position",
                    image.asset.id, SpeechSource.Recording(audio.asset.id)))
            }),
            BoardPage(PageId("second"), 1, 2, listOf(
                BoardCell(0, CellKind.CONTENT, CardDocument(CardId("third"), "original-third",
                    image.asset.id, SpeechSource.Recording(audio.asset.id))), BoardCell(1, CellKind.EMPTY))),
        ))
        try { runtime.library.addBoard(original, permit()).ok() }
        finally { image.lease.close(); audio.lease.close() }
        val edited = board(name = "newer board").copy(pages = listOf(BoardPage(PageId("new"), 1, 1, emptyList())))
        choose = {
            runtime.library.replaceBoard(edited, 1, permit()).ok()
            assertEquals(0, runtime.media.collectGarbage().ok().removedAssets)
        }
        val workflow = coordinator()
        val id = workflow.prepareExport("owner", BoardId("board")).ok()
        val expected = BoardTransferReport(2, 3, 1, 1, (imageBytes.size + audioBytes.size).toLong(), false)
        assertEquals(original.name, workflow.state.value[id]!!.preview!!.name)
        assertEquals(expected, workflow.state.value[id]!!.preview!!.board)
        val media = runtime.media.acquire(setOf(image.asset.id, audio.asset.id)).ok()
        try {
            assertContentEquals(imageBytes, runtime.media.source(image.asset.id, media).ok().buffer().use { it.readByteArray() })
            assertContentEquals(audioBytes, runtime.media.source(audio.asset.id, media).ok().buffer().use { it.readByteArray() })
            workflow.confirm("owner", id, capability, TransferConfirmation.WriteFile).ok()
            assertEquals(expected, workflow.state.value[id]!!.preview!!.board)
            val source = runtime.library.board(BoardId("board")).ok()
            try { assertEquals(edited, source.board.document); assertEquals(2L, source.board.revision) }
            finally { source.lease.close() }
            input = external.copyOf()
            val imported = workflow.prepareImport("owner", "captured export").ok()
            assertEquals(expected, workflow.state.value[imported]!!.preview!!.board)
            workflow.cancel("owner", imported, null).ok()
            assertContentEquals(imageBytes, runtime.media.source(image.asset.id, media).ok().buffer().use { it.readByteArray() })
            assertContentEquals(audioBytes, runtime.media.source(audio.asset.id, media).ok().buffer().use { it.readByteArray() })
        } finally { media.close() }
        assertEquals(0, privateFiles.temporaryCount)
        assertEquals(0, privateFiles.openHandles)
    } }

    @Test fun safeStopAfterPickerRevocationNeedsNoFreshCapabilityAndCannotStopAnotherOwner() = runTest { fresh {
        add()
        val workflow = coordinator()
        choose = { permitted = false }
        val foreign = workflow.prepareExport("other", BoardId("board")).ok()
        for (staleOrAbsent in listOf(capability, null)) {
            val id = workflow.prepareExport("owner", BoardId("board")).ok()
            val checksBefore = checks
            workflow.cancel("owner", foreign, staleOrAbsent).problem(ProblemCode.STALE_REQUEST)
            workflow.cancel("other", foreign, capability).problem(ProblemCode.UNAUTHORIZED)
            workflow.cancel("owner", id, AssistantCapability("foreign", "other")).problem(ProblemCode.UNAUTHORIZED)
            assertEquals(TransferPhase.AWAITING_CONFIRMATION, workflow.state.value[id]!!.transfer.phase)
            workflow.cancel("owner", id, staleOrAbsent).ok()
            assertEquals(checksBefore, checks)
            assertEquals(TransferPhase.CANCELLED, workflow.state.value[id]!!.transfer.phase)
            assertEquals(TransferPhase.AWAITING_CONFIRMATION, workflow.state.value[foreign]!!.transfer.phase)
        }
        workflow.cancel("other", foreign, null).ok()
        assertEquals(0, writes); assertTrue(external.isEmpty()); assertEquals(3, closes)
        assertEquals(0, guards); assertEquals(0, checks)
        assertEquals(0, privateFiles.temporaryCount)
    } }

    @Test fun safeStopRunningWriteAfterGateRevocationWithoutCapabilityPreservesOnlyAdmittedPartial() = runTest { fresh {
        add()
        val workflow = coordinator()
        val id = workflow.prepareExport("owner", BoardId("board")).ok()
        val entered = CompletableDeferred<Unit>()
        chunk = { permitted = false; entered.complete(Unit); awaitCancellation() }
        coroutineScope {
            val writing = async { workflow.confirm("owner", id, capability, TransferConfirmation.WriteFile) }
            entered.await()
            val checksBefore = checks
            workflow.cancel("owner", id, null).ok()
            assertEquals(checksBefore, checks)
            assertEquals(OperationResult.Cancelled, writing.await())
        }
        assertEquals(TransferPhase.CANCELLED, workflow.state.value[id]!!.transfer.phase)
        assertEquals(32, external.size); assertEquals(1, writes); assertEquals(1, closes)
        assertEquals(0, privateFiles.temporaryCount)
        assertEquals(0, privateFiles.openHandles)
    } }

    @Test fun stateKeepsOnlyLatestOperationPerOwnerAndRetainsTerminalUntilNextAdmission() = runTest { fresh {
        add(); linkaInput()
        val workflow = coordinator()
        val other = workflow.prepareImport("other", "other").ok()
        var previous: OperationId? = null
        repeat(5) {
            previous?.let { old ->
                assertEquals(TransferPhase.COMPLETED, workflow.state.value[old]!!.transfer.phase)
            }
            val id = workflow.prepareImport("owner", "repeated").ok()
            previous?.let { old -> assertFalse(old in workflow.state.value) }
            assertEquals(setOf(other, id), workflow.state.value.keys)
            assertEquals(TransferPhase.AWAITING_CONFIRMATION, workflow.state.value[other]!!.transfer.phase)
            workflow.prepareImport("owner", "conflict").problem(ProblemCode.CONFLICT)
            assertEquals(setOf(other, id), workflow.state.value.keys)
            workflow.confirm("owner", id, capability, TransferConfirmation.ImportNew).ok()
            assertEquals(TransferPhase.COMPLETED, workflow.state.value[id]!!.transfer.phase)
            assertEquals(setOf(other, id), workflow.state.value.keys)
            previous = id
        }
        workflow.revokePending("other")
        assertEquals(TransferPhase.CANCELLED, workflow.state.value[other]!!.transfer.phase)
        val otherNext = workflow.prepareImport("other", "other next").ok()
        assertEquals(setOf(otherNext, previous), workflow.state.value.keys)
        assertEquals(TransferPhase.COMPLETED, workflow.state.value[previous]!!.transfer.phase)
        workflow.revokePending("other")
        workflow.close("owner", capability).ok()
        assertEquals(setOf(otherNext), workflow.state.value.keys)
        assertEquals(TransferPhase.CANCELLED, workflow.state.value[otherNext]!!.transfer.phase)
        workflow.prepareImport("owner", "closed owner").problem(ProblemCode.STALE_REQUEST)
        assertEquals(setOf(otherNext), workflow.state.value.keys)
        assertEquals(0, privateFiles.temporaryCount)
    } }

    @Test fun importIsPreviewOnlyAndNewNeverOverwritesSameName() = runTest { fresh {
        add(); linkaInput()
        val original = input.copyOf()
        val workflow = coordinator()
        val id = workflow.prepareImport("owner", "same name").ok()
        assertEquals(1, runtime.library.listing.value.items.size)
        assertEquals(0, guards)
        assertEquals(TransferPhase.AWAITING_CONFIRMATION, workflow.state.value[id]!!.transfer.phase)
        workflow.prepareImport("owner", "other").problem(ProblemCode.CONFLICT)
        workflow.confirm("foreign", id, capability, TransferConfirmation.ImportNew).problem(ProblemCode.STALE_REQUEST)
        workflow.confirm("owner", id, capability, TransferConfirmation.ImportNew).ok()
        assertEquals(2, runtime.library.listing.value.items.size)
        assertEquals(2, runtime.library.listing.value.items.map { it.id }.toSet().size)
        assertContentEquals(original, input)
        assertEquals(1, guards)
        assertEquals(TransferPhase.COMPLETED, workflow.state.value[id]!!.transfer.phase)
        assertEquals(0, privateFiles.temporaryCount)
        assertEquals(0, privateFiles.openHandles)
    } }

    @Test fun replaceUsesExplicitTargetAndExpectedRevision() = runTest { fresh {
        add(); linkaInput()
        val workflow = coordinator()
        val id = workflow.prepareImport("owner", "replacement").ok()
        runtime.library.renameBoard(BoardId("board"), "changed", 1, permit()).ok()
        workflow.confirm("owner", id, capability, TransferConfirmation.ImportReplace(BoardId("board"), 1)).problem(ProblemCode.CONFLICT)
        assertEquals("changed", runtime.library.listing.value.items.single().name)
        assertEquals(TransferPhase.FAILED, workflow.state.value[id]!!.transfer.phase)
        assertEquals(0, privateFiles.temporaryCount)
    } }

    @Test fun exportNeedsFreshAuthorizationAfterPickerAndDoesNotLeakContents() = runTest { fresh {
        add()
        choose = { permitted = false }
        val workflow = coordinator()
        val id = workflow.prepareExport("owner", BoardId("board")).ok()
        assertTrue(external.isEmpty()); assertEquals(0, writes); assertEquals(0, guards)
        workflow.confirm("owner", id, capability, TransferConfirmation.WriteFile).problem(ProblemCode.UNAUTHORIZED)
        assertEquals(1, guards); assertEquals(0, writes); assertEquals(1, closes)
        assertEquals(0, privateFiles.temporaryCount)
    } }

    @Test fun exportUsesCapturedBoardAndRevocationStopsNextChunkWithoutRollbackClaim() = runTest { fresh {
        add()
        val workflow = coordinator()
        val id = workflow.prepareExport("owner", BoardId("board")).ok()
        runtime.library.deleteBoard(BoardId("board"), 1, permit()).ok()
        chunk = { permitted = false }
        workflow.confirm("owner", id, capability, TransferConfirmation.WriteFile).problem(ProblemCode.UNAUTHORIZED)
        assertEquals(32, external.size)
        assertEquals(TransferPhase.FAILED, workflow.state.value[id]!!.transfer.phase)
        assertEquals(0, privateFiles.temporaryCount)
        assertEquals(1, closes)
    } }

    @Test fun backupRestoreCapturesStampsAndRecoveryIsExplicitAndAtomic() = runTest { fresh {
        add(); backupInput()
        val workflow = coordinator()
        val restore = workflow.prepareRestore("owner").ok()
        val preview = workflow.state.value[restore]!!.preview!!
        assertEquals(1, preview.snapshot!!.boards)
        assertEquals(1L, preview.expectedLibraryRevision)
        runtime.messages.saveMessage(StoredMessage(0, 4, MessageDraft())).ok()
        workflow.confirm("owner", restore, capability, TransferConfirmation.ReplaceWholeLibrary).problem(ProblemCode.CONFLICT)
        assertNull(runtime.library.recovery().ok())
        val retry = workflow.prepareRestore("owner").ok()
        workflow.confirm("owner", retry, capability, TransferConfirmation.ReplaceWholeLibrary).ok()
        assertEquals(1L, workflow.state.value[retry]!!.stateCommittedEpoch)
        assertEquals(StoredMessage(1, 0, MessageDraft()), runtime.messages.message().ok())
        val recovery = runtime.library.recovery().ok()!!
        val occupied = workflow.prepareRestore("owner").ok()
        workflow.confirm("owner", occupied, capability, TransferConfirmation.ReplaceWholeLibrary).problem(ProblemCode.CONFLICT)
        assertEquals(recovery, runtime.library.recovery().ok())
        val swap = workflow.prepareReturnToRecovery("owner", recovery.id).ok()
        workflow.confirm("owner", swap, capability, TransferConfirmation.ReturnToRecovery).ok()
        assertEquals(2L, workflow.state.value[swap]!!.stateCommittedEpoch)
        val delete = workflow.prepareDeleteRecovery("owner", runtime.library.recovery().ok()!!.id).ok()
        assertNotNull(runtime.library.recovery().ok())
        workflow.confirm("owner", delete, capability, TransferConfirmation.DeleteRecovery).ok()
        assertNull(runtime.library.recovery().ok())
        assertEquals(0, privateFiles.temporaryCount)
    } }

    @Test fun libraryRevisionConflictDoesNotRefreshCapturedExpectedStamp() = runTest { fresh {
        add(); backupInput()
        val workflow = coordinator()
        val id = workflow.prepareRestore("owner").ok()
        runtime.library.renameBoard(BoardId("board"), "new", 1, permit()).ok()
        workflow.confirm("owner", id, capability, TransferConfirmation.ReplaceWholeLibrary).problem(ProblemCode.CONFLICT)
        assertEquals("new", runtime.library.listing.value.items.single().name)
        assertNull(runtime.library.recovery().ok())
    } }

    @Test fun revokePendingNeverResumesAndLatePickerResultIsCleaned() = runTest { fresh {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        pick = { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
        val workflow = coordinator()
        coroutineScope {
            val preparation = async { workflow.prepareImport("owner", "late") }
            entered.await()
            val id = workflow.state.value.keys.single()
            val cancellation = async(start = CoroutineStart.UNDISPATCHED) { workflow.revokePending("owner") }
            // Revocation is observable at the guard boundary before the late source is delivered.
            release.complete(Unit)
            cancellation.await()
            assertEquals(OperationResult.Cancelled, preparation.await())
            assertEquals(TransferPhase.CANCELLED, workflow.state.value[id]!!.transfer.phase)
            workflow.confirm("owner", id, capability, TransferConfirmation.ImportNew).problem(ProblemCode.STALE_REQUEST)
        }
        assertEquals(0, privateFiles.temporaryCount)
    } }

    @Test fun pendingCancelClosesDestinationAndOwnTemporary() = runTest { fresh {
        add()
        val workflow = coordinator()
        val id = workflow.prepareBackup("owner").ok()
        workflow.cancel("owner", id, capability).ok()
        assertEquals(TransferPhase.CANCELLED, workflow.state.value[id]!!.transfer.phase)
        assertEquals(0, writes); assertEquals(1, closes)
        assertEquals(0, privateFiles.temporaryCount)
        workflow.close("owner", capability).ok()
        assertTrue(workflow.state.value.isEmpty())
    } }

    @Test fun failedAndUnknownPublicationHaveDifferentTerminalStatesAndUnknownFreezes() = runTest { fresh {
        add(); linkaInput()
        var outcome: OperationResult<PublishedBoard> = OperationResult.Failed(Problem(ProblemCode.IO_FAILURE))
        val library = object : LibraryStore by runtime.library {
            override suspend fun addBoard(value: BoardDocument, permit: WritePermit) = outcome
        }
        val workflow = coordinator(library)
        val failed = workflow.prepareImport("owner", "failure").ok()
        workflow.confirm("owner", failed, capability, TransferConfirmation.ImportNew).problem(ProblemCode.IO_FAILURE)
        assertEquals(TransferPhase.FAILED, workflow.state.value[failed]!!.transfer.phase)
        outcome = OperationResult.UnconfirmedCommit
        val unknown = workflow.prepareImport("owner", "unknown").ok()
        assertEquals(OperationResult.UnconfirmedCommit, workflow.confirm("owner", unknown, capability, TransferConfirmation.ImportNew))
        assertEquals(TransferPhase.UNCONFIRMED, workflow.state.value[unknown]!!.transfer.phase)
        workflow.prepareBackup("owner").problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)
        assertEquals(0, privateFiles.temporaryCount)
    } }

    @Test fun runningWriteCancelPreservesExternalPartialAndNeverBecomesIoFailure() = runTest { fresh {
        add()
        val entered = CompletableDeferred<Unit>()
        val workflow = coordinator()
        val id = workflow.prepareExport("owner", BoardId("board")).ok()
        chunk = { entered.complete(Unit); awaitCancellation() }
        coroutineScope {
            val writing = async { workflow.confirm("owner", id, capability, TransferConfirmation.WriteFile) }
            entered.await()
            workflow.cancel("owner", id, capability).ok()
            assertEquals(OperationResult.Cancelled, writing.await())
        }
        assertEquals(32, external.size)
        assertEquals(TransferPhase.CANCELLED, workflow.state.value[id]!!.transfer.phase)
        assertEquals(0, privateFiles.temporaryCount)
        assertEquals(0, privateFiles.openHandles)
    } }

    @Test fun cancellationAfterConfirmedPublicationCannotClaimRollback() = runTest { fresh {
        add(); linkaInput()
        val committed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val library = object : LibraryStore by runtime.library {
            override suspend fun addBoard(value: BoardDocument, permit: WritePermit): OperationResult<PublishedBoard> {
                val result = runtime.library.addBoard(value, permit)
                withContext(NonCancellable) { committed.complete(Unit); release.await() }
                return result
            }
        }
        val workflow = coordinator(library)
        val id = workflow.prepareImport("owner", "committed").ok()
        coroutineScope {
            val publication = async { workflow.confirm("owner", id, capability, TransferConfirmation.ImportNew) }
            committed.await()
            val cancellation = async(start = CoroutineStart.UNDISPATCHED) { workflow.cancel("owner", id, capability) }
            release.complete(Unit)
            cancellation.await().ok()
            publication.await().ok()
        }
        assertEquals(2, runtime.library.listing.value.items.size)
        assertEquals(TransferPhase.COMPLETED, workflow.state.value[id]!!.transfer.phase)
        assertEquals(0, privateFiles.temporaryCount)
    } }

    @Test fun invalidInputFailsBeforePublicationAndWorkflowCountersDescribeCheckedArchive() = runTest { fresh {
        add(); linkaInput()
        val workflow = coordinator()
        workflow.prepareImport("owner", "bad\u0000name").problem(ProblemCode.INVALID_DATA)
        assertEquals(1, runtime.library.listing.value.items.size)
        assertEquals(0, privateFiles.temporaryCount)
        val id = workflow.prepareImport("owner", "valid").ok()
        assertEquals(1, workflow.state.value[id]!!.transfer.processedEntries)
        assertTrue(workflow.state.value[id]!!.transfer.processedBytes > 0)
        workflow.cancel("owner", id, capability).ok()
        workflow.prepareImport("owner", "x".repeat(65537)).problem(ProblemCode.LIMIT_EXCEEDED)
        assertEquals(0, privateFiles.temporaryCount)
    } }

    @Test fun publicBackupWritesCapturedSnapshotOnlyOnConfirmAndCanRestoreIt() = runTest { fresh {
        add()
        val workflow = coordinator()
        val backup = workflow.prepareBackup("owner").ok()
        assertEquals(0, writes)
        assertTrue(external.isEmpty())
        runtime.library.renameBoard(BoardId("board"), "after snapshot", 1, permit()).ok()
        workflow.confirm("owner", backup, capability, TransferConfirmation.WriteFile).ok()
        assertTrue(external.isNotEmpty())
        assertEquals("after snapshot", runtime.library.listing.value.items.single().name)
        assertEquals(TransferPhase.COMPLETED, workflow.state.value[backup]!!.transfer.phase)
        input = external.copyOf()
        val restore = workflow.prepareRestore("owner").ok()
        workflow.confirm("owner", restore, capability, TransferConfirmation.ReplaceWholeLibrary).ok()
        assertEquals("same name", runtime.library.listing.value.items.single().name)
        assertNotNull(runtime.library.recovery().ok())
        assertEquals(0, privateFiles.temporaryCount)
        assertEquals(0, privateFiles.openHandles)
    } }

    @Test fun activeArchiveModesFailWholeWorkflowWithoutPublication() = runTest { fresh {
        val workflow = coordinator()
        for (mode in listOf("quiz", "match")) {
            input = syntheticZip(listOf(configEntry(simpleConfig.replace("standard", mode))))
            workflow.prepareImport("owner", "active").problem(ProblemCode.UNSUPPORTED_MODE)
            assertTrue(runtime.library.listing.value.items.isEmpty())
            assertEquals(0, privateFiles.temporaryCount)
            assertEquals(TransferPhase.FAILED, workflow.state.value.values.last().transfer.phase)
        }
        assertEquals(0, guards)
    } }
}
