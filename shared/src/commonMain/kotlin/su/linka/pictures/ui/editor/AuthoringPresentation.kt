package su.linka.pictures.ui.editor

import org.jetbrains.compose.resources.StringResource
import su.linka.pictures.resources.*
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.application.authoring.*
import su.linka.pictures.domain.BoardDocument
import su.linka.pictures.domain.InputStyle

internal fun withoutSpaceDetails(document: BoardDocument, checked: Boolean): AuthoringAction.Details =
    AuthoringAction.Details(document.name, document.description,
        document.behavior.copy(inputStyle = if (checked) InputStyle.LITERAL else InputStyle.CARDS))

internal fun AuthoringState.editable(): Boolean = !switchingCard && (phase == AuthoringPhase.EDITING || phase == AuthoringPhase.CONFLICT)

internal fun draftSaveLabel(phase: DraftSavePhase): StringResource = when (phase) {
    DraftSavePhase.UNSAVED -> Res.string.draft_not_yet_saved
    DraftSavePhase.SAVING -> Res.string.saving_draft
    DraftSavePhase.SAVED -> Res.string.draft_saved_on_this_device
    DraftSavePhase.FAILED -> Res.string.draft_was_not_saved_do_not_close
    DraftSavePhase.UNCONFIRMED -> Res.string.write_outcome_is_unknown_editing_is_stopped
}

internal fun authoringProblemLabel(code: ProblemCode): StringResource = when (code) {
    ProblemCode.CONFLICT -> Res.string.the_board_or_draft_was_changed_by
    ProblemCode.STALE_REQUEST -> Res.string.the_library_was_replaced_reopen_the_editor
    ProblemCode.UNAUTHORIZED -> Res.string.assistant_sign_in_and_explicit_continuation_are
    ProblemCode.UNFINISHED_FORM -> Res.string.finish_the_form_and_wait_for_the
    ProblemCode.LIMIT_EXCEEDED -> Res.string.limit_exceeded_256_pages_grid_1_64
    ProblemCode.MISSING_ASSET -> Res.string.image_or_recording_missing_select_the_file
    ProblemCode.INVALID_DATA -> Res.string.check_the_entered_data_and_grid_size
    ProblemCode.NOT_FOUND -> Res.string.the_board_or_draft_no_longer_exists
    ProblemCode.PERMISSION_DENIED -> Res.string.permission_for_the_selected_operation_is_missing
    ProblemCode.UNSUPPORTED_MEDIA -> Res.string.this_image_or_audio_format_is_not
    ProblemCode.VOICE_UNAVAILABLE, ProblemCode.SPEECH_UNAVAILABLE -> Res.string.local_voice_unavailable_choose_a_recording_or
    ProblemCode.INTERRUPTED -> Res.string.operation_interrupted_form_changes_are_saved_only
    ProblemCode.COMMIT_OUTCOME_UNKNOWN -> Res.string.write_outcome_is_unknown_do_not_apply
    else -> Res.string.could_not_complete_the_operation_1_s
}
