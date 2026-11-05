package su.linka.pictures.audio

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class AudioCoordinatorTest {
    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun next() { tasks.removeFirst().run() }
        val pending get() = tasks.size
    }
    private class Fixture(val test: TestScope) {
        val backend = NativeAudioFake()
        val media = AudioMediaFake()
        val files = AudioFilesFake()
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler) + CoroutineExceptionHandler { _, failure -> uncaught += failure })
        val coordinator = AudioCoordinator(backend, media, files, scope)
        val owner = AudioOwner("communication", AudioPurpose.COMMUNICATION)
        val asset = AssetId("a".repeat(64))
        val clip = LocalFile("immutable")
        init { media.clips[asset] = clip }
        fun play(id: String = "play", segments: List<SpeechSegment> = listOf(SpeechSegment.Text("текст")), preferred: String? = null) {
            coordinator.play(PlaybackRequest(RequestId(id), owner, SpeechPlan(segments, "visible"), media.Lease(setOf(asset)), preferred))
            test.runCurrent()
        }
        fun record(id: String = "record") = test.async { coordinator.record(RecordingRequest(RequestId(id), AudioOwner("editor", AudioPurpose.RECORDING))) }
        fun synth(id: String = "synth", text: String = "текст", language: String? = null, voice: String? = null) = test.async {
            coordinator.synthesize(SynthesisRequest(RequestId(id), AudioOwner("editor", AudioPurpose.SYNTHESIS), text, language, voice))
        }
        fun close() {
            scope.cancel(); test.runCurrent()
            assertTrue(uncaught.isEmpty(), "No cleanup exception may escape the coordinator")
        }
    }

    @Test fun malformedOrNulSpeechNeverStartsAnySegmentOrSynthesis() = runTest {
        for (value in listOf("left\u0000right", "left\uD800right", "left\uDC00right")) {
            val f = Fixture(this)
            f.play(segments = listOf(SpeechSegment.Recording(f.asset), SpeechSegment.Text(value)))
            assertEquals(ProblemCode.INVALID_DATA, f.coordinator.state.value.problem?.code)
            assertTrue(f.backend.calls.isEmpty())
            assertTrue(f.backend.inspected.isEmpty())
            assertEquals(0, f.media.open)
            val synthesis = f.synth(text = value)
            runCurrent()
            assertEquals(ProblemCode.INVALID_DATA, assertIs<OperationResult.Failed>(synthesis.await()).problem.code)
            assertTrue(f.backend.calls.isEmpty())
            assertTrue(f.files.discarded.isEmpty())
            assertEquals(ProblemCode.INVALID_DATA, assertIs<OperationResult.Failed>(speechChunks(value, 4000)).problem.code)
            f.close()
        }
    }

    @Test fun mixedPlanIsFullyInspectedThenPlayedInOrderWithoutFallbackOrWhitespaceSpeech() = runTest {
        val f = Fixture(this)
        f.backend.audible = { assertEquals(listOf(f.clip), f.backend.inspected); OperationResult.Completed(Unit) }
        f.play(segments = listOf(SpeechSegment.Text("  "), SpeechSegment.Text("Хочу", "ru-RU"), SpeechSegment.Recording(f.asset), SpeechSegment.Text(" мёд ")))
        assertEquals(listOf("tts:Хочу:ru-RU:null", "clip:immutable", "tts: мёд :null:null"), f.backend.calls)
        assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
        assertEquals(0, f.media.open)
        assertTrue(f.files.discarded.isEmpty())
        f.close()
    }

    @Test fun everyPreflightRefusalPreventsAllAudibleCallsAndReleasesLease() = runTest {
        for (failure in listOf("missing", "clip", "voice", "language", "preferred", "maximum", "oversized", "unknown", "cancel")) {
            val f = Fixture(this)
            var text = "текст"
            var language: String? = null
            var preferred: String? = null
            when (failure) {
                "missing" -> f.media.clips.clear()
                "clip" -> f.backend.inspect = { OperationResult.Failed(Problem(ProblemCode.UNSUPPORTED_MEDIA)) }
                "voice" -> f.backend.voices.value = VoiceCapability(VoiceStatus.MISSING_VOICE)
                "language" -> language = "zz-ZZ"
                "preferred" -> preferred = "not-installed"
                "maximum" -> f.backend.voices.value = f.backend.voices.value.copy(maxTextLength = 0)
                "oversized" -> { text = "👩🏽‍💻"; f.backend.voices.value = f.backend.voices.value.copy(maxTextLength = 2) }
                "unknown" -> f.media.failure = OperationResult.UnconfirmedCommit
                "cancel" -> f.backend.inspect = { OperationResult.Cancelled }
            }
            f.play(segments = listOf(SpeechSegment.Text(text, language), SpeechSegment.Recording(f.asset)), preferred = preferred)
            assertTrue(f.backend.calls.isEmpty(), failure)
            assertEquals(0, f.media.open, failure)
            if (failure == "unknown") assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN, f.coordinator.state.value.problem?.code)
            else if (failure == "cancel") assertEquals(AudioPhase.CANCELLED, f.coordinator.state.value.phase)
            else assertEquals(AudioPhase.FAILED, f.coordinator.state.value.phase, failure)
            f.close()
        }
    }

    @Test fun recordingOnlyAndEmptyPlansDoNotRequireVoice() = runTest {
        val f = Fixture(this)
        f.backend.voices.value = VoiceCapability(VoiceStatus.MISSING_VOICE, maxTextLength = -1)
        f.play(segments = listOf(SpeechSegment.Recording(f.asset)))
        f.play("empty", emptyList())
        f.play("spaces", listOf(SpeechSegment.Text("\n \t")))
        assertEquals(listOf("clip:immutable"), f.backend.calls)
        assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
        assertEquals(0, f.media.open)
        f.close()
    }

    @Test fun chunksPreserveAllTextAndUnicode16ClustersWithinStrictUtf16Maximum() = runTest {
        val f = Fixture(this)
        val text = "  е\u0301🇷🇺👩🏽‍💻क्षabc  "
        f.backend.voices.value = f.backend.voices.value.copy(maxTextLength = 7)
        f.play(segments = listOf(SpeechSegment.Text(text)))
        val chunks = f.backend.calls.map { it.removePrefix("tts:").removeSuffix(":null:null") }
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 7 })
        assertTrue(chunks.any { "👩🏽‍💻" in it })
        assertTrue(chunks.any { "🇷🇺" in it })
        assertTrue(chunks.any { "क्ष" in it })
        assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
        f.close()
    }

    @Test fun voiceChangeDuringPreflightFailsWholePlanAndDuringPlaybackInterrupts() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.backend.inspect = { gate.await(); OperationResult.Completed(Unit) }
        f.play(segments = listOf(SpeechSegment.Recording(f.asset), SpeechSegment.Text("текст")))
        f.backend.voices.value = f.backend.voices.value.copy(maxTextLength = 20)
        gate.complete(Unit); runCurrent()
        assertTrue(f.backend.calls.isEmpty())
        assertEquals(ProblemCode.VOICE_UNAVAILABLE, f.coordinator.state.value.problem?.code)
        val speaking = CompletableDeferred<OperationResult<Unit>>()
        f.backend.audible = { speaking.await() }
        f.play("next")
        assertEquals(AudioPhase.PLAYING, f.coordinator.state.value.phase)
        f.backend.voices.value = f.backend.voices.value.copy(status = VoiceStatus.INTERRUPTED); runCurrent()
        assertEquals(AudioPhase.INTERRUPTED, f.coordinator.state.value.phase)
        assertEquals(ProblemCode.INTERRUPTED, f.coordinator.state.value.problem?.code)
        assertEquals(1, f.backend.calls.size)
        f.close()
    }

    @Test fun exactOwnerAndRequestStopCannotStopForeignOrNewAudio() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<OperationResult<Unit>>()
        f.backend.audible = { gate.await() }
        f.play("old")
        f.coordinator.stop("foreign", RequestId("old"))
        f.coordinator.stop(f.owner.id, RequestId("new")); runCurrent()
        assertTrue(f.backend.stopped.isEmpty())
        f.play("new")
        assertEquals(listOf(RequestId("old")), f.backend.stopped)
        f.coordinator.stop(f.owner.id, RequestId("old")); runCurrent()
        assertEquals(AudioPhase.PLAYING, f.coordinator.state.value.phase)
        f.coordinator.stop(f.owner.id, RequestId("new")); runCurrent()
        assertEquals(AudioPhase.CANCELLED, f.coordinator.state.value.phase)
        assertEquals(0, f.media.open)
        f.close()
    }

    @Test fun rapidReplacementHasOneNoncooperativeNativeJobAndOnlyLatestPendingLease() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.backend.audible = { id ->
            if (id == RequestId("old")) withContext(NonCancellable) { gate.await() }
            OperationResult.Completed(Unit)
        }
        f.play("old")
        repeat(500) { f.play("new-$it"); assertTrue(f.media.open <= 2) }
        assertEquals(1, f.backend.calls.size)
        assertEquals(1, f.backend.nativeActive)
        gate.complete(Unit); runCurrent()
        assertEquals(2, f.backend.calls.size)
        assertEquals(RequestId("new-499"), f.coordinator.state.value.requestId)
        assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
        assertEquals(1, f.backend.peakNativeActive)
        assertEquals(0, f.media.open)
        f.close()
    }

    @Test fun duplicateIdsConsumeEveryLeaseWithoutRepeatedAudioOrReplacingCurrent() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<OperationResult<Unit>>()
        f.backend.audible = { gate.await() }
        repeat(20) { f.play("same") }
        assertEquals(1, f.backend.calls.size)
        assertEquals(1, f.media.open)
        gate.complete(OperationResult.Completed(Unit)); runCurrent()
        f.play("same")
        assertEquals(1, f.backend.calls.size)
        assertEquals(0, f.media.open)
        f.close()
    }

    @Test fun recordPhasesFinishExactIdAndValidatedFileTransfersToCaller() = runTest {
        val f = Fixture(this)
        val result = CompletableDeferred<OperationResult<TemporaryFile>>()
        val file = TemporaryFile("recorded")
        f.backend.recording = { _, callback ->
            callback(AudioPhase.READY)
            callback(AudioPhase.RECORDING)
            result.await()
        }
        val request = f.record(); runCurrent()
        assertEquals(AudioPhase.RECORDING, f.coordinator.state.value.phase)
        f.coordinator.finishRecording("foreign", RequestId("record"))
        f.coordinator.finishRecording("editor", RequestId("other")); runCurrent()
        assertTrue(f.backend.finished.isEmpty())
        f.coordinator.finishRecording("editor", RequestId("record")); runCurrent()
        assertEquals(listOf(RequestId("record")), f.backend.finished)
        assertEquals(AudioPhase.FINISHING, f.coordinator.state.value.phase)
        result.complete(OperationResult.Completed(file)); runCurrent()
        assertEquals(file, assertIs<OperationResult.Completed<*>>(request.await()).value)
        assertEquals(listOf<LocalFile>(file), f.backend.inspected)
        assertEquals(AudioPhase.READY, f.coordinator.state.value.phase)
        f.close()
        assertTrue(f.files.discarded.isEmpty())
    }

    @Test fun explicitStopAndInterruptHaveDifferentSuspendingResults() = runTest {
        for (interrupt in listOf(false, true)) {
            val f = Fixture(this)
            f.backend.recording = { _, _ -> awaitCancellation() }
            val request = f.record(); runCurrent()
            if (interrupt) f.coordinator.interrupt() else f.coordinator.stop("editor", RequestId("record"))
            runCurrent()
            if (interrupt) {
                assertEquals(ProblemCode.INTERRUPTED, assertIs<OperationResult.Failed>(request.await()).problem.code)
                assertEquals(AudioPhase.INTERRUPTED, f.coordinator.state.value.phase)
            } else {
                assertEquals(OperationResult.Cancelled, request.await())
                assertEquals(AudioPhase.CANCELLED, f.coordinator.state.value.phase)
            }
            f.close()
        }
    }

    @Test fun permissionDeniedAndValidationFailureNeverBecomeReadyAndOnlyTempIsDiscarded() = runTest {
        val f = Fixture(this)
        f.backend.recording = { _, _ -> OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED)) }
        val denied = f.record(); runCurrent()
        assertEquals(ProblemCode.PERMISSION_DENIED, assertIs<OperationResult.Failed>(denied.await()).problem.code)
        val temp = TemporaryFile("bad-synthesis")
        f.backend.synthesis = { OperationResult.Completed(temp) }
        f.backend.inspect = { OperationResult.Failed(Problem(ProblemCode.INVALID_DATA)) }
        val bad = f.synth(); runCurrent()
        assertEquals(ProblemCode.INVALID_DATA, assertIs<OperationResult.Failed>(bad.await()).problem.code)
        assertEquals(listOf(temp), f.files.discarded)
        assertEquals(AudioPhase.FAILED, f.coordinator.state.value.phase)
        f.close()
    }

    @Test fun lateNoncooperativeRecordFileAndPhaseCannotAffectNewOwnerAndAreDiscarded() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        val temp = TemporaryFile("late-record")
        var callback: ((AudioPhase) -> Unit)? = null
        f.backend.recording = { _, phase ->
            callback = phase
            withContext(NonCancellable) { gate.await(); phase(AudioPhase.RECORDING); OperationResult.Completed(temp) }
        }
        val old = f.record(); runCurrent()
        f.play("new")
        assertEquals(OperationResult.Cancelled, old.await())
        callback!!(AudioPhase.FINISHING); runCurrent()
        assertEquals(RequestId("new"), f.coordinator.state.value.requestId)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(temp), f.files.discarded)
        assertEquals(listOf("record", "tts:текст:null:null"), f.backend.calls)
        assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
        f.close()
    }

    @Test fun parentCancellationCleansPendingLeasesAndLateReturnedTempEvenAfterActorStops() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        val temp = TemporaryFile("late-after-close")
        f.backend.synthesis = { withContext(NonCancellable) { gate.await(); OperationResult.Completed(temp) } }
        val request = f.synth(); runCurrent()
        f.play("pending")
        f.close()
        assertEquals(0, f.media.open)
        assertEquals(OperationResult.Cancelled, request.await())
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(temp), f.files.discarded)
        assertEquals(0, f.backend.nativeActive)
    }

    @Test fun alreadyCancelledParentRejectsNewRequestsAndDisposesPlaybackLease() = runTest {
        val backend = NativeAudioFake()
        val media = AudioMediaFake()
        val files = AudioFilesFake()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        scope.cancel()
        val coordinator = AudioCoordinator(backend, media, files, scope)
        coordinator.play(PlaybackRequest(RequestId("play"), AudioOwner("owner", AudioPurpose.DIRECT), SpeechPlan(emptyList(), ""), media.Lease(emptySet())))
        assertEquals(OperationResult.Cancelled, coordinator.record(RecordingRequest(RequestId("record"), AudioOwner("editor", AudioPurpose.RECORDING))))
        runCurrent()
        assertEquals(0, media.open)
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun cancellationWhileInspectingReturnedFileDiscardsItWithoutReady() = runTest {
        val f = Fixture(this)
        val temp = TemporaryFile("inspecting")
        val inspecting = CompletableDeferred<Unit>()
        f.backend.synthesis = { OperationResult.Completed(temp) }
        f.backend.inspect = { inspecting.await(); OperationResult.Completed(Unit) }
        val request = f.synth(); runCurrent()
        request.cancel(); runCurrent()
        assertEquals(listOf(temp), f.files.discarded)
        assertEquals(AudioPhase.CANCELLED, f.coordinator.state.value.phase)
        f.close()
    }

    @Test fun backendFailureExceptionAndInterruptedOutcomeHaveNoRetry() = runTest {
        for (kind in listOf("failure", "exception", "interrupt", "cancel", "unknown")) {
            val f = Fixture(this)
            f.backend.audible = { when (kind) {
                "failure" -> OperationResult.Failed(Problem(ProblemCode.UNSUPPORTED_MEDIA))
                "exception" -> error("private engine exception")
                "interrupt" -> OperationResult.Failed(Problem(ProblemCode.INTERRUPTED))
                "unknown" -> OperationResult.UnconfirmedCommit
                else -> OperationResult.Cancelled
            } }
            f.play(segments = listOf(SpeechSegment.Text("первый"), SpeechSegment.Text("второй")))
            assertEquals(1, f.backend.calls.size)
            assertEquals(0, f.media.open)
            assertEquals(when (kind) { "interrupt" -> AudioPhase.INTERRUPTED; "cancel" -> AudioPhase.CANCELLED; else -> AudioPhase.FAILED }, f.coordinator.state.value.phase)
            f.close()
        }
    }

    @Test fun cancellationAfterValidatedResumeButBeforeCallerDispatchDiscardsTemp() = runTest {
        val f = Fixture(this)
        val paused = PausedDispatcher()
        val caller = CoroutineScope(SupervisorJob() + paused)
        val file = TemporaryFile("validated-not-delivered")
        f.backend.synthesis = { OperationResult.Completed(file) }
        val request = caller.async {
            f.coordinator.synthesize(SynthesisRequest(RequestId("synth"), AudioOwner("editor", AudioPurpose.SYNTHESIS), "текст", null))
        }
        paused.next()
        runCurrent()
        assertEquals(AudioPhase.READY, f.coordinator.state.value.phase)
        assertEquals(1, paused.pending)
        request.cancel()
        paused.next(); runCurrent()
        assertEquals(listOf(file), f.files.discarded)
        assertEquals(AudioPhase.CANCELLED, f.coordinator.state.value.phase)
        caller.cancel()
        f.close()
    }

    @Test fun successfulSynthesisOwnershipSurvivesLaterCallerAndCoordinatorCancellation() = runTest {
        val f = Fixture(this)
        assertSame(f.backend.voices, f.coordinator.voices)
        val file = TemporaryFile("delivered")
        f.backend.synthesis = { request ->
            assertEquals("ru", request.preferredVoiceId)
            assertEquals("ru-RU", request.languageTag)
            assertEquals(" текст ", request.text)
            OperationResult.Completed(file)
        }
        val request = f.synth(text = " текст ", language = "ru-RU", voice = "ru"); runCurrent()
        assertSame(file, assertIs<OperationResult.Completed<*>>(request.await()).value)
        request.cancel()
        f.close()
        assertTrue(f.files.discarded.isEmpty())
    }

    @Test fun synthesisRejectsMissingVoiceAndBlankTextAndPropagatesUnknownWithoutSuccess() = runTest {
        for (kind in listOf("voice", "blank", "unknown", "cancel")) {
            val f = Fixture(this)
            f.backend.synthesis = { if (kind == "unknown") OperationResult.UnconfirmedCommit else OperationResult.Cancelled }
            val request = f.synth(text = if (kind == "blank") " \n" else "текст", voice = if (kind == "voice") "missing" else null)
            runCurrent()
            when (kind) {
                "unknown" -> {
                    assertEquals(OperationResult.UnconfirmedCommit, request.await())
                    assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN, f.coordinator.state.value.problem?.code)
                }
                "cancel" -> assertEquals(OperationResult.Cancelled, request.await())
                else -> { assertIs<OperationResult.Failed>(request.await()); assertTrue(f.backend.calls.isEmpty()) }
            }
            f.close()
        }
    }

    @Test fun pendingRequestCancelledBeforeWorkerStartsHasNoBackendCallAndNoLeaseLeak() = runTest {
        val f = Fixture(this)
        f.coordinator.play(PlaybackRequest(RequestId("pending"), f.owner, SpeechPlan(listOf(SpeechSegment.Text("текст")), ""), f.media.Lease(emptySet())))
        f.coordinator.stop(f.owner.id, RequestId("pending"))
        runCurrent()
        assertEquals(0, f.media.open)
        assertTrue(f.backend.calls.isEmpty())
        assertEquals(AudioPhase.CANCELLED, f.coordinator.state.value.phase)
        f.close()
    }

    @Test fun missingLeaseAssetDoesNotResolveImmutableFileAndCallerListIsSnapshotted() = runTest {
        val f = Fixture(this)
        f.play(segments = listOf(SpeechSegment.Recording(AssetId("b".repeat(64)))))
        assertEquals(ProblemCode.MISSING_ASSET, f.coordinator.state.value.problem?.code)
        val list = mutableListOf<SpeechSegment>(SpeechSegment.Text("original"))
        f.coordinator.play(PlaybackRequest(RequestId("snapshot"), f.owner, SpeechPlan(list, ""), f.media.Lease(emptySet())))
        list.clear(); list += SpeechSegment.Text("changed")
        runCurrent()
        assertEquals(listOf("tts:original:null:null"), f.backend.calls)
        f.close()
    }

    @Test fun chunkLimitsCannotWrapAndOversizedCombiningClusterIsRejected() {
        for (max in listOf(0, -1, Int.MIN_VALUE)) assertIs<OperationResult.Failed>(speechChunks("a", max))
        assertEquals(listOf("a"), assertIs<OperationResult.Completed<*>>(speechChunks("a", Int.MAX_VALUE)).value)
        assertIs<OperationResult.Failed>(speechChunks("е\u0301", 1))
        assertEquals(listOf("е\u0301"), assertIs<OperationResult.Completed<*>>(speechChunks("е\u0301", 2)).value)
    }

    @Test fun staleVoiceChangeCannotInterruptNewRecordingOwnerAndNewPreflightFollowsStop() = runTest {
        val f = Fixture(this)
        f.backend.audible = { awaitCancellation() }
        f.play("old")
        f.backend.recording = { _, phase -> phase(AudioPhase.RECORDING); awaitCancellation() }
        val recording = f.record(); runCurrent()
        f.backend.voices.value = VoiceCapability(VoiceStatus.MISSING_VOICE); runCurrent()
        assertEquals(AudioPhase.RECORDING, f.coordinator.state.value.phase)
        assertEquals(RequestId("record"), f.coordinator.state.value.requestId)
        f.backend.audible = { OperationResult.Completed(Unit) }
        f.backend.inspect = {
            assertTrue(RequestId("record") in f.backend.stopped)
            OperationResult.Completed(Unit)
        }
        f.play("new-clip", listOf(SpeechSegment.Recording(f.asset)))
        assertEquals(OperationResult.Cancelled, recording.await())
        assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
        f.close()
    }

    @Test fun cancellationOfRejectedDuplicateCannotCancelPreviouslyDeliveredReadyResult() = runTest {
        val f = Fixture(this)
        val file = TemporaryFile("original")
        f.backend.synthesis = { OperationResult.Completed(file) }
        val original = f.synth("same"); runCurrent()
        assertSame(file, assertIs<OperationResult.Completed<*>>(original.await()).value)
        val paused = PausedDispatcher()
        val caller = CoroutineScope(SupervisorJob() + paused)
        val duplicate = caller.async {
            f.coordinator.synthesize(SynthesisRequest(RequestId("same"), AudioOwner("editor", AudioPurpose.SYNTHESIS), "текст", null))
        }
        paused.next()
        duplicate.cancel()
        runCurrent()
        assertEquals(AudioPhase.READY, f.coordinator.state.value.phase)
        assertEquals(1, f.backend.calls.size)
        assertTrue(f.files.discarded.isEmpty())
        caller.cancel()
        f.close()
    }

    @Test fun everyReturnedTempValidationErrorDisposesOwnedFileAndPreservesOutcome() = runTest {
        for (kind in listOf("failure", "cancel", "unknown", "throw")) {
            val f = Fixture(this)
            val file = TemporaryFile("invalid-$kind")
            f.backend.synthesis = { OperationResult.Completed(file) }
            f.backend.inspect = { when (kind) {
                "failure" -> OperationResult.Failed(Problem(ProblemCode.UNSUPPORTED_MEDIA))
                "cancel" -> OperationResult.Cancelled
                "unknown" -> OperationResult.UnconfirmedCommit
                else -> error("private inspection exception")
            } }
            val request = f.synth(); runCurrent()
            val result = request.await()
            when (kind) {
                "cancel" -> assertEquals(OperationResult.Cancelled, result)
                "unknown" -> assertEquals(OperationResult.UnconfirmedCommit, result)
                else -> assertIs<OperationResult.Failed>(result)
            }
            assertNotEquals(AudioPhase.READY, f.coordinator.state.value.phase)
            assertEquals(listOf(file), f.files.discarded)
            f.close()
        }
    }

    @Test fun throwingStopStillCancelsOldJobAndNewIntentWaitsForItsTerminalResult() = runTest {
        for (noncooperative in listOf(false, true)) {
            val f = Fixture(this)
            val gate = CompletableDeferred<Unit>()
            var oldJob: Job? = null
            f.backend.audible = { id ->
                if (id == RequestId("old")) {
                    oldJob = currentCoroutineContext().job
                    if (noncooperative) withContext(NonCancellable) { gate.await() } else gate.await()
                }
                OperationResult.Completed(Unit)
            }
            f.backend.stopAction = { error("stop fault") }
            f.play("old"); f.play("new")
            assertTrue(oldJob!!.isCancelled)
            if (noncooperative) {
                assertEquals(1, f.backend.calls.size)
                assertEquals(2, f.media.open)
                gate.complete(Unit); runCurrent()
            }
            assertEquals(2, f.backend.calls.size)
            assertEquals(1, f.backend.peakNativeActive)
            assertEquals(RequestId("new"), f.coordinator.state.value.requestId)
            assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
            assertEquals(0, f.media.open)
            f.close()
        }
    }

    @Test fun throwingStopDoesNotReplaceExplicitCancelOrInterruptPrimaryResult() = runTest {
        for (interrupt in listOf(false, true)) {
            val f = Fixture(this)
            f.backend.recording = { _, _ -> awaitCancellation() }
            f.backend.stopAction = { error("stop fault") }
            val request = f.record(); runCurrent()
            if (interrupt) f.coordinator.interrupt() else f.coordinator.stop("editor", RequestId("record"))
            runCurrent()
            if (interrupt) {
                assertEquals(ProblemCode.INTERRUPTED, assertIs<OperationResult.Failed>(request.await()).problem.code)
                assertEquals(AudioPhase.INTERRUPTED, f.coordinator.state.value.phase)
                assertEquals(ProblemCode.INTERRUPTED, f.coordinator.state.value.problem?.code)
            } else {
                assertEquals(OperationResult.Cancelled, request.await())
                assertEquals(AudioPhase.CANCELLED, f.coordinator.state.value.phase)
                assertEquals(ProblemCode.IO_FAILURE, f.coordinator.state.value.problem?.code)
            }
            f.play("next")
            assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
            f.close()
        }
    }

    @Test fun throwingFinishRecordingFailsCallerCancelsNativeAndDiscardsLateFileWithoutReady() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        val file = TemporaryFile("late-after-finish-error")
        var native: Job? = null
        f.backend.recording = { _, callback ->
            native = currentCoroutineContext().job
            callback(AudioPhase.RECORDING)
            withContext(NonCancellable) { gate.await(); OperationResult.Completed(file) }
        }
        f.backend.finishAction = { error("finish fault") }
        val request = f.record(); runCurrent()
        f.coordinator.finishRecording("editor", RequestId("record")); runCurrent()
        assertEquals(ProblemCode.IO_FAILURE, assertIs<OperationResult.Failed>(request.await()).problem.code)
        assertEquals(AudioPhase.FAILED, f.coordinator.state.value.phase)
        assertTrue(native!!.isCancelled)
        f.play("new")
        assertEquals(listOf("record"), f.backend.calls)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(file), f.files.discarded)
        assertEquals(RequestId("new"), f.coordinator.state.value.requestId)
        assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
        f.close()
    }

    @Test fun throwingLeaseCloseStillWakesActorAndPreservesCompletedOrUnknownOutcome() = runTest {
        for (unknown in listOf(false, true)) {
            val f = Fixture(this)
            f.media.closeFailure = { error("lease fault") }
            if (unknown) f.media.failure = OperationResult.UnconfirmedCommit
            f.play(segments = listOf(SpeechSegment.Recording(f.asset)))
            assertEquals(1, f.media.closeAttempts)
            assertEquals(0, f.media.open)
            assertEquals(if (unknown) AudioPhase.FAILED else AudioPhase.COMPLETED, f.coordinator.state.value.phase)
            assertEquals(if (unknown) ProblemCode.COMMIT_OUTCOME_UNKNOWN else ProblemCode.IO_FAILURE, f.coordinator.state.value.problem?.code)
            f.media.failure = null
            f.play("next")
            assertEquals(RequestId("next"), f.coordinator.state.value.requestId)
            assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
            assertEquals(2, f.media.closeAttempts)
            f.close()
        }
    }

    @Test fun throwingPendingOrDuplicateLeaseCloseCannotBreakAdmissionOrReplacement() = runTest {
        val f = Fixture(this)
        f.media.closeFailure = { error("lease fault") }
        fun request(id: String) = PlaybackRequest(RequestId(id), f.owner, SpeechPlan(listOf(SpeechSegment.Text(id)), id), f.media.Lease(emptySet()))
        f.coordinator.play(request("old"))
        f.coordinator.play(request("new"))
        f.coordinator.play(request("old"))
        runCurrent()
        assertEquals(listOf("tts:new:null:null"), f.backend.calls)
        assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
        assertEquals(3, f.media.closeAttempts)
        assertEquals(0, f.media.open)
        f.close()
    }

    @Test fun shutdownAttemptsAllResourcesWhenStopLeaseAndLateDiscardThrow() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        val file = TemporaryFile("late-shutdown-error")
        var native: Job? = null
        f.backend.recording = { _, _ ->
            native = currentCoroutineContext().job
            withContext(NonCancellable) { gate.await(); OperationResult.Completed(file) }
        }
        f.backend.stopAction = { error("stop fault") }
        f.media.closeFailure = { error("lease fault") }
        f.files.discardAction = { error("discard fault") }
        val request = f.record(); runCurrent()
        f.play("pending")
        f.close()
        assertTrue(native!!.isCancelled)
        assertEquals(OperationResult.Cancelled, request.await())
        assertEquals(0, f.media.open)
        assertEquals(1, f.media.closeAttempts)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(file), f.files.discardAttempts)
        assertTrue(f.files.discarded.isEmpty())
        assertEquals(0, f.backend.nativeActive)
        assertTrue(f.uncaught.isEmpty())
    }

    @Test fun lateCompletedOutcomeDiscardFailureDoesNotStrandNewIntentOrCaller() = runTest {
        val f = Fixture(this)
        val file = TemporaryFile("stale-completed")
        f.files.discardAction = { error("discard fault") }
        f.backend.synthesis = {
            currentCoroutineContext().job.invokeOnCompletion {
                f.coordinator.play(PlaybackRequest(RequestId("new"), f.owner, SpeechPlan(listOf(SpeechSegment.Text("new")), ""), f.media.Lease(emptySet())))
            }
            OperationResult.Completed(file)
        }
        val old = f.synth(); runCurrent()
        assertEquals(OperationResult.Cancelled, old.await())
        assertEquals(listOf(file), f.files.discardAttempts)
        assertTrue(f.files.discarded.isEmpty())
        assertEquals(RequestId("new"), f.coordinator.state.value.requestId)
        assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
        assertEquals(0, f.media.open)
        f.close()
    }

    @Test fun cancellationDiscardFailureCannotEscapeContinuationHandlerOrHangNextRequest() = runTest {
        val f = Fixture(this)
        val paused = PausedDispatcher()
        val failures = mutableListOf<Throwable>()
        val caller = CoroutineScope(SupervisorJob() + paused + CoroutineExceptionHandler { _, failure -> failures += failure })
        val file = TemporaryFile("undelivered-cleanup-fault")
        f.backend.synthesis = { OperationResult.Completed(file) }
        f.files.discardAction = { error("discard fault") }
        val request = caller.async {
            f.coordinator.synthesize(SynthesisRequest(RequestId("synth"), AudioOwner("editor", AudioPurpose.SYNTHESIS), "text", null))
        }
        paused.next(); runCurrent()
        request.cancel()
        paused.next(); runCurrent()
        assertEquals(listOf(file), f.files.discardAttempts)
        assertEquals(AudioPhase.CANCELLED, f.coordinator.state.value.phase)
        assertEquals(ProblemCode.IO_FAILURE, f.coordinator.state.value.problem?.code)
        assertTrue(failures.isEmpty())
        f.play("new")
        assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
        caller.cancel(); f.close()
    }

    @Test fun validationPrimaryFailureAndUnknownSurviveThrowingDiscardWithoutFalseReady() = runTest {
        for (kind in listOf("failed", "cancelled", "unknown")) {
            val f = Fixture(this)
            val file = TemporaryFile("bad-$kind")
            f.backend.synthesis = { OperationResult.Completed(file) }
            f.backend.inspect = { when (kind) {
                "failed" -> OperationResult.Failed(Problem(ProblemCode.UNSUPPORTED_MEDIA))
                "cancelled" -> OperationResult.Cancelled
                else -> OperationResult.UnconfirmedCommit
            } }
            f.files.discardAction = { error("discard fault") }
            val request = f.synth(); runCurrent()
            when (kind) {
                "failed" -> assertEquals(ProblemCode.UNSUPPORTED_MEDIA, assertIs<OperationResult.Failed>(request.await()).problem.code)
                "cancelled" -> assertEquals(OperationResult.Cancelled, request.await())
                else -> {
                    assertEquals(OperationResult.UnconfirmedCommit, request.await())
                    assertEquals(ProblemCode.COMMIT_OUTCOME_UNKNOWN, f.coordinator.state.value.problem?.code)
                }
            }
            assertNotEquals(AudioPhase.READY, f.coordinator.state.value.phase)
            assertEquals(listOf(file), f.files.discardAttempts)
            assertTrue(f.files.discarded.isEmpty())
            f.play("next")
            assertEquals(AudioPhase.COMPLETED, f.coordinator.state.value.phase)
            f.close()
        }
    }

    @Test fun synthesisHonorsExactUtf16MaximumAndRejectsOneOverBeforeAnyBackendOrTemp() = runTest {
        for (text in listOf("😀ab", "😀abc", "     ")) {
            val f = Fixture(this)
            f.backend.voices.value = f.backend.voices.value.copy(maxTextLength = 4)
            val file = TemporaryFile("synth-limit")
            f.backend.synthesis = { request -> assertEquals(text, request.text); OperationResult.Completed(file) }
            val request = f.synth(text = text); runCurrent()
            if (text.length == 4) {
                assertSame(file, assertIs<OperationResult.Completed<*>>(request.await()).value)
                assertEquals(1, f.backend.calls.size)
                assertEquals(AudioPhase.READY, f.coordinator.state.value.phase)
            } else {
                assertEquals(ProblemCode.LIMIT_EXCEEDED, assertIs<OperationResult.Failed>(request.await()).problem.code)
                assertTrue(f.backend.calls.isEmpty())
                assertTrue(f.backend.inspected.isEmpty())
                assertTrue(f.files.discardAttempts.isEmpty())
                assertEquals(AudioPhase.FAILED, f.coordinator.state.value.phase)
            }
            f.close()
            assertTrue(f.files.discarded.isEmpty())
        }
    }

    @Test fun shutdownStopFailureStillCancelsActiveNativeAndResolvesAwaitingCaller() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        val file = TemporaryFile("late-direct-shutdown")
        var native: Job? = null
        f.backend.recording = { _, _ ->
            native = currentCoroutineContext().job
            withContext(NonCancellable) { gate.await(); OperationResult.Completed(file) }
        }
        f.backend.stopAction = { error("shutdown stop fault") }
        val request = f.record(); runCurrent()
        assertFalse(native!!.isCancelled)
        f.close()
        assertTrue(native!!.isCancelled)
        assertEquals(OperationResult.Cancelled, request.await())
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(file), f.files.discarded)
        assertEquals(0, f.backend.nativeActive)
        assertTrue(f.uncaught.isEmpty())
    }
}
