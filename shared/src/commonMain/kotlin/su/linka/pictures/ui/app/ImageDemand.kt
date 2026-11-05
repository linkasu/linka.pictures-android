package su.linka.pictures.ui.app

import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.domain.*

internal fun BoardPage.imageIds(): Set<AssetId> = cells.mapNotNull { it.card?.image }.toSet()

internal fun CommunicationState.pageImageIds(): Set<AssetId> {
    val cursor = message.cursor ?: return emptySet()
    return board?.takeIf { it.document.id == cursor.boardId }?.document?.pages
        ?.firstOrNull { it.id == cursor.pageId }?.imageIds().orEmpty()
}

internal fun EditorDraft.imageIds(): Set<AssetId> = when (val current = form) {
    is EditorForm.Card -> listOfNotNull(current.image).toSet()
    is EditorForm.Grid -> document.pages.firstOrNull { it.id == current.pageId }?.imageIds().orEmpty()
    // shortcut: selected page/visible rows are private to EditorScreen; COORD must expose their demand, never load all pages here.
    null -> emptySet()
}
