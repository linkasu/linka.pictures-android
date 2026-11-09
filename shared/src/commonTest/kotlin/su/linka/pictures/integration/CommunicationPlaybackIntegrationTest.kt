package su.linka.pictures.integration

import su.linka.pictures.testing.testSQLiteDriver
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import su.linka.pictures.application.*
import su.linka.pictures.application.communication.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.audio.AudioCoordinator
import su.linka.pictures.audio.AudioFilesFake
import su.linka.pictures.audio.NativeAudioFake
import su.linka.pictures.domain.*
import su.linka.pictures.storage.StorageRuntime
import su.linka.pictures.storage.token

class CommunicationPlaybackIntegrationTest {
    private fun <T> OperationResult<T>.ok(): T = assertIs<OperationResult.Completed<T>>(this).value

    @Test fun crossBoardRecordingSnapshotsPlayStopAndReplayThroughActualStorage() = runTest {
        val fs = FileSystem.SYSTEM
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "linka-communication-playback-${token()}"
        fs.createDirectory(root, mustCreate = true)
        var storage: StorageRuntime? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = StorageRuntime.open(root, fs, testSQLiteDriver()).ok().also { storage = it }
            for ((name, label) in listOf("want" to "ХОЧУ", "food" to "КУШАТЬ")) {
                val prepared = runtime.media.prepare(Buffer().writeUtf8(name), MediaKind.AUDIO, "audio/test").ok()
                try {
                    val board = BoardDocument(BoardId(name), label, listOf(BoardPage(PageId(name), 1, 1, listOf(
                        BoardCell(0, CellKind.CONTENT, CardDocument(CardId(name), label,
                            speech = SpeechSource.Recording(prepared.asset.id))),
                    ))))
                    runtime.library.addBoard(board, WritePermit(0) { OperationResult.Completed(Unit) }).ok()
                } finally { prepared.lease.close() }
            }
            // Only the native audio endpoint is fake: session, coordinator, SQLite and media proofs are real.
            val backend = NativeAudioFake()
            val audio = AudioCoordinator(backend, runtime.media, AudioFilesFake(), scope)
            val serial = MutableStateFlow(0)
            fun next() = "communication-playback-${serial.updateAndGet { it + 1 }}"
            val session = CommunicationSession(runtime.library, runtime.messages, runtime.media, audio, IdSource { next() }, scope)
            suspend fun awaitState(predicate: (CommunicationState) -> Boolean) = withContext(Dispatchers.Default) {
                withTimeout(10_000) { session.state.first(predicate) }
            }
            awaitState { it.savePhase == MessageSavePhase.SAVED }
            for ((name, phrase) in listOf("want" to "ХОЧУ", "food" to "ХОЧУ КУШАТЬ")) {
                session.dispatch(CommunicationCommand.OpenBoard(CommandId(next()), BoardId(name)))
                awaitState { it.board?.document?.id == BoardId(name) && it.savePhase == MessageSavePhase.SAVED }
                session.dispatch(CommunicationCommand.Select(CommandId(next()), PageId(name), 0))
                awaitState { it.renderedText == phrase && it.savePhase == MessageSavePhase.SAVED }
            }
            val persisted = runtime.messages.message().ok()
            suspend fun playToCompletion() {
                session.dispatch(CommunicationCommand.Speak(CommandId(next())))
                val finished = awaitState { it.audio.phase in setOf(AudioPhase.COMPLETED, AudioPhase.FAILED, AudioPhase.INTERRUPTED) }
                assertEquals(AudioPhase.COMPLETED, finished.audio.phase, "${finished.audio.problem}")
                assertNull(finished.problem)
                assertEquals(persisted, runtime.messages.message().ok())
            }
            playToCompletion()
            assertEquals(listOf("want", "food"), backend.inspected.map { file ->
                fs.source(file.path.toPath()).buffer().use { it.readUtf8() }
            })

            val started = CompletableDeferred<Unit>()
            backend.audible = { started.complete(Unit); awaitCancellation() }
            session.dispatch(CommunicationCommand.Speak(CommandId(next())))
            withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }
            session.dispatch(CommunicationCommand.Stop(CommandId(next())))
            awaitState { it.audio.phase == AudioPhase.IDLE }
            backend.audible = { OperationResult.Completed(Unit) }
            playToCompletion()
            assertTrue(backend.stopped.isNotEmpty())
            assertEquals(1, backend.peakNativeActive)
            assertEquals(0, runtime.media.collectGarbage().ok().removedAssets)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            storage?.close()
            fs.deleteRecursively(root)
        }
    }
}
