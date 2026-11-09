package su.linka.pictures.ui.communication

import su.linka.pictures.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import su.linka.pictures.application.Problem
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.application.communication.MessageSavePhase
import su.linka.pictures.application.ports.AudioPhase
import su.linka.pictures.application.ports.AudioState
import su.linka.pictures.application.ports.VoiceCapability
import su.linka.pictures.application.ports.VoiceStatus
import su.linka.pictures.domain.*

class CommunicationPresentationTest {
    private val page = BoardPage(PageId("first"), 1, 2, listOf(
        BoardCell(1, CellKind.CONTENT, CardDocument(CardId("card"), "Хочу")),
    ))
    private val lastPage = BoardPage(PageId("last"), 64, 64, listOf(
        BoardCell(4095, CellKind.CONTENT, CardDocument(CardId("last-card"), "кашу")),
        BoardCell(17, CellKind.SPACE), BoardCell(200, CellKind.EMPTY),
    ))
    private val board = PublishedBoard(BoardDocument(BoardId("board"), "Доска", listOf(page, lastPage)), 2)
    private val part = MessagePart.Card(
        MessagePartId("part"),
        CardSelectionSnapshot(SelectionOrigin(board.document.id, 2, page.id, CardId("card")), "Хочу", null, SpeechSource.None),
    )
    private val state = CommunicationState(
        message = MessageDraft(listOf(part), BoardCursor(board.document.id, page.id)),
        board = board, renderedText = "Точный текст session", savePhase = MessageSavePhase.SAVED,
    )
    private val readyVoice = VoiceCapability(VoiceStatus.LOCAL_VOICE_READY)

    @Test
    fun pageComesOnlyFromExactSessionCursorAndEverySparseSlotSurvives() {
        val p = communicationPresentation(state, readyVoice)
        assertSame(page, p.page)
        assertEquals(0, p.pageIndex)
        assertEquals(listOf(null, page.cells.single()), communicationSlots(page))
        val slots = communicationSlots(lastPage)
        assertEquals(4096, slots.size)
        assertNull(slots[0])
        assertEquals(CellKind.SPACE, slots[17]?.kind)
        assertEquals(CellKind.EMPTY, slots[200]?.kind)
        assertEquals("кашу", slots[4095]?.card?.label)
        assertEquals(lastPage.cells.toSet(), slots.filterNotNull().toSet())
        val last = communicationPresentation(state.copy(message = state.message.copy(cursor = BoardCursor(board.document.id, lastPage.id, 4095))), readyVoice)
        assertEquals(1, last.pageIndex)
        assertSame(lastPage, last.page)
        for (cursor in listOf(null, BoardCursor(BoardId("another"), page.id), BoardCursor(board.document.id, PageId("missing")))) {
            assertNull(communicationPresentation(state.copy(message = state.message.copy(cursor = cursor)), readyVoice).page)
        }
    }

    @Test
    fun selectionMarksOnlySnapshotsOfTheCurrentRevisionPageAndComposeMode() {
        assertEquals(setOf(CardId("card")), communicationPresentation(state, readyVoice).selectedCards)
        val oldRevision = state.copy(board = board.copy(revision = 3))
        assertTrue(communicationPresentation(oldRevision, readyVoice).selectedCards.isEmpty())
        val otherPage = state.copy(message = state.message.copy(cursor = BoardCursor(board.document.id, lastPage.id)))
        assertTrue(communicationPresentation(otherPage, readyVoice).selectedCards.isEmpty())
        val direct = communicationPresentation(state.copy(message = state.message.copy(mode = InteractionMode.DIRECT), canUndoClear = true), readyVoice)
        assertFalse(direct.composing)
        assertFalse(direct.editEnabled)
        assertFalse(direct.undoEnabled)
        assertFalse(direct.speechEnabled)
        assertTrue(direct.selectedCards.isEmpty())
        assertEquals("Точный текст session", state.renderedText)
        assertEquals(listOf(part), state.message.parts)
    }

    @Test
    fun missingVoiceNeverGuessesThatAllPlansNeedTts() {
        val descriptions = VoiceStatus.entries.map { voice ->
            val p = communicationPresentation(state, VoiceCapability(voice))
            assertTrue(p.speechEnabled, "$voice must not disable a recording-only plan")
            assertTrue(p.commandsEnabled)
            p.voiceStatus
        }
        assertEquals(VoiceStatus.entries.size, descriptions.toSet().size)
        val empty = communicationPresentation(state.copy(message = state.message.copy(parts = emptyList())), readyVoice)
        assertFalse(empty.speechEnabled)
        assertFalse(empty.editEnabled)
        assertTrue(communicationPresentation(state.copy(canUndoClear = true), readyVoice).undoEnabled)
    }

    @Test
    fun stopReplacesSpeakForEveryActivePhaseIncludingDirectMode() {
        val active = setOf(AudioPhase.PREPARING, AudioPhase.PLAYING, AudioPhase.REQUESTING_PERMISSION, AudioPhase.RECORDING, AudioPhase.FINISHING, AudioPhase.SYNTHESIZING)
        for (phase in AudioPhase.entries) {
            val p = communicationPresentation(state.copy(audio = AudioState(phase = phase), message = state.message.copy(mode = InteractionMode.DIRECT)), readyVoice)
            assertEquals(phase in active, p.stopping, "$phase")
            assertEquals(phase in active, p.speechEnabled, "$phase")
        }
        assertNotEquals(
            communicationPresentation(state.copy(audio = AudioState(phase = AudioPhase.CANCELLED)), readyVoice).audioStatus,
            communicationPresentation(state.copy(audio = AudioState(phase = AudioPhase.COMPLETED)), readyVoice).audioStatus,
        )
    }

