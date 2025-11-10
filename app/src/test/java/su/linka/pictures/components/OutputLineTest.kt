package su.linka.pictures.components

import android.content.Context
import android.view.View
import android.widget.TextView
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
class OutputLineTest {

    private lateinit var context: Context
    private lateinit var workingDir: java.io.File
    private lateinit var set: su.linka.pictures.Set
    private lateinit var outputLine: OutputLine

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        if (this::workingDir.isInitialized) {
            ComponentTestUtils.cleanup(workingDir)
        }
    }

    @Test
    fun addCard_withoutSpace_updatesTextOutput() {
        val card = Card(0, title = "Hi", cardType = 0)
        val (_, createdSet, dir) = ComponentTestUtils.createSet(cards = listOf(card), withoutSpace = true)
        set = createdSet
        workingDir = dir
        outputLine = OutputLine(context)
        outputLine.setSet(set)

        val textView = outputLine.findViewById<TextView>(su.linka.pictures.R.id.output_text)
        val grid = outputLine.findViewById<OutputGrid>(su.linka.pictures.R.id.output_grid)

        assertEquals(View.VISIBLE, textView.visibility)
        assertEquals(View.GONE, grid.visibility)

        outputLine.addCard(card)
        assertEquals("Hi", textView.text.toString())

        outputLine.addCard(Card(1, cardType = 1))
        assertEquals("Hi ", textView.text.toString())

        outputLine.backspace()
        assertEquals("Hi", textView.text.toString())

        outputLine.clear()
        assertEquals("", textView.text.toString())
    }

    @Test
    fun addCard_withSpaces_usesGrid() {
        val card = Card(0, title = "Hi", cardType = 0)
        val (_, createdSet, dir) = ComponentTestUtils.createSet(cards = listOf(card))
        set = createdSet
        workingDir = dir
        outputLine = OutputLine(context)
        outputLine.setSet(set)

        val textView = outputLine.findViewById<TextView>(su.linka.pictures.R.id.output_text)
        val grid = outputLine.findViewById<OutputGrid>(su.linka.pictures.R.id.output_grid)

        assertEquals(View.GONE, textView.visibility)
        assertEquals(View.VISIBLE, grid.visibility)

        outputLine.addCard(card)
        assertEquals(1, grid.childCount)

        outputLine.backspace()
        assertEquals(0, grid.childCount)

        outputLine.clear()
        assertEquals(0, grid.childCount)
    }

    @Test
    fun addCard_inDirectMode_doesNotStoreCard() {
        val card = Card(0, title = "Hi", cardType = 0)
        val (_, createdSet, dir) = ComponentTestUtils.createSet(cards = listOf(card))
        set = createdSet
        workingDir = dir
        outputLine = OutputLine(context)
        outputLine.setSet(set)

        val textView = outputLine.findViewById<TextView>(su.linka.pictures.R.id.output_text)
        val grid = outputLine.findViewById<OutputGrid>(su.linka.pictures.R.id.output_grid)

        outputLine.setDirectMode(true)
        outputLine.addCard(card)

        assertEquals(0, grid.childCount)
        assertEquals("", textView.text.toString())
    }
}
