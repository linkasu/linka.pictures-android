package su.linka.pictures.ui.app

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import su.linka.pictures.application.*
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.communication.CommunicationSession
import su.linka.pictures.application.communication.CommunicationCommand
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.application.communication.MessageSavePhase
import su.linka.pictures.application.library.LibraryController
import su.linka.pictures.application.portability.TransferPhase
import su.linka.pictures.application.portability.TransferConfirmation
import su.linka.pictures.application.portability.TransferTestRig
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.storage.StorageRuntime
import kotlin.test.*

class ProductNavigationTest {
    private class Access : AssistantAccess {
        override val state = MutableStateFlow<AssistantState>(AssistantState.Unprotected(OwnerVerificationAvailability.UNAVAILABLE))
        val open = MutableStateFlow<Set<String>>(emptySet())
        var revokeFailure = false
        private val generation = MutableStateFlow(0)
        override fun check(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): OperationResult<Unit> =
            if (ownerId in open.value) OperationResult.Completed(Unit) else OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
        override fun guard(action: ProtectedAction, capability: AssistantCapability?, ownerId: String): CommitGuard {
            val issued = generation.value
            return { if (issued != generation.value) OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED)) else check(action, capability, ownerId) }
        }
        override suspend fun unlock(password: String, ownerId: String): OperationResult<AssistantCapability> =
            OperationResult.Completed(AssistantCapability("test-only", ownerId))
        override suspend fun setPassword(password: String, ownerId: String, capability: AssistantCapability?) = OperationResult.Failed(Problem(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE))
        override suspend fun resetPasswordWithDeviceOwner(password: String, ownerId: String) = OperationResult.Failed(Problem(ProblemCode.OWNER_VERIFICATION_UNAVAILABLE))
        override suspend fun disableLock(capability: AssistantCapability, ownerId: String) = OperationResult.Failed(Problem(ProblemCode.UNAUTHORIZED))
        override suspend fun refreshPrerequisites() = Unit
        override fun revokeOwner(ownerId: String) {
            if (revokeFailure) error("Injected access revoke failure")
            generation.update { it + 1 }
        }
        override fun revokeAll() { generation.update { it + 1 } }
    }
    private class Fixture(val runtime: StorageRuntime) {
        val native = AuthoringNativeFakes()
        val access = Access()
        var stopFailure = false
        var closeFailure = false
        val uncaught = MutableStateFlow(0)
        val stopCalls = MutableStateFlow(0)
        val interrupts = MutableStateFlow(0)
        val stops = MutableStateFlow<List<Pair<String, RequestId?>>>(emptyList())
        val active = MutableStateFlow<PlaybackRequest?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> uncaught.update { it + 1 } })
        val audio = object : AudioPort by native {
            override fun play(request: PlaybackRequest) {
                active.value?.let { stop(it.owner.id, it.id) }
                native.play(request)
                active.value = request
            }
            override fun stop(ownerId: String, requestId: RequestId?) {
                stopCalls.update { it + 1 }
                stops.update { it + (ownerId to requestId) }
                if (stopFailure) error("Injected stop failure")
                val playing = active.value
                if (playing?.owner?.id == ownerId && (requestId == null || requestId == playing.id)) {
                    active.value = null
                    native.stop(ownerId, requestId)
                } else if (native.state.value.owner?.id == ownerId &&
                    (requestId == null || native.state.value.requestId == requestId)) native.stop(ownerId, requestId)
            }
            override fun interrupt() {
                interrupts.update { it + 1 }
                native.state.value.owner?.let { stop(it.id, native.state.value.requestId) }
            }
        }
        private val serial = MutableStateFlow(0)
        val ids = IdSource { "p13-${serial.updateAndGet { it + 1 }}" }
        val communication = CommunicationSession(runtime.library, runtime.messages, runtime.media, audio, ids, scope)
        val controller = LibraryController(runtime.library, access)
        val events = MutableStateFlow<List<String>>(emptyList())
        var onOpen: (() -> Unit)? = null
        val closedWhileActive = MutableStateFlow<List<Boolean>>(emptyList())
        val navigation: ProductNavigation = ProductNavigation(runtime.library, runtime.media, runtime.messages,
            access, audio, native, native, native, ids, scope, communication, controller,
            { owner -> access.open.update { it + owner }; events.update { it + "open:$owner" }; onOpen?.invoke() },
            { owner ->
                closedWhileActive.update { it + (currentJob()?.isActive == true) }
                access.open.update { it - owner }; events.update { it + "close:$owner" }
                if (closeFailure) error("Injected native close failure")
            }, { error("No replacement expected in this fixture") })
        private fun currentJob() = navigation.route.value?.scope?.coroutineContext?.get(Job)
        fun send(route: ProductRoute, action: AuthoringAction) = route.editor!!.send(AuthoringCommand(CommandId(ids.next()), route.owner, route.capability.value, action))
        suspend fun await(route: ProductRoute, predicate: (AuthoringState) -> Boolean) = withContext(Dispatchers.Default) {
            withTimeout(10_000) { route.editor!!.state.first(predicate) }
        }
        suspend fun awaitCommunication(predicate: (CommunicationState) -> Boolean) = withContext(Dispatchers.Default) {
            withTimeout(10_000) { communication.state.first(predicate) }
        }
        suspend fun finish() {
            stopFailure = false; closeFailure = false; access.revokeFailure = false
            try { navigation.open(ProductPage.COMMUNICATION) }
            finally { scope.cancel(); scope.coroutineContext[Job]!!.join() }
        }
    }
    private suspend fun fresh(test: suspend (Fixture) -> Unit) {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "p13-navigation-${kotlin.random.Random.nextLong()}"
        fs.createDirectory(root, mustCreate = true)
        val runtime = assertIs<OperationResult.Completed<StorageRuntime>>(StorageRuntime.open(root, fs, BundledSQLiteDriver())).value
        val fixture = Fixture(runtime)
        try { test(fixture) } finally { fixture.finish(); runtime.close(); fs.deleteRecursively(root) }
    }

    @Test fun concurrentStartOpensLibraryOnceAndNeverReopensAfterCommunication() = runTest { fresh { f ->
        coroutineScope { List(20) { async(Dispatchers.Default) { f.navigation.start() } }.awaitAll() }
        val first = assertNotNull(f.navigation.route.value)
        assertEquals(ProductPage.LIBRARY, first.page)
        assertEquals(listOf("open:${first.owner}"), f.events.value)
        f.navigation.start()
        assertSame(first, f.navigation.route.value)
        f.navigation.open(ProductPage.COMMUNICATION)
        f.navigation.start()
        assertNull(f.navigation.route.value)
        assertEquals(listOf("open:${first.owner}", "close:${first.owner}"), f.events.value)
    } }

    @Test fun disposedNavigationCannotStartAndDisposalDuringOwnerOpenLeavesNoOrphan() = runTest {
        fresh { f ->
            f.navigation.dispose()
            f.navigation.start()
            assertNull(f.navigation.route.value)
            assertTrue(f.events.value.isEmpty())
        }
        fresh { f ->
            f.onOpen = { f.navigation.dispose() }
            f.navigation.start()
            assertNull(f.navigation.route.value)
            assertTrue(f.access.open.value.isEmpty())
            assertEquals(2, f.events.value.size)
            assertEquals(f.events.value[0].removePrefix("open:"), f.events.value[1].removePrefix("close:"))
        }
    }

    @Test fun editorOwnersAreUniqueBackgroundPausesAndResumeIsExplicit() = runTest { fresh { f ->
        f.navigation.open(ProductPage.EDITOR, AuthoringAction.NewBoard())
        val first = f.navigation.route.value!!
        assertEquals(setOf(first.owner), f.access.open.value)
        assertTrue(f.send(first, first.initialAction!!))
        val saved = f.await(first) { it.savePhase == DraftSavePhase.SAVED }
        val guard = f.access.guard(ProtectedAction.EDIT_BOARD, null, first.owner)
        first.capability.value = assertIs<OperationResult.Completed<AssistantCapability>>(f.access.unlock("", first.owner)).value
        f.navigation.background()
        assertNull(first.capability.value)
        assertIs<OperationResult.Failed>(guard())
        f.await(first) { it.phase == AuthoringPhase.PAUSED }
        assertEquals(AuthoringPhase.PAUSED, first.editor!!.state.value.phase)
        f.send(first, AuthoringAction.Resume)
        f.await(first) { it.phase == AuthoringPhase.EDITING }
        f.navigation.open(ProductPage.LIBRARY)
        assertFalse(first.scope.coroutineContext[Job]!!.isActive)
        assertFalse(first.owner in f.access.open.value)
        f.navigation.open(ProductPage.EDITOR, AuthoringAction.ResumeDraft(saved.draft!!.id))
        val second = f.navigation.route.value!!
        assertNotEquals(first.owner, second.owner)
        assertNotSame(first.editor, second.editor)
        f.send(second, second.initialAction!!)
        assertEquals(saved.draft!!.id, f.await(second) { it.phase == AuthoringPhase.EDITING }.draft!!.id)
        assertTrue(f.closedWhileActive.value.all { it })
    } }

    @Test fun leavingTransferRevokesPendingAndAwaitsPickerCancellationBeforeScopeCancellation() = runTest { fresh { f ->
        f.native.pickResult = CompletableDeferred()
        f.navigation.open(ProductPage.TRANSFER)
        val route = f.navigation.route.value!!
        val prepare = route.scope.async { route.transfer!!.prepareImport(route.owner, "Импорт") }
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.pickEntered.await() } }
        f.navigation.open(ProductPage.LIBRARY)
        assertTrue(prepare.isCompleted)
        assertFalse(route.scope.coroutineContext[Job]!!.isActive)
        assertTrue(route.transfer!!.state.value.values.all { it.transfer.phase == TransferPhase.CANCELLED })
        assertEquals(listOf("open:${route.owner}", "close:${route.owner}", "open:${f.navigation.route.value!!.owner}"), f.events.value)
        assertTrue(f.closedWhileActive.value.single())
    } }

    @Test fun disposalDuringSlowTransferCleanupCannotOpenAnOrphanOwner() = runTest { fresh { f ->
        f.native.pickResult = CompletableDeferred()
        f.native.ignorePickCancellation = true
        f.navigation.open(ProductPage.TRANSFER)
        val route = f.navigation.route.value!!
        route.scope.launch { route.transfer!!.prepareImport(route.owner, "Импорт") }
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.pickEntered.await() } }
        val leaving = f.scope.async { f.navigation.open(ProductPage.LIBRARY) }
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.events.first { "close:${route.owner}" in it } } }
        f.navigation.dispose()
        f.native.pickResult!!.complete(OperationResult.Cancelled)
        leaving.await()
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.navigation.route.first { it == null } } }
        assertTrue(f.access.open.value.isEmpty())
        assertEquals(listOf("open:${route.owner}", "close:${route.owner}"), f.events.value)
        assertFalse(route.scope.coroutineContext[Job]!!.isActive)
    } }

    @Test fun unconfirmedOutcomeAllowsCommunicationButNoNewProtectedSession() = runTest { fresh { f ->
        f.navigation.open(ProductPage.LIBRARY)
        val libraryRoute = f.navigation.route.value
        f.controller.markCommitUnconfirmed()
        for (page in listOf(ProductPage.EDITOR, ProductPage.SETTINGS, ProductPage.ACCESS, ProductPage.TRANSFER)) {
            f.navigation.open(page, AuthoringAction.NewBoard())
            assertSame(libraryRoute, f.navigation.route.value)
        }
        f.navigation.open(ProductPage.COMMUNICATION)
        assertNull(f.navigation.route.value)
        assertTrue(f.access.open.value.isEmpty())
    } }

    @Test fun composePlaybackSurvivesBrowsingAndBoardNavigationButProtectedEntryStopsIt() = runTest { fresh { f ->
        val first = BoardDocument(BoardId("speaking"), "Исходная доска", listOf(
            BoardPage(PageId("first"), 1, 1, listOf(BoardCell(0, CellKind.CONTENT,
                CardDocument(CardId("want"), "Хочу", speech = SpeechSource.Tts("Хочу"))))),
            BoardPage(PageId("second"), 1, 1, listOf(BoardCell(0, CellKind.CONTENT,
                CardDocument(CardId("other-page-card"), "Другая страница", speech = SpeechSource.Tts("Другое")))))))
        val other = BoardDocument(BoardId("other-board"), "Другая доска", listOf(
            BoardPage(PageId("other-board-page"), 1, 1, listOf(BoardCell(0, CellKind.CONTENT,
                CardDocument(CardId("other-board-card"), "Другая доска", speech = SpeechSource.Tts("Новый текст")))))))
        fun permit() = WritePermit(f.runtime.library.listing.value.epoch) { OperationResult.Completed(Unit) }
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(first, permit()))
        assertIs<OperationResult.Completed<PublishedBoard>>(f.runtime.library.addBoard(other, permit()))
        f.awaitCommunication { it.savePhase == MessageSavePhase.SAVED }
        f.communication.dispatch(CommunicationCommand.OpenBoard(CommandId(f.ids.next()), first.id))
        f.awaitCommunication { it.message.cursor?.boardId == first.id && it.savePhase == MessageSavePhase.SAVED }
        f.communication.dispatch(CommunicationCommand.Select(CommandId(f.ids.next()), PageId("first"), 0))
        f.awaitCommunication { it.renderedText == "Хочу" && it.savePhase == MessageSavePhase.SAVED }
        f.communication.dispatch(CommunicationCommand.Speak(CommandId(f.ids.next())))
        val request = withContext(Dispatchers.Default) { withTimeout(10_000) { f.active.first { it != null }!! } }
        f.awaitCommunication { it.audio.requestId == request.id && it.audio.phase == AudioPhase.PLAYING }
        assertEquals(AudioPurpose.COMMUNICATION, request.owner.purpose)
        assertEquals(InteractionMode.COMPOSE, f.communication.state.value.message.mode)
        val interrupts = f.interrupts.value
        val stops = f.stops.value.size

        f.navigation.start()
        f.navigation.start()
        assertSame(request, f.active.value)
        f.navigation.open(ProductPage.COMMUNICATION)
        f.communication.dispatch(CommunicationCommand.ChangePage(CommandId(f.ids.next()), PageId("second")))
        f.awaitCommunication { it.message.cursor?.pageId == PageId("second") && it.savePhase == MessageSavePhase.SAVED }
        f.navigation.open(ProductPage.LIBRARY)
        f.communication.dispatch(CommunicationCommand.OpenBoard(CommandId(f.ids.next()), other.id))
        f.navigation.open(ProductPage.COMMUNICATION)
        f.awaitCommunication { it.message.cursor?.boardId == other.id && it.savePhase == MessageSavePhase.SAVED }
        assertSame(request, f.active.value)
        assertSame(request.plan, f.active.value!!.plan)
        assertEquals("Хочу", request.plan.renderedText)
        assertEquals(request.id, f.communication.state.value.audio.requestId)
        assertEquals(AudioPhase.PLAYING, f.communication.state.value.audio.phase)
        assertEquals(interrupts, f.interrupts.value)
        assertTrue(f.stops.value.drop(stops).none { it.first == request.owner.id })
        assertEquals(1, f.native.played.size)

        var playing = request
        for ((index, page) in listOf(ProductPage.EDITOR, ProductPage.SETTINGS, ProductPage.ACCESS, ProductPage.TRANSFER).withIndex()) {
            f.navigation.open(page, AuthoringAction.EditBoard(other.id))
            f.awaitCommunication { it.audio.phase == AudioPhase.IDLE }
            assertNull(f.active.value)
            assertEquals(interrupts + index + 1, f.interrupts.value)
            assertTrue(f.stops.value.any { it.first == playing.owner.id && it.second == playing.id })
            val exitStops = f.stops.value.size
            f.navigation.open(ProductPage.LIBRARY)
            f.navigation.open(ProductPage.COMMUNICATION)
            val target = if (index % 2 == 0) first.id else other.id
            f.communication.dispatch(CommunicationCommand.OpenBoard(CommandId(f.ids.next()), target))
            f.awaitCommunication { it.message.cursor?.boardId == target && it.savePhase == MessageSavePhase.SAVED }
            assertTrue(f.stops.value.drop(exitStops).none { it.first == playing.owner.id })
            assertNull(f.active.value)
            assertEquals(interrupts + index + 1, f.interrupts.value)
            assertEquals(index + 1, f.native.played.size)
            if (page != ProductPage.TRANSFER) {
                f.communication.dispatch(CommunicationCommand.Speak(CommandId(f.ids.next())))
                playing = withContext(Dispatchers.Default) { withTimeout(10_000) { f.active.first { it != null }!! } }
                f.awaitCommunication { it.audio.requestId == playing.id && it.audio.phase == AudioPhase.PLAYING }
                assertEquals("Хочу", playing.plan.renderedText)
            }
        }
    } }

    @Test fun disposalRevokesOldGuardBeforeThrowingAudioStopAndStillCleansOwner() = runTest { fresh { f ->
        f.navigation.open(ProductPage.LIBRARY)
        val route = f.navigation.route.value!!
        val guard = f.access.guard(ProtectedAction.RENAME_BOARD, null, route.owner)
        assertIs<OperationResult.Completed<Unit>>(guard())
        f.stopFailure = true
        runCatching { f.navigation.dispose() }
        assertIs<OperationResult.Failed>(guard())
        withContext(Dispatchers.Default) { withTimeout(10_000) { route.scope.coroutineContext[Job]!!.join() } }
        assertFalse(route.owner in f.access.open.value)
        assertEquals(listOf("open:${route.owner}", "close:${route.owner}"), f.events.value)
        assertTrue(f.controller.requiresRestart)
        assertEquals(Problem(ProblemCode.IO_FAILURE), f.navigation.problem.value)
        assertFalse(route.closeResult.await())
    } }

    @Test fun throwingNativeCloseStillAwaitsTransferCleanupAndNeverOpensANewOwner() = runTest { fresh { f ->
        f.native.pickResult = CompletableDeferred()
        f.navigation.open(ProductPage.TRANSFER)
        val route = f.navigation.route.value!!
        val prepare = route.scope.async { route.transfer!!.prepareImport(route.owner, "Импорт") }
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.native.pickEntered.await() } }
        f.closeFailure = true
        f.navigation.open(ProductPage.LIBRARY)
        assertTrue(prepare.isCompleted)
        assertFalse(route.scope.coroutineContext[Job]!!.isActive)
        assertFalse(route.closeResult.await())
        assertTrue(route.transfer!!.state.value.values.all { it.transfer.phase == TransferPhase.CANCELLED })
        assertNull(f.navigation.route.value)
        assertTrue(f.closedWhileActive.value.single())
        assertEquals(listOf("open:${route.owner}", "close:${route.owner}"), f.events.value)
        f.navigation.open(ProductPage.EDITOR, AuthoringAction.NewBoard())
        assertNull(f.navigation.route.value)
        assertEquals(Problem(ProblemCode.IO_FAILURE), f.navigation.problem.value)
        assertEquals(0, f.uncaught.value)
    } }

    @Test fun authoringRevokeAudioFailureCannotSkipNativeCloseOrChildCancellation() = runTest { fresh { f ->
        f.navigation.open(ProductPage.EDITOR, AuthoringAction.NewBoard())
        val route = f.navigation.route.value!!
        f.send(route, route.initialAction!!)
        f.await(route) { it.savePhase == DraftSavePhase.SAVED }
        val guard = f.access.guard(ProtectedAction.APPLY_DRAFT, null, route.owner)
        f.stopFailure = true
        f.navigation.open(ProductPage.LIBRARY)
        assertIs<OperationResult.Failed>(guard())
        assertTrue(f.stopCalls.value >= 2)
        assertFalse(route.scope.coroutineContext[Job]!!.isActive)
        assertNull(f.navigation.route.value)
        assertFalse(route.owner in f.access.open.value)
        assertTrue(f.closedWhileActive.value.single())
        assertEquals(Problem(ProblemCode.IO_FAILURE), f.navigation.problem.value)
        assertEquals(0, f.uncaught.value)
    } }

    @Test fun failingCoreRevokeIsNotTreatedAsSuccessfulLifecycleOrAuthorization() = runTest { fresh { f ->
        f.navigation.open(ProductPage.LIBRARY)
        val route = f.navigation.route.value!!
        f.access.revokeFailure = true
        f.navigation.open(ProductPage.SETTINGS)
        assertFalse(route.scope.coroutineContext[Job]!!.isActive)
        assertFalse(route.closeResult.await())
        assertNull(f.navigation.route.value)
        assertTrue(f.access.open.value.isEmpty())
        assertEquals(Problem(ProblemCode.IO_FAILURE), f.navigation.problem.value)
        assertTrue(f.controller.requiresRestart)
        assertEquals(listOf("open:${route.owner}", "close:${route.owner}"), f.events.value)
    } }

    private class RestoreFixture(val rig: TransferTestRig) {
        val native = AuthoringNativeFakes()
        val access = Access()
        val controller = LibraryController(rig.runtime.library, access)
        var stopFailure = false
        var reloadFailure = false
        val attempts = MutableStateFlow(0)
        val delivered = MutableStateFlow<List<Long>>(emptyList())
        val uncaught = MutableStateFlow(0)
        val scope = CoroutineScope(rig.scope.coroutineContext + CoroutineExceptionHandler { _, _ -> uncaught.update { it + 1 } })
        val audio = object : AudioPort by native {
            override fun stop(ownerId: String, requestId: RequestId?) {
                if (stopFailure) error("Injected stop failure")
                native.stop(ownerId, requestId)
            }
        }
        val communication = CommunicationSession(rig.runtime.library, rig.runtime.messages, rig.runtime.media, native, rig.ids, scope)
        val navigation = ProductNavigation(rig.runtime.library, rig.runtime.media, rig.runtime.messages, access,
            audio, rig.files, native, rig.inspection, rig.ids, scope, communication, controller,
            { owner -> access.open.update { it + owner } }, { owner -> access.open.update { it - owner } },
            { epoch ->
                attempts.update { it + 1 }
                if (reloadFailure) error("Injected root reload failure")
                delivered.update { it + epoch }
            })
        suspend fun restore(): ProductRoute {
            navigation.open(ProductPage.TRANSFER)
            val route = navigation.route.value!!
            val operation = assertIs<OperationResult.Completed<OperationId>>(route.transfer!!.prepareRestore(route.owner)).value
            assertIs<OperationResult.Completed<Unit>>(route.transfer.confirm(route.owner, operation, null, TransferConfirmation.ReplaceWholeLibrary))
            return route
        }
        suspend fun finish() {
            stopFailure = false; reloadFailure = false
            try { navigation.open(ProductPage.COMMUNICATION) } finally { rig.clean() }
        }
    }

    private suspend fun freshRestore(test: suspend (RestoreFixture) -> Unit) {
        val rig = TransferTestRig.fresh()
        rig.add(); rig.backupInput()
        val fixture = RestoreFixture(rig)
        try { test(fixture) } finally { fixture.finish() }
    }

    @Test fun failedRootReloadNeverClaimsDeliveredEpochOrAutomaticallyRetries() = runTest { freshRestore { f ->
        f.reloadFailure = true
        val route = f.restore()
        withContext(Dispatchers.Default) { withTimeout(10_000) { f.attempts.first { it == 1 } } }
        f.navigation.open(ProductPage.LIBRARY)
        assertTrue(f.controller.requiresRestart)
        assertEquals(Problem(ProblemCode.IO_FAILURE), f.navigation.problem.value)
        assertEquals(f.rig.runtime.library.listing.value.epoch, f.navigation.committedEpoch.value)
        assertNull(route.deliveredEpoch)
        assertTrue(f.delivered.value.isEmpty())
        assertEquals(1, f.attempts.value)
        assertEquals(TransferPhase.COMPLETED, route.transfer!!.state.value.values.single().transfer.phase)
        assertEquals(0, f.uncaught.value)
        f.navigation.open(ProductPage.TRANSFER)
        assertNull(f.navigation.route.value)
        assertEquals(1, f.attempts.value)
    } }

    @Test fun confirmedRestoreEpochSurvivesSecondaryStopFailureDuringClose() = runTest { freshRestore { f ->
        val route = f.restore()
        val epoch = f.rig.runtime.library.listing.value.epoch
        f.stopFailure = true
        f.navigation.open(ProductPage.LIBRARY)
        assertEquals(TransferPhase.COMPLETED, route.transfer!!.state.value.values.single().transfer.phase)
        assertEquals(epoch, route.deliveredEpoch)
        assertEquals(epoch, f.navigation.committedEpoch.value)
        assertEquals(listOf(epoch), f.delivered.value)
        assertEquals(1, f.attempts.value)
        assertFalse(route.scope.coroutineContext[Job]!!.isActive)
        assertNull(f.navigation.route.value)
        assertEquals(Problem(ProblemCode.IO_FAILURE), f.navigation.problem.value)
        assertEquals(0, f.uncaught.value)
    } }

    @Test fun actualRestoreEpochIsDeliveredOnceAndNeverReplayedFromTransferHistory() = runTest {
        val rig = TransferTestRig.fresh()
        val native = AuthoringNativeFakes()
        val access = Access()
        val delivered = MutableStateFlow<List<Long>>(emptyList())
        val communication = CommunicationSession(rig.runtime.library, rig.runtime.messages, rig.runtime.media, native, rig.ids, rig.scope)
        val navigation = ProductNavigation(rig.runtime.library, rig.runtime.media, rig.runtime.messages, access,
            native, rig.files, native, rig.inspection, rig.ids, rig.scope, communication,
            LibraryController(rig.runtime.library, access), { owner -> access.open.update { it + owner } },
            { owner -> access.open.update { it - owner } }, { epoch -> delivered.update { it + epoch } })
        try {
            rig.add(); rig.backupInput()
            navigation.open(ProductPage.TRANSFER)
            val route = navigation.route.value!!
            val epochs = mutableListOf<Long>()
            repeat(2) { index ->
                val preparation = if (index == 0) route.transfer!!.prepareRestore(route.owner) else {
                    val recovery = assertIs<OperationResult.Completed<RecoveryInfo?>>(rig.runtime.library.recovery()).value!!
                    route.transfer!!.prepareReturnToRecovery(route.owner, recovery.id)
                }
                val operation = assertIs<OperationResult.Completed<OperationId>>(preparation).value
                val confirmation = if (index == 0) TransferConfirmation.ReplaceWholeLibrary else TransferConfirmation.ReturnToRecovery
                val confirmed = route.transfer!!.confirm(route.owner, operation, null, confirmation)
                assertIs<OperationResult.Completed<Unit>>(confirmed, "restore $index: $confirmed")
                epochs += rig.runtime.library.listing.value.epoch
                withContext(Dispatchers.Default) { withTimeout(10_000) { delivered.first { it.size == index + 1 } } }
            }
            navigation.open(ProductPage.LIBRARY)
            assertEquals(epochs, delivered.value)
            assertTrue(epochs[1] > epochs[0])
        } finally { navigation.open(ProductPage.COMMUNICATION); rig.clean() }
    }
}