    @Test
    fun uncertainDurabilityAndStaleSessionFreezeEveryCommandWithoutClaimingSaved() {
        for (uncertain in listOf(
            state.copy(savePhase = MessageSavePhase.UNCONFIRMED),
            state.copy(problem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN)),
            state.copy(audio = AudioState(problem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))),
        )) {
            val p = communicationPresentation(uncertain.copy(canUndoClear = true), readyVoice)
            assertFalse(p.commandsEnabled)
            assertFalse(p.speechEnabled)
            assertFalse(p.editEnabled)
            assertFalse(p.undoEnabled)
            assertTrue(p.unconfirmed)
            assertEquals(communicationProblemText(ProblemCode.COMMIT_OUTCOME_UNKNOWN), p.saveStatus)
            assertEquals(state.message, uncertain.message)
        }
        assertFalse(communicationPresentation(state.copy(problem = Problem(ProblemCode.STALE_REQUEST)), readyVoice).commandsEnabled)
        assertFalse(communicationPresentation(state.copy(savePhase = MessageSavePhase.LOADING), readyVoice).commandsEnabled)
        assertTrue(communicationPresentation(state.copy(savePhase = MessageSavePhase.FAILED), readyVoice).commandsEnabled)
        assertTrue(communicationPresentation(state.copy(savePhase = MessageSavePhase.SAVING), readyVoice).commandsEnabled)
    }

    @Test
    fun noticesHideRoutineSuccessButKeepDurabilityAndActionFailures() {
        fun notices(value: CommunicationState, voice: VoiceCapability = readyVoice) =
            communicationNotices(value, communicationPresentation(value, voice), voice)
        assertTrue(notices(state).isEmpty())
        assertTrue(notices(state, VoiceCapability(VoiceStatus.MISSING_VOICE)).isEmpty())
        assertTrue(notices(state.copy(audio = AudioState(phase = AudioPhase.COMPLETED))).isEmpty())
        assertTrue(notices(state.copy(audio = AudioState(phase = AudioPhase.CANCELLED))).isEmpty())
        assertTrue(notices(state.copy(savePhase = MessageSavePhase.UNCONFIRMED)).contains(communicationProblemText(ProblemCode.COMMIT_OUTCOME_UNKNOWN)))
        assertTrue(notices(state.copy(problem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))).contains(communicationProblemText(ProblemCode.COMMIT_OUTCOME_UNKNOWN)))
        assertTrue(notices(state.copy(savePhase = MessageSavePhase.FAILED)).contains(Res.string.message_was_not_saved_it_remains_on))
        assertTrue(notices(state.copy(audio = AudioState(phase = AudioPhase.FAILED))).isNotEmpty())
        assertTrue(notices(state.copy(problem = Problem(ProblemCode.INTEGRITY_FAILURE))).isNotEmpty())
        val voiceFailure = notices(state.copy(audio = AudioState(problem = Problem(ProblemCode.VOICE_UNAVAILABLE))), VoiceCapability(VoiceStatus.MISSING_VOICE))
        assertTrue(voiceFailure.contains(Res.string.no_local_os_voice_is_installed_text))
        assertFalse(notices(state.copy(problem = Problem(ProblemCode.VOICE_UNAVAILABLE))).contains(Res.string.local_os_voice_is_available))
        val stale = notices(state.copy(problem = Problem(ProblemCode.STALE_REQUEST)))
        assertTrue(stale.contains(communicationProblemText(ProblemCode.STALE_REQUEST)))
        assertFalse(stale.contains(Res.string.message_saved))
    }

    @Test
    fun noticeVisibilityUsesDurabilityNotTheDisplayedResource() {
        val uncertain = state.copy(problem = Problem(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
        val presentation = communicationPresentation(uncertain, readyVoice)
            .copy(saveStatus = Res.string.message_saved)
        assertTrue(presentation.unconfirmed)
        assertTrue(communicationNotices(uncertain, presentation, readyVoice).contains(Res.string.message_saved))
        val saved = communicationPresentation(state, readyVoice)
            .copy(saveStatus = communicationProblemText(ProblemCode.COMMIT_OUTCOME_UNKNOWN))
        assertFalse(saved.unconfirmed)
        assertTrue(communicationNotices(state, saved, readyVoice).isEmpty())
    }

    @Test
    fun problemsUseOnlySafeCodesAndKeepVoiceAndSpeechFailuresDistinct() {
        val subject = "/private/person/media/secret.wav"
        val p = communicationPresentation(state.copy(
            savePhase = MessageSavePhase.FAILED,
            problem = Problem(ProblemCode.VOICE_UNAVAILABLE, subjectId = subject, systemCode = 12345),
            audio = AudioState(phase = AudioPhase.FAILED, problem = Problem(ProblemCode.SPEECH_UNAVAILABLE, subjectId = subject)),
        ), readyVoice)
        assertEquals(2, p.problems.size)
        assertEquals(listOf(communicationProblemText(ProblemCode.VOICE_UNAVAILABLE),
            communicationProblemText(ProblemCode.SPEECH_UNAVAILABLE)), p.problems)
        assertNotEquals(p.problems[0], p.problems[1])
        assertEquals(Res.string.message_was_not_saved_it_remains_on, p.saveStatus)
        assertEquals(1, communicationPresentation(state.copy(
            problem = Problem(ProblemCode.IO_FAILURE), audio = AudioState(problem = Problem(ProblemCode.IO_FAILURE)),
        ), readyVoice).problems.size)
        assertEquals(ProblemCode.entries.size, ProblemCode.entries.map(::communicationProblemText).toSet().size)
    }
}
