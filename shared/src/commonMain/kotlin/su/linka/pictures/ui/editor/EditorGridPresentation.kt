package su.linka.pictures.ui.editor

import su.linka.pictures.domain.BoardCell
import su.linka.pictures.domain.CellKind
import su.linka.pictures.domain.BoardPage
import su.linka.pictures.domain.EditorDraft
import su.linka.pictures.domain.EditorForm

internal fun editorCellExtent(available: Float, count: Int, minimum: Float): Float =
    ((available - 4f * (count - 1)) / count).coerceAtLeast(minimum)

internal fun editorCellLabel(cell: BoardCell?): String = when (cell?.kind) {
    CellKind.CONTENT -> cell.card!!.label.ifEmpty { "Карточка без подписи" }
    CellKind.SPACE -> "Пробел"
    CellKind.EMPTY, null -> "Создать карточку"
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
