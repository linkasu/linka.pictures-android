package su.linka.pictures.ui.editor

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import su.linka.pictures.application.authoring.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.card.CardEditorDialog
import su.linka.pictures.ui.card.CardEditorContent
import su.linka.pictures.ui.card.rememberCardEditorInput
import su.linka.pictures.ui.app.editorUsesInspector
import su.linka.pictures.ui.components.LabeledActionButton
import su.linka.pictures.ui.images.VisibleImageDemand
import su.linka.pictures.ui.components.ToolbarTitle
import su.linka.pictures.ui.app.ProductTopBar
import su.linka.pictures.ui.components.toolbarHeightForFontScale
import su.linka.pictures.ui.theme.LocalLinkaPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    state: AuthoringState,
    ownerId: String,
    capability: AssistantCapability?,
    ids: IdSource,
    onCommand: (AuthoringCommand) -> Unit,
    images: Map<AssetId, ImageBitmap> = emptyMap(),
    modifier: Modifier = Modifier,
    onImagesNeeded: ((Set<AssetId>) -> Unit)? = null,
) {
    val newBoardName = stringResource(Res.string.new_board)
    val backWithSavedDraftLabel = stringResource(Res.string.back_with_saved_draft)
    val saveAndExitLabel = stringResource(Res.string.save_and_exit)
    val finishTheFormFirst214Label = stringResource(Res.string.finish_the_form_first_214)
    val editorMenuLabel = stringResource(Res.string.editor_menu)
    val previousPageLabel = stringResource(Res.string.previous_page)
    val nextPageLabel = stringResource(Res.string.next_page)
    val pageActionsLabel = stringResource(Res.string.page_actions)
    val saveStatus = stringResource(draftSaveLabel(state.savePhase))
    val dispatch: (AuthoringAction) -> Unit = { onCommand(AuthoringCommand(CommandId(ids.next()), ownerId, capability, it)) }
    val dispatchEdited: (AuthoringAction, (AuthoringState) -> Unit) -> Unit = { action, onHandled ->
        onCommand(AuthoringCommand(CommandId(ids.next()), ownerId, capability, action, onHandled))
    }
    val draft = state.draft
    val document = draft?.document
    val enabled = state.editable()
    val structural = enabled && draft?.form == null && !state.mediaBusy
    val palette = LocalLinkaPalette.current
    var selectedPage by rememberSaveable(draft?.id?.value) { mutableStateOf<String?>(null) }
    var moveSource by remember(draft?.id) { mutableStateOf<Pair<PageId, Int>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable(draft?.id?.value) { mutableStateOf(false) }
    var showSaveStatus by remember { mutableStateOf(false) }
    val page = editorPage(draft, selectedPage)
    val pageIndex = document?.pages?.indexOf(page) ?: -1
    val gridForm = draft?.form as? EditorForm.Grid
    val cardInput = rememberCardEditorInput(state, ownerId, dispatchEdited)
    LaunchedEffect(draft?.id, draft?.form != null, page?.id) {
        if (draft?.form != null) selectedPage = page?.id?.value
    }
    VisibleImageDemand(Triple(state.epoch, ownerId, draft?.id), onImagesNeeded) {
        Surface(modifier.fillMaxSize(), color = palette.background, contentColor = palette.text) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
             val fontScale = LocalDensity.current.fontScale
             // IME resizes the form, not its host; moving a focused field between hosts dismisses the keyboard.
             val inspector = editorUsesInspector(maxWidth.value, maxHeight.value, fontScale)
             Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                ProductTopBar(modifier = Modifier.heightIn(min = toolbarHeightForFontScale(56f, LocalDensity.current.fontScale).dp)
                    .semantics { stateDescription = saveStatus },
                    title = document?.name ?: stringResource(Res.string.board_editor),
                    navigationIcon = {
                        IconButton({ dispatch(AuthoringAction.Close) }, enabled = state.phase != AuthoringPhase.UNCONFIRMED,
                            modifier = Modifier.semantics { contentDescription = backWithSavedDraftLabel }) { EditorIcon(EditorSymbol.BACK) }
                    }, actions = {
                         TextButton({ dispatch(AuthoringAction.Apply) },
                            enabled = structural && state.phase != AuthoringPhase.CONFLICT,
                            modifier = Modifier.semantics {
                                 contentDescription = saveAndExitLabel
                                if (draft?.form != null) stateDescription = finishTheFormFirst214Label
                             }) { Text(saveAndExitLabel) }
                        Box {
                            IconButton({ menu = true }, modifier = Modifier.semantics { contentDescription = editorMenuLabel }) { EditorIcon(EditorSymbol.MORE) }
                            DropdownMenu(menu, { menu = false }) {
                                 DropdownMenuItem({ menu = false; showSettings = true }, enabled = document != null && draft.form !is EditorForm.Card) { Text(stringResource(Res.string.board_options)) }
                                DropdownMenuItem({ menu = false; showSaveStatus = true }) { Text(stringResource(Res.string.draft_status)) }
                                DropdownMenuItem({ menu = false; confirmDiscard = true }, enabled = enabled) { Text(stringResource(Res.string.discard_unsaved_changes)) }
                                DropdownMenuItem({ menu = false; confirmDelete = true }, enabled = structural && state.durableVersion != null) { Text(stringResource(Res.string.delete_draft)) }
                            }
                        }
                    })
                val urgentSave = state.savePhase == DraftSavePhase.FAILED || state.savePhase == DraftSavePhase.UNCONFIRMED
                AuthoringFeedback(state, dispatch, showSaveStatus = urgentSave)
                if (document == null) {
                    Text(if (state.phase == AuthoringPhase.LOADING) stringResource(Res.string.loading_draft) else stringResource(Res.string.create_a_board_or_open_a_draft), Modifier.padding(8.dp))
                    LabeledActionButton(newBoardName, state.phase == AuthoringPhase.IDLE, false, { dispatch(AuthoringAction.NewBoard(newBoardName)) })
                 } else if (page != null) {
                     Row(Modifier.weight(1f).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                         Surface(Modifier.weight(1f).fillMaxHeight(), color = palette.surface,
                             border = BorderStroke(1.dp, palette.border), shape = MaterialTheme.shapes.medium) {
                             Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                 Text(stringResource(Res.string.cards), style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
                                 if (moveSource != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                     Text(stringResource(Res.string.choose_a_destination_cell_it_can_be), Modifier.weight(1f))
                                     TextButton({ moveSource = null }) { Text(stringResource(Res.string.cancel)) }
                                 }
                                 key(page.id) {
                                     EditorPageCells(page, structural, moveSource, { moveSource = it }, dispatch, images,
                                         Modifier.weight(1f).fillMaxWidth(), activePosition = (draft.form as? EditorForm.Card)?.slotIndex)
                                 }
                                 Divider(color = palette.border)
                                 FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                                     verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                     IconButton({ selectedPage = document.pages[pageIndex - 1].id.value }, enabled = pageIndex > 0 && draft.form == null,
                                         modifier = Modifier.semantics { contentDescription = previousPageLabel }) { EditorIcon(EditorSymbol.PREVIOUS) }
                                     Text(stringResource(Res.string.page_1_s_of_2_s, pageIndex + 1, document.pages.size), Modifier.heightIn(min = 48.dp).wrapContentHeight(),
                                         style = MaterialTheme.typography.body2)
                                     IconButton({ selectedPage = document.pages[pageIndex + 1].id.value }, enabled = pageIndex < document.pages.lastIndex && draft.form == null,
                                         modifier = Modifier.semantics { contentDescription = nextPageLabel }) { EditorIcon(EditorSymbol.NEXT) }
                                     OutlinedButton({ dispatch(AuthoringAction.AddPage(page.rows, page.columns)) }, enabled = structural) { Text(stringResource(Res.string.page)) }
                                     Box {
                                         IconButton({ pageMenu = true }, enabled = structural,
                                             modifier = Modifier.semantics { contentDescription = pageActionsLabel }) { EditorIcon(EditorSymbol.MORE) }
                                         DropdownMenu(pageMenu, { pageMenu = false }) {
                                             DropdownMenuItem({ pageMenu = false; dispatch(AuthoringAction.MovePage(page.id, pageIndex - 1)) }, enabled = structural && pageIndex > 0) { Text(stringResource(Res.string.move_page_earlier)) }
                                             DropdownMenuItem({ pageMenu = false; dispatch(AuthoringAction.MovePage(page.id, pageIndex + 1)) }, enabled = structural && pageIndex < document.pages.lastIndex) { Text(stringResource(Res.string.move_page_later)) }
                                             DropdownMenuItem({ pageMenu = false; moveSource = null; dispatch(AuthoringAction.RemovePage(page.id)) }, enabled = structural && document.pages.size > 1) { Text(stringResource(Res.string.delete_page_keeping_cells)) }
                                         }
                                     }
                                 }
                             }
                         }
                         if (inspector) Surface(Modifier.width(360.dp * fontScale.coerceAtLeast(1f)).fillMaxHeight(), color = palette.surface,
                             border = BorderStroke(1.dp, palette.border), shape = MaterialTheme.shapes.medium) {
                             if (cardInput != null) CardEditorContent(state, cardInput, dispatch, images, Modifier.fillMaxSize().padding(16.dp), fillHeight = true)
                             else Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                 Text(stringResource(Res.string.card), style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
                                 Text(stringResource(Res.string.select_a_card_on_the_board_to))
                             }
                         }
                     }
                }
            }
             if ((showSettings || gridForm != null) && document != null && draft.form !is EditorForm.Card)
                 EditorSettingsDialog(state, ownerId, enabled, dispatchEdited, { showSettings = false }, page, dispatch, { moveSource = null })
             if (!inspector && cardInput != null) CardEditorDialog(state, ownerId, dispatch, dispatchEdited, images, input = cardInput)
            if (showSaveStatus) AlertDialog(onDismissRequest = { showSaveStatus = false },
                title = { Text(stringResource(Res.string.draft_status)) }, text = { Column {
                    Text(stringResource(draftSaveLabel(state.savePhase)))
                     Text(stringResource(Res.string.autosave_saves_only_the_draft_save_and))
                } }, confirmButton = { TextButton({ showSaveStatus = false }) { Text(stringResource(Res.string.understood)) } })
            if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
                title = { Text(stringResource(Res.string.discard_unsaved_changes_234)) },
                text = { Text(stringResource(Res.string.the_editor_will_close_the_last_successfully)) },
                confirmButton = { TextButton({ confirmDiscard = false; dispatch(AuthoringAction.ForceDiscard) }) { Text(stringResource(Res.string.discard_and_close)) } },
                dismissButton = { TextButton({ confirmDiscard = false }) { Text(stringResource(Res.string.stay)) } })
            if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
                title = { Text(stringResource(Res.string.delete_draft_238)) }, text = { Text(stringResource(Res.string.the_published_board_will_not_change_the)) },
                confirmButton = { TextButton({ confirmDelete = false; dispatch(AuthoringAction.DeleteDraft) }) { Text(stringResource(Res.string.delete_draft_240)) } },
                 dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(Res.string.cancel)) } })
            }
        }
    }
}
