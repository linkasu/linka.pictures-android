package su.linka.pictures.ui

sealed interface BoardAction {
    data class SelectCard(val absoluteIndex: Int) : BoardAction
    /** Zero-based destination page, not a delta. */
    data class Page(val index: Int) : BoardAction
    data object Backspace : BoardAction
    data object Clear : BoardAction
    data object Speak : BoardAction
    data object Settings : BoardAction
    data object Close : BoardAction
}
