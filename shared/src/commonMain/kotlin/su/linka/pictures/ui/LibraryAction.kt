package su.linka.pictures.ui

sealed interface LibraryAction {
    /** Null dismisses the controlled set menu. */
    data class Options(val set: SetSummary?) : LibraryAction
    data class Open(val set: SetSummary) : LibraryAction
    data class Edit(val set: SetSummary) : LibraryAction
    data class Rename(val set: SetSummary) : LibraryAction
    data class Delete(val set: SetSummary) : LibraryAction
    data class Export(val set: SetSummary) : LibraryAction
    data object Create : LibraryAction
    data object Import : LibraryAction
    data object Settings : LibraryAction
}
