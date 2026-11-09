package su.linka.pictures.application.communication

import kotlinx.coroutines.flow.MutableStateFlow
import su.linka.pictures.domain.*

/** Transient input snapshot: cancellation must also reach a Select already queued in the actor. */
class CommunicationSelectionSource private constructor(
    val boardId: BoardId,
    val revision: Long,
    val stamp: MessageStamp,
    val pageId: PageId,
    val mode: InteractionMode,
) {
    private val cancelled = MutableStateFlow(false)

    fun cancel() { cancelled.value = true }

    internal fun matches(state: CommunicationState): Boolean = !cancelled.value &&
        mode == InteractionMode.COMPOSE && state.message.mode == mode && state.stamp == stamp &&
        state.board?.document?.id == boardId && state.board.revision == revision &&
        state.message.cursor?.boardId == boardId && state.message.cursor.pageId == pageId

    companion object {
        fun capture(state: CommunicationState): CommunicationSelectionSource? {
            val board = state.board ?: return null
            val cursor = state.message.cursor ?: return null
            if (state.message.mode != InteractionMode.COMPOSE || cursor.boardId != board.document.id) return null
            return CommunicationSelectionSource(board.document.id, board.revision, state.stamp, cursor.pageId, state.message.mode)
        }
    }
}
