package su.linka.pictures.components

import android.view.View
import android.widget.TextView
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

    private lateinit var context: android.content.Context
    private lateinit var workingDir: java.io.File
    private lateinit var set: su.linka.pictures.Set
    private lateinit var outputLine: OutputLine

    @Before
    fun setUp() {
        // context provided per-test via ComponentTestUtils
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
        val (createdContext, createdSet, dir) = ComponentTestUtils.createSet(cards = listOf(card), withoutSpace = true)
        context = createdContext
        set = createdSet
        workingDir = dir
        outputLine = OutputLine(context)
        outputLine.setSet(set)

        val textView = outputLine.findViewById<TextView>(su.linka.pictures.R.id.output_text)
        val scrollView = outputLine.findViewById<android.widget.HorizontalScrollView>(su.linka.pictures.R.id.scroll_grid)

        assertEquals(View.VISIBLE, textView.visibility)
        assertEquals(View.GONE, scrollView.visibility)

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
        val (createdContext, createdSet, dir) = ComponentTestUtils.createSet(cards = listOf(card))
        context = createdContext
        set = createdSet
        workingDir = dir
        outputLine = OutputLine(context)
        outputLine.setSet(set)

        val textView = outputLine.findViewById<TextView>(su.linka.pictures.R.id.output_text)
        val scrollView = outputLine.findViewById<android.widget.HorizontalScrollView>(su.linka.pictures.R.id.scroll_grid)
        val grid = outputLine.findViewById<OutputGrid>(su.linka.pictures.R.id.output_grid)

        assertEquals(View.GONE, textView.visibility)
        assertEquals(View.VISIBLE, scrollView.visibility)

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
        val (createdContext, createdSet, dir) = ComponentTestUtils.createSet(cards = listOf(card))
        context = createdContext
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
