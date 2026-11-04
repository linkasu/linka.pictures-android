package su.linka.pictures.components

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.linka.pictures.Card

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OutputGridTest {

    private lateinit var context: android.content.Context
    private lateinit var workingDir: java.io.File
    private lateinit var grid: OutputGrid
    private lateinit var set: su.linka.pictures.Set

    @Before
    fun setUp() {
        val card = Card(0, title = "Hello", cardType = 0)
        val (createdContext, createdSet, dir) = ComponentTestUtils.createSet(rows = 1, columns = 1, cards = listOf(card))
        context = createdContext
        set = createdSet
        workingDir = dir
        grid = OutputGrid(context)
        grid.setSet(set, output = true)
    }

    @After
    fun tearDown() {
        ComponentTestUtils.cleanup(workingDir)
    }

    @Test
    fun addCard_appendsVisibleGridButton() {
        val card = set.getManifest().cards.first()
        grid.addCard(card)

        assertEquals(1, grid.childCount)
    }

    @Test
    fun addCard_nonStandardCardIgnored() {
        val spaceCard = Card(1, cardType = 1)
        grid.addCard(spaceCard)

        assertEquals(0, grid.childCount)
    }

    @Test
    fun addCard_spaceThenNormalCard_rendersNormalCard() {
        val card = set.getManifest().cards.first()
        grid.addCard(Card(1, cardType = 1))

        grid.addCard(card)

        assertEquals(1, grid.childCount)
        assertEquals(card, (grid.getChildAt(0) as GridButton).getCard())
    }

    @Test
    fun addCard_mixedCards_preservesVisibleOrder() {
        val first = set.getManifest().cards.first()
        val second = first.copy(id = 4, title = "World")
        listOf(Card(1, cardType = 1), first, Card(2, cardType = 2),
            Card(3, cardType = 3), second, Card(5, cardType = 1)).forEach(grid::addCard)

        assertEquals(2, grid.childCount)
        assertEquals(first, (grid.getChildAt(0) as GridButton).getCard())
        assertEquals(second, (grid.getChildAt(1) as GridButton).getCard())
    }

    @Test
    fun backspace_mixedCards_removesStoredCardsIncludingSpaces() {
        val first = set.getManifest().cards.first()
        val second = first.copy(id = 2, title = "World")
        listOf(first, Card(1, cardType = 1), second, Card(3, cardType = 1))
            .forEach(grid::addCard)

        grid.backspace()
        assertEquals(2, grid.childCount)
        assertEquals(second, (grid.getChildAt(1) as GridButton).getCard())

        grid.backspace()
        assertEquals(1, grid.childCount)
        assertEquals(first, (grid.getChildAt(0) as GridButton).getCard())

        grid.backspace()
        assertEquals(1, grid.childCount)
        assertEquals(first, (grid.getChildAt(0) as GridButton).getCard())

        grid.backspace()
        grid.backspace()
        assertEquals(0, grid.childCount)
    }

    @Test
    fun clear_mixedCards_removesHiddenCardsBeforeNextAdd() {
        val card = set.getManifest().cards.first()
        listOf(Card(1, cardType = 1), card, Card(2, cardType = 2))
            .forEach(grid::addCard)

        grid.clear()
        assertEquals(0, grid.childCount)

        grid.addCard(card)
        assertEquals(1, grid.childCount)
        assertEquals(card, (grid.getChildAt(0) as GridButton).getCard())

        grid.backspace()
        assertEquals(0, grid.childCount)
    }

    @Test
    fun backspace_removesLastCard() {
        val card = set.getManifest().cards.first()
        grid.addCard(card)
        grid.addCard(card.copy(id = 1))

        grid.backspace()

        assertEquals(1, grid.childCount)
    }

    @Test
    fun clear_removesAllCards() {
        val card = set.getManifest().cards.first()
        grid.addCard(card)
        grid.addCard(card.copy(id = 1))

        grid.clear()

        assertEquals(0, grid.childCount)
    }
}
