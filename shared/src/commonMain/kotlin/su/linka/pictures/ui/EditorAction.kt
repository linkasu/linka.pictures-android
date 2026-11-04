package su.linka.pictures.ui

sealed interface EditorAction {
    data class EditCard(val absoluteIndex: Int) : EditorAction
    data class Page(val index: Int) : EditorAction
    data object NewPage : EditorAction
    data class Resize(val rows: Int, val columns: Int) : EditorAction
    data class WithoutSpace(val enabled: Boolean) : EditorAction
    data object Save : EditorAction
    data object Back : EditorAction
    data object Export : EditorAction
}
