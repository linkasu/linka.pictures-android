package su.linka.pictures.ui.communication

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import su.linka.pictures.application.communication.CommunicationState
import su.linka.pictures.domain.*
import kotlin.test.*

class CommunicationDragGestureTest {
    private fun state(mode: InteractionMode = InteractionMode.COMPOSE): CommunicationState {
        val board = BoardId("board")
        val page = PageId("page")
        return CommunicationState(
            message = MessageDraft(mode = mode, cursor = BoardCursor(board, page)),
            board = PublishedBoard(BoardDocument(board, "Board", listOf(BoardPage(page, 1, 1, emptyList()))), 1),
        )
    }

    @Test fun optInAndComposeAreBothRequiredAndInactivePresentationCannotDrag() {
        assertNull(communicationDragGesture(state(), enabled = true, dragToCompose = false))
        assertNull(communicationDragGesture(state(), enabled = false, dragToCompose = true))
        assertNull(communicationDragGesture(state(InteractionMode.DIRECT), enabled = true, dragToCompose = true))
        assertNotNull(communicationDragGesture(state(), enabled = true, dragToCompose = true))
    }

    @Test fun onlyMessageAcceptsOneDropAndCancelOrRepeatDoesNotActivateCard() {
        val drag = assertNotNull(communicationDragGesture(state(), true, true))
        val message = Rect(48f, 56f, 300f, 256f)
        assertNull(drag.drop(message)) // Taps never create a drag.
        drag.start(3, Offset(100f, 400f))
        drag.move(Offset(100f, 100f))
        assertEquals(3, drag.drop(message))
        assertNull(drag.drop(message))
        for (outside in listOf(Offset(20f, 100f), Offset(320f, 100f), Offset(100f, 400f))) {
            drag.start(3, outside) // Speak, remove/clear and grid are not targets.
            assertNull(drag.drop(message))
        }
        drag.start(3, Offset(100f, 100f))
        drag.cancel()
        assertNull(drag.drop(message))
        drag.start(3, Offset(100f, 100f))
        assertNull(drag.drop(Rect.Zero))
        assertNull(drag.drop(message))
    }
}
