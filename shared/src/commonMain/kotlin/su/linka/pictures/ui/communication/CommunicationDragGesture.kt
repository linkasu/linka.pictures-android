package su.linka.pictures.ui.communication

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import su.linka.pictures.application.communication.CommunicationSelectionSource
import su.linka.pictures.application.communication.CommunicationState

internal fun communicationDragGesture(state: CommunicationState, enabled: Boolean, dragToCompose: Boolean): CommunicationDragGesture? =
    if (enabled && dragToCompose) CommunicationSelectionSource.capture(state)?.let(::CommunicationDragGesture) else null

/** A long press owns one drop; ordinary taps still belong to the card's clickable semantics. */
internal class CommunicationDragGesture(val source: CommunicationSelectionSource) {
    private var position: Int? = null
    private var pointer = Offset.Zero

    fun start(position: Int, pointer: Offset) {
        this.position = position
        this.pointer = pointer
    }

    fun move(pointer: Offset) { this.pointer = pointer }
    fun cancel() { position = null }

    fun drop(messageBounds: Rect): Int? {
        val selected = position
        cancel()
        return selected?.takeIf { messageBounds.width > 0 && messageBounds.height > 0 && messageBounds.contains(pointer) }
    }
}
