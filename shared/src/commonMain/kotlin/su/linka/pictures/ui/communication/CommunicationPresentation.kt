package su.linka.pictures.ui.communication

import org.jetbrains.compose.resources.StringResource
import su.linka.pictures.resources.*
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.application.communication.MessageSavePhase
import su.linka.pictures.application.ports.AudioPhase
import su.linka.pictures.application.ports.VoiceCapability
import su.linka.pictures.application.ports.VoiceStatus
import su.linka.pictures.domain.BoardCell
import su.linka.pictures.domain.BoardPage
import su.linka.pictures.domain.CardId
import su.linka.pictures.domain.InteractionMode
import su.linka.pictures.domain.MessagePart

internal data class CommunicationPresentation(
    val page: BoardPage?,
    val pageIndex: Int,
    val selectedCards: Set<CardId>,
    val commandsEnabled: Boolean,
    val composing: Boolean,
    val stopping: Boolean,
    val speechEnabled: Boolean,
    val editEnabled: Boolean,
    val undoEnabled: Boolean,
    val unconfirmed: Boolean,
    val saveStatus: StringResource,
    val audioStatus: StringResource,
    val voiceStatus: StringResource,
    val problems: List<StringResource>,
)

internal fun communicationPresentation(state: CommunicationState, voices: VoiceCapability): CommunicationPresentation {
    val cursor = state.message.cursor
    val board = state.board?.takeIf { it.document.id == cursor?.boardId }
    val pageIndex = board?.document?.pages?.indexOfFirst { it.id == cursor?.pageId } ?: -1
    val page = board?.document?.pages?.getOrNull(pageIndex)
    val codes = listOfNotNull(state.problem?.code, state.audio.problem?.code).distinct()
    val unconfirmed = state.savePhase == MessageSavePhase.UNCONFIRMED || ProblemCode.COMMIT_OUTCOME_UNKNOWN in codes
    val enabled = !unconfirmed && state.savePhase != MessageSavePhase.LOADING && ProblemCode.STALE_REQUEST !in codes
    val composing = state.message.mode == InteractionMode.COMPOSE
    val stopping = state.audio.phase in setOf(
        AudioPhase.PREPARING, AudioPhase.PLAYING, AudioPhase.REQUESTING_PERMISSION,
        AudioPhase.RECORDING, AudioPhase.FINISHING, AudioPhase.SYNTHESIZING,
    )
    return CommunicationPresentation(
        page = page,
        pageIndex = pageIndex,
        selectedCards = if (!composing || board == null || page == null) emptySet() else state.message.parts
            .filterIsInstance<MessagePart.Card>()
            .filter {
                it.snapshot.origin.boardId == board.document.id && it.snapshot.origin.boardRevision == board.revision &&
                    it.snapshot.origin.pageId == page.id
            }.mapTo(mutableSetOf()) { it.snapshot.origin.cardId },
        commandsEnabled = enabled,
        composing = composing,
        stopping = stopping,
        // Missing TTS must not disable a recording-only plan; the coordinator checks the real plan.
        speechEnabled = enabled && (stopping || (composing && state.message.parts.isNotEmpty())),
        editEnabled = enabled && composing && state.message.parts.isNotEmpty(),
        undoEnabled = enabled && composing && state.canUndoClear,
        unconfirmed = unconfirmed,
        saveStatus = if (unconfirmed) unconfirmedMessage else when (state.savePhase) {
            MessageSavePhase.LOADING -> Res.string.loading_the_saved_message_actions_are_temporarily
            MessageSavePhase.SAVING -> Res.string.saving_the_message_has_not_yet_been
            MessageSavePhase.SAVED -> Res.string.message_saved
            MessageSavePhase.FAILED -> Res.string.message_was_not_saved_it_remains_on
            MessageSavePhase.UNCONFIRMED -> unconfirmedMessage
        },
        audioStatus = when (state.audio.phase) {
            AudioPhase.IDLE -> Res.string.playback_stopped
            AudioPhase.PREPARING -> Res.string.preparing_playback
            AudioPhase.PLAYING -> Res.string.speaking_message
            AudioPhase.REQUESTING_PERMISSION -> Res.string.waiting_for_recording_permission
            AudioPhase.RECORDING -> Res.string.recording_in_progress
            AudioPhase.FINISHING -> Res.string.finishing_recording
            AudioPhase.SYNTHESIZING -> Res.string.preparing_speech
            AudioPhase.READY -> Res.string.audio_prepared
            AudioPhase.COMPLETED -> Res.string.playback_completed
            AudioPhase.CANCELLED -> Res.string.playback_cancelled
            AudioPhase.INTERRUPTED -> Res.string.playback_interrupted_there_is_no_automatic_retry
            AudioPhase.FAILED -> Res.string.could_not_finish_playback_the_message_remains
        },
        voiceStatus = when (voices.status) {
            VoiceStatus.INITIALIZING -> Res.string.checking_the_local_os_voice_recordings_do
            VoiceStatus.LOCAL_VOICE_READY -> Res.string.local_os_voice_is_available
            VoiceStatus.MISSING_VOICE -> Res.string.no_local_os_voice_is_installed_text
            VoiceStatus.UNSUPPORTED_LANGUAGE -> Res.string.the_local_voice_does_not_support_the
            VoiceStatus.INTERRUPTED -> Res.string.local_voice_check_interrupted_recordings_do_not
            VoiceStatus.FAILED -> Res.string.could_not_check_the_local_os_voice
        },
        problems = codes.filterNot { it == ProblemCode.COMMIT_OUTCOME_UNKNOWN }.map(::communicationProblemText),
    )
}

