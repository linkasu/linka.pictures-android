package su.linka.pictures.ui.library

import org.jetbrains.compose.resources.StringResource
import su.linka.pictures.resources.*
import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal fun libraryTileMinWidth(fontScale: Float): Float = 160f * fontScale.coerceAtLeast(1f)

internal fun libraryEntryDescription(entry: LibraryEntrySummary): StringResource =
    if (entry.kind == LibraryEntryKind.RETAINED_ARCHIVE) Res.string.retained_unreadable_archive_1_s
    else entry.cardCount?.let { Res.string.cards_1_s } ?: Res.string.card_count_unknown

internal fun libraryCoverImage(board: BoardDocument): AssetId? = board.pages.asSequence()
    .flatMap { it.cells.asSequence() }.mapNotNull { it.card?.image }.firstOrNull()

internal fun needsLibraryAuthorization(result: OperationResult<*>?): Boolean =
    result is OperationResult.Failed && result.problem.code == ProblemCode.UNAUTHORIZED

internal fun libraryDecisionFinished(result: OperationResult<*>): Boolean =
    result is OperationResult.Completed || result == OperationResult.UnconfirmedCommit
