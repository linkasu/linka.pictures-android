package su.linka.pictures.ui.settings

import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.PortableSettings

internal data class SettingsSnapshot(val listing: LibraryListing, val value: PortableSettings)

internal suspend fun readSettingsSnapshot(library: LibraryStore): OperationResult<SettingsSnapshot> {
    val captured = library.listing.value
    return when (val read = library.settings()) {
        is OperationResult.Completed -> {
            val current = library.listing.value
            if (captured.epoch == current.epoch && captured.revision == current.revision &&
                captured.refreshProblem == null && current.refreshProblem == null)
                OperationResult.Completed(SettingsSnapshot(captured, read.value))
            else OperationResult.Failed(Problem(ProblemCode.STALE_REQUEST))
        }
        is OperationResult.Failed -> read
        OperationResult.Cancelled -> OperationResult.Cancelled
        OperationResult.UnconfirmedCommit -> OperationResult.UnconfirmedCommit
    }
}

/** A read failure cannot undo a known commit; null means no automatic reload was requested. */
internal suspend fun reloadSettingsAfterSave(library: LibraryStore, result: OperationResult<Long>,
    value: PortableSettings, onSaved: (PortableSettings) -> Unit): OperationResult<SettingsSnapshot>? {
    if (result !is OperationResult.Completed) return null
    onSaved(value)
    return readSettingsSnapshot(library)
}
