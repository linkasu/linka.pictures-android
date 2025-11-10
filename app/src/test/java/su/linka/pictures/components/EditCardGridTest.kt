package su.linka.pictures.components

import android.widget.LinearLayout
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
class EditCardGridTest {

    private lateinit var context: android.content.Context
    private lateinit var workingDir: java.io.File
    private lateinit var set: su.linka.pictures.Set
    private lateinit var grid: EditCardGrid

    @Before
    fun setUp() {
        val cards = mutableListOf(Card(0, title = "Hello", cardType = 0))
        val (createdContext, createdSet, dir) = ComponentTestUtils.createSet(rows = 1, columns = 2, cards = cards)
        context = createdContext
        set = createdSet
        workingDir = dir
        grid = EditCardGrid(context)
        grid.setSet(set)
    }

    @After
    fun tearDown() {
        ComponentTestUtils.cleanup(workingDir)
    }

    @Test
    fun render_insertsPlaceholderCards() {
        val row = grid.getChildAt(0) as LinearLayout
        val first = row.getChildAt(0) as GridButton
        val second = row.getChildAt(1) as GridButton

        assertEquals(0, first.getCard()?.id)
        assertEquals(3, second.getCard()?.cardType)
    }

    @Test
    fun refresh_reflectsUpdatedManifest() {
        val manifest = set.getManifest()
        manifest.cards.add(Card(1, title = "World", cardType = 0))

        grid.refresh()

        val row = grid.getChildAt(0) as LinearLayout
        val second = row.getChildAt(1) as GridButton
        assertEquals(1, second.getCard()?.id)
    }
}