private val unconfirmedMessage = Res.string.saving_has_not_been_confirmed_the_operation

internal fun communicationNotices(state: CommunicationState, presentation: CommunicationPresentation, voices: VoiceCapability): List<StringResource> = buildList {
    if (state.savePhase != MessageSavePhase.SAVED || presentation.unconfirmed) add(presentation.saveStatus)
    addAll(presentation.problems)
    if (state.audio.phase in setOf(AudioPhase.FAILED, AudioPhase.INTERRUPTED)) add(presentation.audioStatus)
    if (voices.status != VoiceStatus.LOCAL_VOICE_READY &&
        (state.problem?.code == ProblemCode.VOICE_UNAVAILABLE || state.audio.problem?.code == ProblemCode.VOICE_UNAVAILABLE))
        add(presentation.voiceStatus)
}.distinct()

internal fun pageTarget(index: Int, count: Int, direction: Int, enabled: Boolean): Int? =
    (index + direction).takeIf { enabled && index in 0 until count && it in 0 until count && direction in listOf(-1, 1) }

/** Like a button, activate on release; native key repeat never changes multiple pages. */
internal class PageKeyActivation {
    private var pressed: Int? = null
    fun reset() { pressed = null }
    fun event(direction: Int, down: Boolean, textFocused: Boolean, modified: Boolean, enabled: Boolean = true, activate: (Int) -> Unit): Boolean {
        if (!enabled || textFocused || modified || direction !in listOf(-1, 1)) {
            reset()
            return false
        }
        if (down) {
            if (pressed == null) pressed = direction
        } else {
            val fire = pressed == direction
            reset()
            if (fire) activate(direction)
        }
        return true
    }
}

internal fun communicationSlots(page: BoardPage): List<BoardCell?> {
    val cells = page.cells.associateBy { it.position }
    return List(page.capacity) { cells[it] }
}

internal fun communicationProblemText(code: ProblemCode): StringResource = when (code) {
    ProblemCode.NOT_FOUND -> Res.string.board_not_found_open_the_library_and
    ProblemCode.CONFLICT -> Res.string.data_was_changed_by_another_action_changes
    ProblemCode.INVALID_DATA -> Res.string.could_not_read_data_invalid_format
    ProblemCode.NEWER_SCHEMA -> Res.string.data_was_created_by_a_newer_app
    ProblemCode.IO_FAILURE -> Res.string.read_or_write_error_check_available_space
    ProblemCode.UNSUPPORTED_VERSION -> Res.string.board_version_is_not_supported
    ProblemCode.UNSUPPORTED_MODE -> Res.string.board_mode_is_not_supported
    ProblemCode.UNSUPPORTED_FEATURE -> Res.string.the_board_contains_an_unsupported_feature
    ProblemCode.UNSUPPORTED_MEDIA -> Res.string.image_or_audio_format_is_not_supported
    ProblemCode.UNSAFE_ARCHIVE -> Res.string.unsafe_archive_rejected
    ProblemCode.LIMIT_EXCEEDED -> Res.string.data_size_limit_reached_content_is_not
    ProblemCode.UNAUTHORIZED -> Res.string.this_action_requires_assistant_access_communication_does
    ProblemCode.OWNER_VERIFICATION_UNAVAILABLE -> Res.string.system_owner_verification_is_unavailable
    ProblemCode.PERMISSION_DENIED -> Res.string.permission_for_the_requested_action_is_missing
    ProblemCode.VOICE_UNAVAILABLE -> Res.string.the_required_local_os_voice_is_unavailable
    ProblemCode.INTERRUPTED -> Res.string.action_interrupted_there_is_no_automatic_retry
    ProblemCode.UNFINISHED_FORM -> Res.string.finish_the_form_first
    ProblemCode.RECOVERY_UNAVAILABLE -> Res.string.could_not_prepare_a_return_to_the
    ProblemCode.STALE_REQUEST -> Res.string.the_library_was_replaced_this_session_is
    ProblemCode.MISSING_ASSET -> Res.string.required_image_or_audio_not_found_the
    ProblemCode.INTEGRITY_FAILURE -> Res.string.data_integrity_check_failed_do_not_delete
    ProblemCode.SPEECH_UNAVAILABLE -> Res.string.speech_is_unavailable_for_part_of_the
    ProblemCode.SPEECH_MATERIALIZATION_REQUIRED -> Res.string.prepare_local_speech_audio_before_transferring
    ProblemCode.COMMIT_OUTCOME_UNKNOWN -> unconfirmedMessage
}
