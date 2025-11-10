package su.linka.pictures.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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

    private lateinit var context: Context
    private lateinit var workingDir: java.io.File
    private lateinit var grid: OutputGrid
    private lateinit var set: su.linka.pictures.Set

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val card = Card(0, title = "Hello", cardType = 0)
        val (_, createdSet, dir) = ComponentTestUtils.createSet(rows = 1, columns = 1, cards = listOf(card))
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
