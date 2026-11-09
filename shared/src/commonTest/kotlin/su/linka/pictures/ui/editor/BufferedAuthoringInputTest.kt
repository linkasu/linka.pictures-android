package su.linka.pictures.ui.editor

import su.linka.pictures.application.authoring.AuthoringAction
import kotlin.test.*

class BufferedAuthoringInputTest {
    @Test fun delayedIntermediateEchoesCannotReplaceImmediateInput() {
        val input = BufferedAuthoringInput("", 0)
        val first = input.edit("L")
        val latest = input.edit("LINKa-EDITOR-6F9C")
        input.acceptExternal("L", 1)
        input.handled(first, "L", 1)
        assertEquals("LINKa-EDITOR-6F9C", input.value)
        assertTrue(input.pending)
        input.handled(latest, "LINKa-EDITOR-6F9C", 2)
        assertFalse(input.pending)
    }

    @Test fun equalTextIsNotAnAcknowledgement() {
        val input = BufferedAuthoringInput("", 0)
        val first = input.edit("A")
        input.edit("AB")
        val latest = input.edit("A")
        input.acceptExternal("A", 1)
        input.handled(first, "A", 1)
        assertTrue(input.pending)
        input.handled(latest, "A", 3)
        assertFalse(input.pending)
        assertEquals("A", input.value)
    }

    @Test fun callbackAheadOfCompositionRejectsOlderModelVersions() {
        val input = BufferedAuthoringInput("A", 1)
        val token = input.edit("AB")
        input.handled(token, "AB", 3)
        input.acceptExternal("A", 1)
        input.acceptExternal("intermediate", 2)
        assertEquals("AB", input.value)
        input.acceptExternal("external edit", 4)
        input.acceptExternal("AB", 3)
        assertEquals("external edit", input.value)
    }

    @Test fun latestRefusalResetsToActuallyAdmittedValue() {
        val input = BufferedAuthoringInput("admitted", 2)
        val token = input.edit("rejected")
        input.handled(token, "admitted", 2)
        assertEquals("admitted", input.value)
        assertFalse(input.pending)
        input.acceptExternal("older", 1)
        assertEquals("admitted", input.value)
    }

    @Test fun wholePayloadPreservesBothFieldsBeforeAnyEcho() {
        val input = BufferedAuthoringInput(AuthoringAction.ChangeGrid("3", "3"), 0)
        val rows = input.edit(input.value.copy(rowsText = "12"))
        val columns = input.edit(input.value.copy(columnsText = "8"))
        val expected = AuthoringAction.ChangeGrid("12", "8")
        assertEquals(expected, input.value)
        input.handled(rows, AuthoringAction.ChangeGrid("12", "3"), 1)
        assertEquals(expected, input.value)
        input.handled(columns, expected, 2)
        assertEquals(expected, input.value)
        assertFalse(input.pending)
    }

    @Test fun explicitCancelInvalidatesPendingAcknowledgement() {
        val input = BufferedAuthoringInput("initial", 0)
        val token = input.edit("cancelled")
        input.discardPending()
        input.acceptExternal("initial", 2)
        input.handled(token, "cancelled", 1)
        assertEquals("initial", input.value)
        assertFalse(input.pending)
    }

    @Test fun newAuthorityInstanceDoesNotCarryPendingInputOrVersionFloor() {
        val old = BufferedAuthoringInput("old", 9)
        val token = old.edit("pending old")
        val current = BufferedAuthoringInput("new authority", 0)
        current.handled(token, "old accepted", 10)
        current.acceptExternal("new accepted", 1)
        assertEquals("new accepted", current.value)
        assertFalse(current.pending)
    }

    @Test fun onlyLatestTokenMattersAfterManyEdits() {
        val input = BufferedAuthoringInput("", 0)
        val obsolete = input.edit("obsolete")
        var latest: Any = obsolete
        repeat(10_000) { latest = input.edit(it.toString()) }
        input.handled(obsolete, "obsolete", 1)
        assertEquals("9999", input.value)
        assertTrue(input.pending)
        input.handled(latest, "9999", 10_001)
        assertFalse(input.pending)
        input.handled(obsolete, "obsolete", 1)
        assertEquals("9999", input.value)
    }

    @Test fun settledInputMirrorsAuthoritativeSameVersionChanges() {
        val input = BufferedAuthoringInput("initial", 2)
        input.acceptExternal("actual", 2)
        assertEquals("actual", input.value)
        val token = input.edit("next")
        input.handled(token, "next", 3)
        input.handled(token, "duplicate callback", 3)
        assertEquals("next", input.value)
    }
}
