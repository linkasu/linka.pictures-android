package su.linka.pictures.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import su.linka.pictures.Card

class PhraseTextTest {
    @Test
    fun joinsKeyboardCardsWithExplicitSpacesAndSkipsPlaceholders() {
        val phrase = listOf(
            Card(id = 1, title = "При"),
            Card(id = 2, title = "вет"),
            Card(id = 3, title = "not a space label", cardType = 1),
            Card(id = 4, title = "hidden", cardType = 2),
            Card(id = 5, title = "hidden", cardType = 3),
            Card(id = 6, title = null),
            Card(id = 7, title = "世界 👩🏽‍💻 e\u0301"),
        )
        assertEquals("Привет 世界 👩🏽‍💻 e\u0301", phraseText(phrase))
    }

    @Test
    fun preservesExplicitWhitespaceAndHandlesEmptyPhrase() {
        assertEquals("", phraseText(emptyList()))
        assertEquals("  А\n ", phraseText(listOf(
            Card(id = 1, cardType = 1),
            Card(id = 2, cardType = 1),
            Card(id = 3, title = "А\n"),
            Card(id = 4, cardType = 1),
        )))
    }
}
