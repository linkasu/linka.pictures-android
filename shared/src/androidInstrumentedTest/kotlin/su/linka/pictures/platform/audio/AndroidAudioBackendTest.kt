package su.linka.pictures.platform.audio

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId
import su.linka.pictures.platform.capabilities.AndroidMediaInspectionPort
import su.linka.pictures.platform.capabilities.TinyMediaFixtures
import java.util.UUID
import java.io.File

class AndroidAudioBackendTest {
    private val owner = AudioOwner("instrumented-test", AudioPurpose.RECORDING)
    private fun request(id: String) = RecordingRequest(RequestId(id), owner)
    private class Fixture(permission: suspend (RequestId) -> OperationResult<Unit>) {
        val files = OwnedAudioTestFiles()
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, error -> failures += error })
        val backend = AndroidAudioBackend(InstrumentationRegistry.getInstrumentation().targetContext, files, AndroidMediaInspectionPort(), permission, scope)
        suspend fun close() {
            scope.cancel()
            withContext(Dispatchers.Main) { }
            assertTrue(failures.isEmpty())
            files.close()
        }
    }

    @Test fun permissionDeniedAndCancelledNeverCreateRecordingOrEmitReady() = runBlocking {
        for (permission in listOf(OperationResult.Cancelled, OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED)))) {
            val f = Fixture { permission }
            try {
                val phases = mutableListOf<AudioPhase>()
                val result = f.backend.record(request("negative")) { phases += it }
                assertEquals(permission, result)
                assertEquals(listOf(AudioPhase.REQUESTING_PERMISSION), phases)
                assertTrue(f.files.created.isEmpty())
            } finally { f.close() }
        }
    }
    @Test fun syntheticPermissionSuccessDoesNotBypassActualOsPermission() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals("This negative test must never run a granted microphone", PackageManager.PERMISSION_DENIED,
            app.packageManager.checkPermission(Manifest.permission.RECORD_AUDIO, app.packageName))
        val f = Fixture { OperationResult.Completed(Unit) }
        try {
            val result = f.backend.record(request("os-denied")) { }
            assertEquals(ProblemCode.PERMISSION_DENIED, (result as OperationResult.Failed).problem.code)
            assertTrue(f.files.created.isEmpty())
        } finally { f.close() }
    }
    @Test fun exactStopCancelsPermissionWaitAndOldIdDoesNotCancelNextRequest() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val permission = CompletableDeferred<OperationResult<Unit>>()
        val f = Fixture { entered.complete(Unit); permission.await() }
        try {
            val phases = mutableListOf<AudioPhase>()
            val recording = async { f.backend.record(request("old")) { phases += it } }
            withTimeout(5000) { entered.await() }
            f.backend.stop(RequestId("foreign"))
            withContext(Dispatchers.Main) { }
            assertFalse(recording.isCompleted)
            f.backend.stop(RequestId("old"))
            assertEquals(OperationResult.Cancelled, withTimeout(5000) { recording.await() })
            permission.complete(OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED)))
            val next = async { f.backend.record(request("new")) { phases += it } }
            f.backend.stop(RequestId("old"))
            assertEquals(ProblemCode.PERMISSION_DENIED, (withTimeout(5000) { next.await() } as OperationResult.Failed).problem.code)
            assertTrue(phases.all { it == AudioPhase.REQUESTING_PERMISSION })
            assertTrue(f.files.created.isEmpty())
        } finally { f.close() }
    }
    @Test fun callerAndParentCancellationCleanPendingPermissionWithoutMicOrLateReady() = runBlocking {
        for (parent in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val permission = CompletableDeferred<OperationResult<Unit>>()
            val f = Fixture { entered.complete(Unit); permission.await() }
            try {
                val phases = mutableListOf<AudioPhase>()
                val pending = async { f.backend.record(request("pending")) { phases += it } }
                withTimeout(5000) { entered.await() }
                if (parent) {
                    f.scope.cancel()
                    assertEquals(OperationResult.Cancelled, withTimeout(5000) { pending.await() })
                } else { pending.cancel(); withTimeout(5000) { pending.join() }; assertTrue(pending.isCancelled) }
                permission.complete(OperationResult.Completed(Unit))
                withContext(Dispatchers.Main) { }
                assertEquals(listOf(AudioPhase.REQUESTING_PERMISSION), phases)
                assertTrue(f.files.created.isEmpty())
            } finally { f.close() }
        }
    }
    @Test fun overlapIsRejectedRatherThanCreatingAnotherNativeRequestQueue() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val f = Fixture { entered.complete(Unit); awaitCancellation() }
        try {
            val old = async { f.backend.record(request("old")) { } }
            withTimeout(5000) { entered.await() }
            assertEquals(ProblemCode.CONFLICT, (f.backend.record(request("overlap")) { } as OperationResult.Failed).problem.code)
            f.backend.stop(RequestId("old"))
            assertEquals(OperationResult.Cancelled, withTimeout(5000) { old.await() })
            assertTrue(f.files.created.isEmpty())
        } finally { f.close() }
    }
    @Test fun explicitlyMissingVoiceHasUnavailableOutcomeNotPositiveTtsProof() = runBlocking {
        val f = Fixture { error("TTS must not request microphone permission") }
        try {
            val voice = "__not_installed_${UUID.randomUUID()}"
            val result = withTimeout(15000) { f.backend.speak("test", null, voice, RequestId("missing-voice")) }
            assertEquals(ProblemCode.VOICE_UNAVAILABLE, (result as OperationResult.Failed).problem.code)
            assertFalse(f.backend.voices.value.voices.any { it.id == voice })
            assertTrue(f.files.created.isEmpty())
        } finally { f.close() }
    }
    @Test fun synthesisOverPlatformLimitNeverCreatesTempOrCallsPermission() = runBlocking {
        val f = Fixture { error("Synthesis must not request microphone permission") }
        try {
            val result = f.backend.synthesize(SynthesisRequest(RequestId("over-limit"), AudioOwner("test", AudioPurpose.SYNTHESIS), "x".repeat(android.speech.tts.TextToSpeech.getMaxSpeechInputLength() + 1), null))
            assertEquals(ProblemCode.LIMIT_EXCEEDED, (result as OperationResult.Failed).problem.code)
            assertTrue(f.files.created.isEmpty())
        } finally { f.close() }
    }
    @Test fun inspectClipUsesActualInjectedInspectorAndDoesNotPlayOrRewriteSource() = runBlocking {
        val f = Fixture { error("Inspection must not request permission") }
        try {
            val file = f.files.file(TinyMediaFixtures.wave())
            assertEquals(OperationResult.Completed(Unit), f.backend.inspectClip(file))
            assertTrue(f.files.discarded.isEmpty())
        } finally { f.close() }
    }
    @Test fun validClipAwaitsActualMediaPlayerTerminalCallback() = runBlocking {
        val f = Fixture { error("Clip playback must not request microphone") }
        try {
            val file = f.files.file(TinyMediaFixtures.wave())
            // No Assume/focus bypass: foreground/focus/codec failure is a real failing native gate.
            assertEquals(OperationResult.Completed(Unit), withTimeout(15000) { f.backend.playClip(file, RequestId("clip")) })
            assertTrue(f.files.discarded.isEmpty())
        } finally { f.close() }
    }
    @Test fun unexpectedNonemptyTemporaryIsRejectedWithoutTakingCleanupOwnership() = runBlocking {
        val f = Fixture { error("This test must never request microphone permission") }
        try {
            val before = byteArrayOf(1, 2, 3, 4)
            f.files.initialBytes = before
            val result = f.backend.synthesize(SynthesisRequest(RequestId("nonempty"), AudioOwner("test", AudioPurpose.SYNTHESIS), "text", null))
            assertEquals(ProblemCode.CONFLICT, (result as OperationResult.Failed).problem.code)
            val file = f.files.created.single()
            assertTrue(File(file.path).isFile)
            assertArrayEquals(before, File(file.path).readBytes())
            assertTrue(f.files.discarded.isEmpty())
        } finally { f.close() } // Only the fixture owner may delete its prior contents after the assertions.
    }
}
