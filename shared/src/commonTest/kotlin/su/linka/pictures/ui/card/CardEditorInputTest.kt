package su.linka.pictures.ui.card

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import su.linka.pictures.application.authoring.*
import su.linka.pictures.domain.*
import su.linka.pictures.ui.editor.BufferedAuthoringInput
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CardEditorInputTest {
    @Test fun switchWaitsForLatestInputAdmissionAndDoesNotQueueTargets() {
        val old = AuthoringAction.ChangeCard("A", "", SpeechChoice.NONE, true)
        val buffer = BufferedAuthoringInput(old, 0)
        val switched = mutableListOf<AuthoringAction.SwitchCard>()
        val input = CardEditorInput(buffer, {}, { switched += it })
        val first = buffer.edit(old.copy(label = "AB"))
        val last = buffer.edit(old.copy(label = "ABC"))
        val target = AuthoringAction.SwitchCard(PageId("page"), 1, 0)
        input.switchCard(target)
        input.switchCard(target.copy(position = 2))
        assertTrue(input.requestingSwitch)
        buffer.handled(first, old.copy(label = "AB"), 1)
        assertTrue(switched.isEmpty())
        buffer.handled(last, old.copy(label = "ABC"), 2)
        assertEquals(listOf(target), switched)
        assertFalse(input.requestingSwitch)
        val rejected = buffer.edit(old.copy(label = "Rejected"))
        input.switchCard(target)
        buffer.handled(rejected, old, 2)
        assertEquals(listOf(target), switched)
        val cancelled = buffer.edit(old)
        input.switchCard(target)
        input.discardPending()
        buffer.handled(cancelled, old, 3)
        assertEquals(listOf(target), switched)
    }

    private val form = EditorForm.Card(PageId("page"), 0, CardId("card"), label = "Initial")
    private val initial = AuthoringState(ownerId = "owner", epoch = 1, phase = AuthoringPhase.EDITING,
        draft = EditorDraft(DraftId("draft"), null, null,
            BoardDocument(BoardId("board"), "Board", listOf(BoardPage(form.pageId, 1, 1, emptyList()))), form))

    @Test fun switchCarriesAdmittedVersionEvenBeforeTheAcceptedModelRenders() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        try {
            var input: CardEditorInput? = null
            val actions = mutableListOf<AuthoringAction>()
            var handled: ((AuthoringState) -> Unit)? = null
            composition.setContent {
                val owner = rememberCardEditorInput(initial, "owner") { action, callback ->
                    actions += action
                    handled = callback
                }
                SideEffect { input = owner }
            }
            input!!.change(input!!.value.copy(label = "Последний символ"))
            val acknowledge = handled!!
            input!!.switchCard(AuthoringAction.SwitchCard(form.pageId, 1, initial.lifecycleVersion))
            assertEquals(1, actions.size)
            acknowledge(initial.copy(editVersion = 7, draft = initial.draft!!.copy(form = form.copy(label = "Последний символ"))))
            runCurrent()
            val switch = assertIs<AuthoringAction.SwitchCard>(actions.last())
            assertEquals(initial.stamp()!!.copy(editVersion = 7), switch.expected)
            assertEquals(initial.lifecycleVersion, switch.expectedLifecycle)
        } finally {
            composition.dispose()
            recomposer.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun resizeAndPhaseChangesKeepPendingInputAndItsAcknowledgement() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        try {
            var state = initial
            var wide = false
            var input: CardEditorInput? = null
            var callback: ((AuthoringState) -> Unit)? = null
            fun render() {
                composition.setContent {
                    val owner = rememberCardEditorInput(state, "owner") { _, handled -> callback = handled }
                    SideEffect { input = owner }
                    if (wide) key("inspector") { SideEffect {} } else key("dialog") { SideEffect {} }
                }
            }
            render()
            val original = assertNotNull(input)
            original.change(original.value.copy(label = "Pending"))
            wide = true
            state = state.copy(phase = AuthoringPhase.PAUSED)
            render()
            assertSame(original, input)
            assertEquals("Pending", input!!.value.label)
            assertTrue(input!!.pending)
            wide = false
            state = state.copy(phase = AuthoringPhase.EDITING)
            render()
            assertSame(original, input)
            assertEquals("Pending", input!!.value.label)
            assertTrue(input!!.pending)
            callback!!(state.copy(editVersion = 1, draft = state.draft!!.copy(form = form.copy(label = "Pending"))))
            runCurrent()
            assertFalse(input!!.pending)
            assertEquals("Pending", input!!.value.label)
            assertEquals(DraftSavePhase.UNSAVED, state.savePhase)

            original.change(original.value.copy(label = "Old pending"))
            val oldCallback = callback!!
            state = initial.copy(draft = initial.draft!!.copy(form = form.copy(cardId = CardId("other"))))
            render()
            val replacement = assertNotNull(input)
            assertNotSame(original, replacement)
            replacement.change(replacement.value.copy(label = "New pending"))
            oldCallback(initial.copy(editVersion = 2, draft = initial.draft.copy(form = form.copy(label = "Late"))))
            runCurrent()
            assertEquals("New pending", replacement.value.label)
            assertTrue(replacement.pending)
        } finally {
            composition.dispose()
            recomposer.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test fun acknowledgementRequiresEveryAuthorityAndFormIdentity() {
        val identity = CardInputIdentity("owner", 1, DraftId("draft"), form.pageId, form.cardId)
        assertTrue(identity.matches(initial))
        assertFalse(identity.matches(initial.copy(ownerId = "other")))
        assertFalse(identity.matches(initial.copy(epoch = 2)))
        assertFalse(identity.matches(initial.copy(draft = initial.draft!!.copy(id = DraftId("other")))))
        assertFalse(identity.matches(initial.copy(draft = initial.draft!!.copy(form = form.copy(pageId = PageId("other"))))))
        assertFalse(identity.matches(initial.copy(draft = initial.draft!!.copy(form = null))))
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
