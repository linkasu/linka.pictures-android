package su.linka.pictures.application.communication

import su.linka.pictures.domain.*

sealed interface CommunicationCommand {
    val id: CommandId

    data class Select(override val id: CommandId, val pageId: PageId, val position: Int) : CommunicationCommand {
        init { require(position >= 0) }
    }
    data class OpenBoard(
        override val id: CommandId,
        val boardId: BoardId,
        val pageId: PageId? = null,
        val expectedRevision: Long? = null,
    ) : CommunicationCommand
    data class ChangePage(override val id: CommandId, val pageId: PageId) : CommunicationCommand
    data class SetMode(override val id: CommandId, val mode: InteractionMode) : CommunicationCommand
    data class InsertText(override val id: CommandId, val text: String) : CommunicationCommand
    data class Backspace(override val id: CommandId) : CommunicationCommand
    data class Clear(override val id: CommandId) : CommunicationCommand
    data class UndoClear(override val id: CommandId) : CommunicationCommand
    data class Speak(override val id: CommandId) : CommunicationCommand
    data class Stop(override val id: CommandId) : CommunicationCommand
    data class CloseBoard(override val id: CommandId) : CommunicationCommand
}
