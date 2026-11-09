package su.linka.pictures.ui.editor

import org.jetbrains.compose.resources.StringResource
import su.linka.pictures.resources.*
import su.linka.pictures.domain.BoardCell
import su.linka.pictures.domain.CellKind
import su.linka.pictures.domain.BoardPage
import su.linka.pictures.domain.EditorDraft
import su.linka.pictures.domain.EditorForm

internal fun editorCellExtent(available: Float, count: Int, minimum: Float): Float =
    ((available - 8f * (count - 1)) / count).coerceAtLeast(minimum)

internal fun editorSettingsCanDismiss(form: EditorForm?, detailsPending: Boolean, gridPending: Boolean): Boolean =
    form == null && !detailsPending && !gridPending

internal fun editorCellLabel(cell: BoardCell?): StringResource? = when (cell?.kind) {
    CellKind.CONTENT -> if (cell.card!!.label.isEmpty()) Res.string.unlabelled_card else null
    CellKind.SPACE -> Res.string.space
    CellKind.EMPTY, null -> Res.string.create_card
}

internal fun editorPage(draft: EditorDraft?, selectedPage: String?): BoardPage? {
    val formPage = when (val form = draft?.form) {
        is EditorForm.Card -> form.pageId
        is EditorForm.Grid -> form.pageId
        null -> null
    }
    return draft?.document?.pages?.firstOrNull { it.id == formPage }
        ?: draft?.document?.pages?.firstOrNull { it.id.value == selectedPage }
        ?: draft?.document?.pages?.firstOrNull()
}
