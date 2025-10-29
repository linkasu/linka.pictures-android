package su.linka.pictures.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.linka.pictures.Card
import su.linka.pictures.Set
import su.linka.pictures.SetManifest
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CardGridTest {

    private lateinit var context: Context
    private lateinit var workingDir: File
    private lateinit var manifest: SetManifest
    private lateinit var set: Set
    private lateinit var grid: CardGrid

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workingDir = File(context.cacheDir, "card-grid-${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }

        manifest = SetManifest(File(workingDir, "config.json")).apply {
            rows = 1
            columns = 2
            cards.clear()
            cards.add(Card(0, cardType = 2))
            cards.add(Card(1, cardType = 2))
            cards.add(Card(2, cardType = 2))
        }
        set = Set(manifest, workingDir)
        grid = CardGrid(context)
        grid.setSet(set)
    }

    @After
    fun tearDown() {
        workingDir.deleteRecursively()
    }

    @Test
    fun nextPage_advancesUntilLastPage() {
        assertTrue(grid.nextPage())
        assertFalse(grid.nextPage())
        assertEquals(1, grid.currentPage())
    }

    @Test
    fun jumpToPage_clampsToAvailablePages() {
        grid.jumpToPage(10)
        assertEquals(1, grid.currentPage())
    }
}
