@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package su.linka.pictures.platform.audio

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okio.*
import okio.Path.Companion.toPath
import platform.AVFAudio.*
import platform.Foundation.*
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.posix.link
import platform.posix.symlink
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.RequestId
import kotlin.test.*

// Main is controlled only for generation tests. These are not foreground/microphone host proof.
class IosAudioBackendTest {
    private val files = OwnedFiles()
    private val request = RecordingRequest(RequestId("record"), AudioOwner("test", AudioPurpose.RECORDING))
    private val inspection = object : MediaInspectionPort {
        override suspend fun image(file: LocalFile): OperationResult<ImageInfo> = error("Not image")
        override suspend fun audio(file: LocalFile): OperationResult<AudioInfo> = OperationResult.Failed(Problem(ProblemCode.INVALID_DATA))
    }

    private fun backend(scope: CoroutineScope, foreground: Boolean = true,
                        permission: suspend (RequestId) -> OperationResult<Unit> = { OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED)) },
                        platformFiles: PlatformFilesPort = files,
                        prepare: suspend (LocalFile) -> OperationResult<LocalFile> = { error("No decoder expected") }) =
        IosAudioBackend(platformFiles, inspection, prepare, permission, { foreground }, scope)

    @BeforeTest fun mainDispatcher() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun resetMain() { Dispatchers.resetMain() }

    @Test fun nativeRuntimeAndSettingsEvidence() {
        println("P07-A runtime: ${NSProcessInfo.processInfo.operatingSystemVersionString}; UDID=${NSProcessInfo.processInfo.environment["SIMULATOR_UDID"]}")
        val settings = waveSettings(16000.0, 1u)
        val format = AVAudioFormat(settings)
        assertNotNull(format)
        assertEquals(16000.0, format.sampleRate)
        assertEquals(1u, format.channelCount)
        assertEquals(AVAudioPCMFormatInt16, format.commonFormat)
        assertEquals(false, settings[AVLinearPCMIsBigEndianKey])
        assertTrue(16000 * 2 * AUDIO_SECONDS_LIMIT + 4096 < AUDIO_BYTE_LIMIT)
    }

    @Test fun nativePcmCopyOwnsSamplesAndPreservesRateChannels() {
        val format = AVAudioFormat(AVAudioPCMFormatFloat32, 22050.0, 2u, false)
        val source = AVAudioPCMBuffer(format, 4u)!!
        source.frameLength = 4u
        for (channel in 0..1) for (frame in 0..3) source.floatChannelData!![channel]!![frame] = (channel + frame).toFloat()
        val copy = ownedPcm(source)
        source.floatChannelData!![0]!![0] = 99f
        assertEquals(0f, copy.floatChannelData!![0]!![0])
        assertEquals(4f, copy.floatChannelData!![1]!![3])
        assertEquals(22050.0, copy.format.sampleRate)
        assertEquals(2u, copy.format.channelCount)
        assertEquals(4u, copy.frameLength)
        source.floatChannelData!![0]!![0] = Float.NaN
        assertEquals(ProblemCode.INVALID_DATA, (assertFailsWith<IosAudioFailure> { ownedPcm(source) }.result as OperationResult.Failed).problem.code)
    }

    @Test fun nativePcmBoundsAndInterleavedInt16() {
        val format = AVAudioFormat(AVAudioPCMFormatInt16, 16000.0, 2u, true)
        val source = AVAudioPCMBuffer(format, 16385u)!!
        source.frameLength = 4u
        for (sample in 0..7) source.int16ChannelData!![0]!![sample] = sample.toShort()
        val copy = ownedPcm(source)
        assertTrue(copy.format.interleaved)
        assertEquals(7.toShort(), copy.int16ChannelData!![0]!![7])
        source.frameLength = 16385u
        assertFailsWith<IosAudioFailure> { ownedPcm(source) }
        source.frameLength = 0u
        assertFailsWith<IosAudioFailure> { ownedPcm(source) }
    }

    @Test fun pcmHandoffHasAnExplicitEightBufferBudget() {
        val call = IosAudioCall(RequestId("pcm-budget"))
        val format = AVAudioFormat(AVAudioPCMFormatInt16, 16000.0, 1u, false)
        val source = AVAudioPCMBuffer(format, 1u)!!
        source.frameLength = 1u
        source.int16ChannelData!![0]!![0] = 1
        repeat(8) { assertTrue(call.pcm.trySend(ownedPcm(source)).isSuccess) }
        assertFalse(call.pcm.trySend(ownedPcm(source)).isSuccess)
        call.pcm.cancel()
    }

    @Test fun malformedSynthesisTextIsRejectedBeforeReservation() = runTest {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val backend = backend(owner)
            val synthesis = SynthesisRequest(RequestId("synth"), AudioOwner("test", AudioPurpose.SYNTHESIS), "hello", "en-US")
            assertProblem(ProblemCode.INVALID_DATA, backend.synthesize(synthesis.copy(text = "\ud800")))
            assertProblem(ProblemCode.LIMIT_EXCEEDED, backend.synthesize(synthesis.copy(text = "x".repeat(4001))))
            assertProblem(ProblemCode.SPEECH_UNAVAILABLE, backend.synthesize(synthesis.copy(text = " ")))
            assertEquals(0, files.created)
        } finally { owner.cancel() }
    }

    @Test fun factoryExistingNonemptyWavIsNotClaimedOrDiscardedBySynthesis() = runTest {
        withContext(Dispatchers.IO) {
            val reservation = IosSynthWriterTest.Reservation()
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                val path = reservation.file.path.toPath()
                FileSystem.SYSTEM.write(path) { writeUtf8("existing source bytes must survive") }
                val before = FileSystem.SYSTEM.read(path) { readByteString() }
                val backend = backend(owner, platformFiles = reservation.files)
                val request = SynthesisRequest(RequestId("factory-alias"), AudioOwner("test", AudioPurpose.SYNTHESIS), "hello", "en-US", "p07.nonexistent.voice")
                assertProblem(ProblemCode.CONFLICT, backend.synthesize(request))
                assertEquals(0, reservation.discarded)
                assertEquals(before, FileSystem.SYSTEM.read(path) { readByteString() })
                assertEquals(before.sha256(), FileSystem.SYSTEM.read(path) { readByteString().sha256() })
                assertIs<OperationResult.Completed<Source>>(reservation.files.source(reservation.file)).value.close()
            } finally { owner.cancel(); reservation.cleanup() }
        }
    }

    @Test fun playbackSourceAliasIsRejectedBeforeCompatibilityTempClaim() = runTest {
        withContext(Dispatchers.IO) {
            val reservation = IosSynthWriterTest.Reservation()
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                val path = reservation.file.path.toPath()
                FileSystem.SYSTEM.write(path) { writeUtf8("canonical playback source") }
                val before = FileSystem.SYSTEM.read(path) { readByteString() }
                val alias = TemporaryFile(reservation.file.path)
                val backend = backend(owner, platformFiles = reservation.files, prepare = { OperationResult.Completed(alias) })
                assertProblem(ProblemCode.CONFLICT, backend.playClip(reservation.file, RequestId("play-alias")))
                assertProblem(ProblemCode.CONFLICT, backend.playClip(LocalFile("${reservation.root}/./reservation.wav"), RequestId("normalized-play-alias")))
                assertEquals(0, reservation.discarded)
                assertEquals(before, FileSystem.SYSTEM.read(path) { readByteString() })
                assertIs<OperationResult.Completed<Source>>(reservation.files.source(reservation.file)).value.close()
            } finally { owner.cancel(); reservation.cleanup() }
        }
    }

    @Test fun invalidFactoryReservationsNeverAcquireDiscardOwnership() = runTest {
        withContext(Dispatchers.IO) {
            for (kind in listOf("extension", "foreign", "symlink", "hardlink")) {
                val reservation = IosSynthWriterTest.Reservation(if (kind == "extension") "reservation.invalid" else "reservation.wav")
                val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                try {
                    if (kind == "symlink" || kind == "hardlink") {
                        val target = reservation.root / "target.wav"
                        FileSystem.SYSTEM.openReadWrite(target, mustCreate = true).close()
                        FileSystem.SYSTEM.delete(reservation.file.path.toPath())
                        assertEquals(0, if (kind == "symlink") symlink(target.toString(), reservation.file.path) else link(target.toString(), reservation.file.path))
                    }
                    val factory = if (kind == "foreign") object : PlatformFilesPort by reservation.files {
                        override fun source(file: LocalFile): OperationResult<Source> = OperationResult.Failed(Problem(ProblemCode.NOT_FOUND))
                    } else reservation.files
                    val previousSize = FileSystem.SYSTEM.metadata(reservation.file.path.toPath()).size
                    val backend = backend(owner, platformFiles = factory)
                    val request = SynthesisRequest(RequestId("invalid-$kind"), AudioOwner("test", AudioPurpose.SYNTHESIS), "hello", "en-US", "p07.nonexistent.voice")
                    assertProblem(when (kind) {
                        "extension" -> ProblemCode.INVALID_DATA
                        "foreign" -> ProblemCode.NOT_FOUND
                        else -> ProblemCode.CONFLICT
                    }, backend.synthesize(request))
                    assertEquals(0, reservation.discarded)
                    assertTrue(FileSystem.SYSTEM.exists(reservation.file.path.toPath()))
                    assertIs<OperationResult.Completed<Source>>(reservation.files.source(reservation.file)).value.buffer().use { assertTrue(it.exhausted()) }
                    assertEquals(previousSize, FileSystem.SYSTEM.metadata(reservation.file.path.toPath()).size)
                } finally { owner.cancel(); reservation.cleanup() }
            }
        }
    }

    @Test fun validFreshFactoryResultAfterStopIsClaimedBeforeSuspensionAndDiscarded() = runTest {
        withContext(Dispatchers.IO) {
            val reservation = IosSynthWriterTest.Reservation()
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                val id = RequestId("late-factory")
                lateinit var backend: IosAudioBackend
                val factory = object : PlatformFilesPort by reservation.files {
                    override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> {
                        backend.stop(id) // Non-cooperative factory still delivers its valid new reservation.
                        return OperationResult.Completed(reservation.file)
                    }
                }
                backend = backend(owner, platformFiles = factory)
                val request = SynthesisRequest(id, AudioOwner("test", AudioPurpose.SYNTHESIS), "hello", "en-US", "p07.nonexistent.voice")
                assertEquals(OperationResult.Cancelled, backend.synthesize(request))
                assertEquals(1, reservation.discarded)
                assertFalse(FileSystem.SYSTEM.exists(reservation.file.path.toPath()))
                assertProblem(ProblemCode.NOT_FOUND, reservation.files.source(reservation.file))
            } finally { owner.cancel(); reservation.cleanup() }
        }
    }

    @Test fun initializationReportsActualAvailableVoiceMetadata() = runTest {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val backend = backend(owner)
            val expected = AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>()
            val snapshot = backend.voices.value
            assertEquals(4000, snapshot.maxTextLength)
            assertEquals(expected.map { it.identifier }.toSet(), snapshot.voices.map { it.id }.toSet())
            assertEquals(if (expected.isEmpty()) VoiceStatus.MISSING_VOICE else VoiceStatus.LOCAL_VOICE_READY, snapshot.status)
        } finally { owner.cancel() }
    }

    @Test fun invalidTextLimitsAndInactiveForegroundNeverReachSpeech() = runTest {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val backend = backend(owner, foreground = false)
            for (text in listOf("\u0000", "\ud800", "\udc00", "a\ud800b"))
                assertProblem(ProblemCode.INVALID_DATA, backend.speak(text, null, null, RequestId("invalid")))
            assertProblem(ProblemCode.LIMIT_EXCEEDED, backend.speak("x".repeat(4001), null, null, RequestId("long")))
            assertProblem(ProblemCode.INTERRUPTED, backend.speak("hello", null, null, RequestId("background")))
            assertTrue(files.discarded.isEmpty())
        } finally { owner.cancel() }
    }

    @Test fun permissionDenialIsNotRecordingAndBackendRemainsUsable() = runTest {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            var permissionCalls = 0
            val backend = backend(owner, permission = { permissionCalls++; OperationResult.Failed(Problem(ProblemCode.PERMISSION_DENIED)) })
            repeat(2) {
                val phases = mutableListOf<AudioPhase>()
                assertProblem(ProblemCode.PERMISSION_DENIED, backend.record(request) { phases += it })
                assertEquals(listOf(AudioPhase.REQUESTING_PERMISSION), phases)
            }
            assertEquals(2, permissionCalls)
            assertEquals(0, files.created)
        } finally { owner.cancel() }
    }

    @Test fun completedPermissionCallbackCannotBypassActualOsDenial() = runTest {
        if (AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted) {
            println("OPEN actual-denial guard test: existing OS grant; will not start microphone")
            return@runTest
        }
        val before = AVAudioSession.sharedInstance().recordPermission
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val backend = backend(owner, permission = { OperationResult.Completed(Unit) })
            assertProblem(ProblemCode.PERMISSION_DENIED, backend.record(request) {})
            assertEquals(0, files.created)
            assertEquals(before, AVAudioSession.sharedInstance().recordPermission)
        } finally { owner.cancel() }
    }

    @Test fun explicitUnknownVoiceAndUnsupportedLanguageNeverFallBack() = runTest {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val backend = backend(owner)
            assertProblem(ProblemCode.VOICE_UNAVAILABLE, backend.speak("hello", "en-US", "p07.nonexistent.voice", RequestId("missing-voice")))
            assertProblem(ProblemCode.VOICE_UNAVAILABLE, backend.speak("hello", "zz-P07", null, RequestId("missing-language")))
        } finally { owner.cancel() }
    }

    @Test fun stopRevokesOnlyMatchingGenerationAndLatePreparedFileIsDiscarded() = runTest {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val late = TemporaryFile("owned-late.wav", "late.wav", "audio/wav")
        try {
            val backend = backend(owner, prepare = {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                OperationResult.Completed(late)
            })
            val id = RequestId("old")
            val pending = async { backend.playClip(LocalFile("canonical"), id) }
            entered.await()
            backend.stop(RequestId("stale"))
            assertFalse(pending.isCompleted)
            backend.stop(id); backend.stop(id)
            assertProblem(ProblemCode.CONFLICT, backend.speak("", null, null, RequestId("new-too-early")))
            release.complete(Unit)
            assertEquals(OperationResult.Cancelled, pending.await())
            assertEquals(listOf(late), files.discarded)
            // A stale stop/finish cannot revoke the next generation.
            backend.stop(id); backend.finishRecording(id)
            assertEquals(OperationResult.Completed(Unit), backend.speak("", null, null, RequestId("new")))
        } finally { release.complete(Unit); owner.cancel() }
    }

    @Test fun lateBorrowedOriginalAfterStopIsNeverDiscardedEvenWhenTemporary() = runTest {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val original = TemporaryFile("borrowed-original.wav")
        try {
            val backend = backend(owner, prepare = {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                OperationResult.Completed(it)
            })
            val id = RequestId("borrowed")
            val pending = async { backend.playClip(original, id) }
            entered.await()
            backend.stop(id)
            release.complete(Unit)
            assertEquals(OperationResult.Cancelled, pending.await())
            assertTrue(files.discarded.isEmpty())
        } finally { release.complete(Unit); owner.cancel() }
    }

    @Test fun invalidBorrowedSourceIsNotClaimedForCleanup() = runTest {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val original = TemporaryFile("borrowed-invalid.wav")
            val backend = backend(owner, prepare = { OperationResult.Completed(it) })
            assertProblem(ProblemCode.INVALID_DATA, backend.playClip(original, RequestId("invalid-borrowed")))
            assertTrue(files.discarded.isEmpty())
        } finally { owner.cancel() }
    }

    @Test fun inspectionFailureAndDiscardExceptionDoNotStrandNextRequest() = runTest {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val prepared = TemporaryFile("owned.wav", "owned.wav", "audio/wav")
            files.failDiscard = true
            val backend = backend(owner, prepare = { OperationResult.Completed(prepared) })
            assertProblem(ProblemCode.INVALID_DATA, backend.playClip(LocalFile("canonical"), RequestId("bad")))
            assertEquals(listOf(prepared), files.discarded)
            assertEquals(OperationResult.Completed(Unit), backend.speak("", null, null, RequestId("after")))
        } finally { owner.cancel() }
    }

    @Test fun scopeCancellationRevokesPermissionWaitWithoutRecording() = runTest {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val entered = CompletableDeferred<Unit>()
        val backend = backend(owner, permission = { entered.complete(Unit); awaitCancellation() })
        val pending = async { backend.record(request) {} }
        entered.await(); owner.cancel()
        assertEquals(OperationResult.Cancelled, pending.await())
        assertEquals(0, files.created)
        assertEquals(OperationResult.Cancelled, backend.speak("", null, null, RequestId("closed")))
    }

    @Test fun nativeLifecycleNotificationsInterruptWithoutPermissionOrAutoReplay() = runTest {
        for ((name, info) in listOf<Pair<String?, Map<Any?, *>?>>(
            UIApplicationDidEnterBackgroundNotification to null,
            AVAudioSessionInterruptionNotification to mapOf(AVAudioSessionInterruptionTypeKey to NSNumber(unsignedLong = AVAudioSessionInterruptionTypeBegan)),
            AVAudioSessionRouteChangeNotification to mapOf(AVAudioSessionRouteChangeReasonKey to NSNumber(unsignedLong = AVAudioSessionRouteChangeReasonOldDeviceUnavailable)),
        )) {
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val entered = CompletableDeferred<Unit>()
            var attempts = 0
            try {
                val backend = backend(owner, permission = { attempts++; entered.complete(Unit); awaitCancellation() })
                val pending = async { backend.record(request) {} }
                entered.await()
                NSNotificationCenter.defaultCenter.postNotificationName(name!!, null, info)
                assertProblem(ProblemCode.INTERRUPTED, pending.await())
                assertEquals(1, attempts)
                assertEquals(0, files.created)
                backend.stop(request.id); backend.finishRecording(request.id)
                assertEquals(OperationResult.Completed(Unit), backend.speak("", null, null, RequestId("after-notification")))
            } finally { owner.cancel() }
        }
    }

    private fun assertProblem(code: ProblemCode, result: OperationResult<*>) {
        assertEquals(code, assertIs<OperationResult.Failed>(result).problem.code)
    }

    private class OwnedFiles : PlatformFilesPort {
        val discarded = mutableListOf<TemporaryFile>()
        var created = 0
        var failDiscard = false
        override fun createTemporaryFile(suggestedName: String, mediaType: String): OperationResult<TemporaryFile> {
            created++; return OperationResult.Completed(TemporaryFile("owned-$created.wav", suggestedName, mediaType))
        }
        override fun source(file: LocalFile): OperationResult<Source> = OperationResult.Completed(Buffer())
        override fun sink(file: TemporaryFile): OperationResult<Sink> = error("No sink expected")
        override fun discard(file: TemporaryFile) { discarded += file; if (failDiscard) error("Injected cleanup failure") }
        override suspend fun pick(kind: PickedFileKind, ownerId: String): OperationResult<TemporaryFile> = error("No picker")
        override suspend fun chooseDestination(suggestedName: String, mediaType: String, ownerId: String): OperationResult<ExportDestination> = error("No export")
    }
}
