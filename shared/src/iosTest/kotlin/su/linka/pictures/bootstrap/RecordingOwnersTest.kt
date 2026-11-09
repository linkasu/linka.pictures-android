@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package su.linka.pictures.bootstrap

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId
import kotlin.test.*

class RecordingOwnersTest {
    @Test fun explicitStopInvalidatesPermissionCallbackBeforeCallerCoroutineResumes() = runTest {
        val result = CompletableDeferred<OperationResult<TemporaryFile>>()
        val native = object : AudioPort {
            override val state = MutableStateFlow(AudioState())
            override val voices = MutableStateFlow(VoiceCapability(VoiceStatus.INITIALIZING))
            override suspend fun record(request: RecordingRequest) = result.await()
            override fun stop(ownerId: String, requestId: RequestId?) { result.complete(OperationResult.Cancelled) }
            override fun interrupt() { error("Not used") }
            override fun play(request: PlaybackRequest) { error("Not used") }
            override suspend fun synthesize(request: SynthesisRequest): OperationResult<TemporaryFile> = error("Not used")
            override fun finishRecording(ownerId: String, requestId: RequestId) { error("Not used") }
        }
        val owners = RecordingOwners({ error("A revoked permission callback must not query UIKit/AVAudioSession") },
            { error("No native permission prompt is authorized by this test") }, { null })
        val audio = owners.bind(native)
        val id = RequestId("owned-recording")
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            assertEquals(OperationResult.Cancelled, audio.record(RecordingRequest(id, AudioOwner("owner", AudioPurpose.RECORDING))))
        }
        audio.stop("owner", id)
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try { assertEquals(OperationResult.Cancelled, owners.permission(id)) }
        finally { Dispatchers.resetMain() }
        worker.join()
    }
}
