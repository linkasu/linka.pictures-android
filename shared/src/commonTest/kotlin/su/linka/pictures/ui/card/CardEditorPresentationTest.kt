package su.linka.pictures.ui.card

import kotlin.test.*
import su.linka.pictures.domain.*

class CardEditorPresentationTest {
    private val image = AssetId("a".repeat(64))
    private val form = EditorForm.Card(PageId("page"), 0, CardId("card"),
        label = "Название", speechChoice = SpeechChoice.RECORDING, recording = null, image = image)

    @Test fun onlyContentShowsLabelAndMediaFieldsRegardlessOfHiddenSpeechValidation() {
        assertTrue(cardContentVisible(form))
        assertFalse(cardContentVisible(form.copy(kind = CellKind.SPACE)))
        assertFalse(cardContentVisible(form.copy(kind = CellKind.EMPTY)))
    }

    @Test fun hiddenImageIsNotDemandedAndReturnsWhenContentIsSelectedAgain() {
        assertEquals(setOf(image), cardImageDemand(form))
        listOf(CellKind.SPACE, CellKind.EMPTY).forEach { kind ->
            val hidden = form.copy(kind = kind)
            assertEquals(emptySet(), cardImageDemand(hidden))
            assertEquals(setOf(image), cardImageDemand(hidden.copy(kind = CellKind.CONTENT)))
        }
        assertEquals(emptySet(), cardImageDemand(form.copy(image = null)))
    }
}
