package su.linka.pictures.ui.portability

import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import su.linka.pictures.resources.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.launch
import su.linka.pictures.application.*
import su.linka.pictures.application.portability.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.app.*

internal fun transferPhaseLabel(phase: TransferPhase): StringResource = when (phase) {
    TransferPhase.VALIDATING -> Res.string.checking_file
    TransferPhase.PREPARING -> Res.string.preparing_preview
    TransferPhase.AWAITING_CONFIRMATION -> Res.string.check_the_data_and_confirm_using_the
    TransferPhase.WRITING -> Res.string.writing_do_not_repeat_the_action
    TransferPhase.COMPLETED -> Res.string.operation_completed
    TransferPhase.CANCELLED -> Res.string.operation_cancelled
    TransferPhase.FAILED -> Res.string.operation_failed
    TransferPhase.UNCONFIRMED -> Res.string.write_outcome_is_unknown_retrying_is_prohibited
}

@Composable
internal fun TransferScreen(library: LibraryStore, transfer: TransferCoordinator, gate: AssistantGate,
    owner: String, capability: AssistantCapability?, enabled: Boolean, foreground: Boolean, boardId: BoardId?,
    access: @Composable () -> Unit, onBack: () -> Unit) {
    val operations by transfer.state.collectAsState()
    val listing by library.listing.collectAsState()
    val current = operations.values.lastOrNull { it.ownerId == owner }
    var result by remember { mutableStateOf<OperationResult<*>?>(null) }
    var recovery by remember { mutableStateOf<OperationResult<RecoveryInfo?>?>(null) }
    val importedBoardName = stringResource(Res.string.imported_board)
    var name by remember { mutableStateOf(importedBoardName) }
    var busy by remember { mutableStateOf(false) }
    var replace by remember(current?.transfer?.id) { mutableStateOf<LibraryEntrySummary?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(current?.transfer?.phase) { recovery = library.recovery() }
    val terminal = current == null || current.transfer.phase in setOf(TransferPhase.COMPLETED, TransferPhase.CANCELLED, TransferPhase.FAILED)
    val canPrepare = enabled && !busy && terminal
    fun prepare(action: ProtectedAction, block: suspend () -> OperationResult<OperationId>) {
        val admission = gate.check(action, capability, owner)
        if (admission !is OperationResult.Completed) { result = admission; return }
        busy = true
        scope.launch { try { result = block() } finally { busy = false } }
    }
    fun confirm(confirmation: TransferConfirmation) {
        val id = current?.transfer?.id ?: return
        busy = true
        scope.launch { try { result = transfer.confirm(owner, id, capability, confirmation) } finally { busy = false } }
    }
    ProductScaffold(stringResource(Res.string.files_and_restore), onBack) {
        ProductSection(stringResource(Res.string.assistant_access)) { access() }
        ProductSection(stringResource(Res.string.board_import_and_export)) {
        Text(stringResource(Res.string.linka_files_and_backups_are_unencrypted_archives))
        Field(name, stringResource(Res.string.imported_board_name), canPrepare) { name = it }
        Action(stringResource(Res.string.choose_linka_to_import_without_writing_to), canPrepare && name.isNotBlank()) {
            val entered = name; prepare(ProtectedAction.IMPORT_BOARD) { transfer.prepareImport(owner, entered) }
        }
        if (boardId != null) Action(stringResource(Res.string.prepare_linka_export_of_the_selected_board), canPrepare) {
            prepare(ProtectedAction.EXPORT_BOARD) { transfer.prepareExport(owner, boardId) }
        }
        }
        ProductSection(stringResource(Res.string.backup_and_restore)) {
        Action(stringResource(Res.string.prepare_a_backup_of_the_whole_library), canPrepare) {
            prepare(ProtectedAction.CREATE_BACKUP) { transfer.prepareBackup(owner) }
        }
        val recoveryAbsent = (recovery as? OperationResult.Completed)?.value == null && recovery is OperationResult.Completed
        Action(stringResource(Res.string.choose_a_backup_to_validate), canPrepare && recoveryAbsent) {
            prepare(ProtectedAction.RESTORE_BACKUP) { transfer.prepareRestore(owner) }
        }
        Text(stringResource(Res.string.backups_transfer_boards_drafts_media_and_portable))
        when (val info = recovery) {
            null -> Text(stringResource(Res.string.checking_recovery_point))
            is OperationResult.Completed -> if (info.value == null) Text(stringResource(Res.string.no_recovery_point)) else {
                val saved = info.value
                Text(stringResource(Res.string.recovery_point_boards_1_s_drafts_2, saved.boardCount, saved.draftCount, saved.assetBytes, saved.createdAtEpochMillis))
                Text(stringResource(Res.string.to_restore_from_a_new_file_first))
                Action(stringResource(Res.string.validate_return_to_recovery_point), canPrepare) {
                    prepare(ProtectedAction.RESTORE_BACKUP) { transfer.prepareReturnToRecovery(owner, saved.id) }
                }
                Action(stringResource(Res.string.prepare_recovery_point_deletion), canPrepare, true) {
                    prepare(ProtectedAction.DELETE_RECOVERY) { transfer.prepareDeleteRecovery(owner, saved.id) }
                }
            }
            else -> Feedback(info)
        }
        Action(stringResource(Res.string.refresh_recovery_point_details), canPrepare) { scope.launch { recovery = library.recovery() } }
        }
        current?.let { operation ->
          ProductSection(stringResource(Res.string.current_operation)) {
            val readiness = transferReadiness(operation, owner, capability, gate, enabled, foreground, busy)
            Text(stringResource(transferPhaseLabel(operation.transfer.phase)), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (operation.kind == TransferKind.EXPORT || operation.kind == TransferKind.BACKUP)
                Text(stringResource(Res.string.if_writing_is_cancelled_or_interrupted_the))
            Text(stringResource(Res.string.archive_entries_checked_1_s_bytes_2, operation.transfer.processedEntries, operation.transfer.processedBytes))
            if (operation.transfer.phase == TransferPhase.AWAITING_CONFIRMATION && !readiness.fresh)
                Text(stringResource(Res.string.after_selecting_a_file_sign_in_again))
            operation.preview?.let { preview ->
                preview.name?.let { Text(stringResource(Res.string.board_1_s, it)) }
                preview.board?.let {
                    Text(stringResource(Res.string.pages_1_s_cards_2_s_images, it.pages, it.cards, it.images, it.recordings, it.mediaBytes, if (it.layoutNormalized) stringResource(Res.string.grid_normalized_for_the_app) else ""))
                    if (it.ignoredInactiveMediaReferences > 0) Text(stringResource(Res.string.inactive_empty_space_attachments_1_s_as, it.ignoredInactiveMediaReferences))
                }
                preview.snapshot?.let { Text(stringResource(Res.string.boards_1_s_drafts_2_s_retained, it.boards, it.drafts, it.retained, it.assets, it.assetBytes)) }
                preview.expectedLibraryRevision?.let { Text(stringResource(Res.string.confirmation_applies_to_library_version_1_s, it)) }
                val canConfirm = readiness.canConfirm
                when (operation.kind) {
                    TransferKind.IMPORT -> {
                        Action(stringResource(Res.string.confirm_import_of_1_s_as_a, preview.name ?: ""), canConfirm) { confirm(TransferConfirmation.ImportNew) }
                        listing.items.filter { it.kind == LibraryEntryKind.BOARD }.forEach { entry ->
                            Action(stringResource(Res.string.replace_1_s_with_the_imported_board, entry.name), canConfirm, true) { replace = entry }
                        }
                        replace?.let { entry ->
                            Text(stringResource(Res.string.completely_replace_1_s_version_2_s, entry.name, entry.revision, preview.name ?: ""))
                            Action(stringResource(Res.string.confirm_replacement_of_1_s, entry.name), canConfirm, true) { confirm(TransferConfirmation.ImportReplace(entry.id, entry.revision)) }
                            Action(stringResource(Res.string.do_not_replace), !busy) { replace = null }
                        }
                    }
                    TransferKind.EXPORT, TransferKind.BACKUP -> {
                        Text(stringResource(Res.string.choosing_a_location_in_the_system_dialog))
                        Action(stringResource(Res.string.confirm_writing_an_unencrypted_1_s, if (operation.kind == TransferKind.EXPORT) stringResource(Res.string.board_file) else stringResource(Res.string.whole_library_archive)), canConfirm) { confirm(TransferConfirmation.WriteFile) }
                    }
                    TransferKind.RESTORE -> {
                        Text(stringResource(Res.string.restoration_will_replace_the_entire_library_settings))
                        Action(stringResource(Res.string.confirm_replacement_of_the_entire_library_from), canConfirm, true) { confirm(TransferConfirmation.ReplaceWholeLibrary) }
                    }
                    TransferKind.RETURN_TO_RECOVERY -> {
                        Text(stringResource(Res.string.returning_will_replace_the_whole_current_library))
                        Action(stringResource(Res.string.confirm_returning_the_whole_library_to_the), canConfirm, true) { confirm(TransferConfirmation.ReturnToRecovery) }
                    }
                    TransferKind.DELETE_RECOVERY -> Action(stringResource(Res.string.confirm_permanent_deletion_of_the_recovery_point), canConfirm, true) { confirm(TransferConfirmation.DeleteRecovery) }
                }
            }
            operation.transfer.problem?.let { Feedback(OperationResult.Failed(it)) }
            if (!terminal && operation.transfer.phase != TransferPhase.UNCONFIRMED) Action(stringResource(Res.string.cancel_current_operation), readiness.canCancel) {
                scope.launch { result = transfer.cancel(owner, operation.transfer.id, capability) }
            }
          }
        }
        Feedback(result)
        Text(stringResource(Res.string.after_moving_to_the_background_the_operation))
    }
}
