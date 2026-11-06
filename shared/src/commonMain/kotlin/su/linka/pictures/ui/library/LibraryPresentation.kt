package su.linka.pictures.ui.library

import su.linka.pictures.application.*
import su.linka.pictures.application.ports.*
import su.linka.pictures.domain.*

internal const val LibraryColumns = 3

internal fun libraryEntryDescription(entry: LibraryEntrySummary): String =
    if (entry.kind == LibraryEntryKind.RETAINED_ARCHIVE) "Сохранённый нечитаемый архив. ${entry.problem?.code ?: "Открытие недоступно"}"
    else entry.cardCount?.let { "Карточек: $it" } ?: "Количество карточек неизвестно"

internal fun libraryCoverImage(board: BoardDocument): AssetId? = board.pages.asSequence()
    .flatMap { it.cells.asSequence() }.mapNotNull { it.card?.image }.firstOrNull()

internal fun needsLibraryAuthorization(result: OperationResult<*>?): Boolean =
    result is OperationResult.Failed && result.problem.code == ProblemCode.UNAUTHORIZED

internal fun libraryDecisionFinished(result: OperationResult<*>): Boolean =
    result is OperationResult.Completed || result == OperationResult.UnconfirmedCommit
