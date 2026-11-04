package su.linka.pictures.activity

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import su.linka.pictures.Callback
import su.linka.pictures.Card
import su.linka.pictures.R
import su.linka.pictures.SetsManager
import su.linka.pictures.components.EditCardDialog
import su.linka.pictures.components.EditCardGrid
import su.linka.pictures.components.GridButton

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SetEditActivityPaginationTest {
    private lateinit var controller: ActivityController<SetEditActivity>
    private lateinit var activity: SetEditActivity
    private lateinit var grid: EditCardGrid

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = SetsManager(context)
        val set = manager.createSet()
        set.getManifest().apply {
            rows = 1
            columns = 2
            cards.clear()
            cards.addAll(List(5) { Card(it, cardType = 2) })
        }
        manager.save(set, "pagination.linka", object : Callback<Any?>() {
            override fun onDone(result: Any?) = Unit
            override fun onFail(error: Exception?) = throw AssertionError(error)
        })
        val intent = Intent(context, SetEditActivity::class.java)
            .putExtra(SetEditActivity.EXTRA_FILE, "pagination.linka")
        controller = Robolectric.buildActivity(SetEditActivity::class.java, intent).setup()
        activity = controller.get()
        grid = activity.findViewById(R.id.card_grid)
        grid.jumpToPage(2)
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun editingThirdPageKeepsPageAndUpdatesAbsolutePosition() {
        cell(0).performClick()
        saveSpaceCard()

        assertEquals(2, grid.currentPage())
        assertEquals(1, cell(0).getCard()?.cardType)
        assertEquals(4, cell(0).getCard()?.id)
        grid.jumpToPage(0)
        assertEquals(2, cell(0).getCard()?.cardType)
    }

    @Test
    fun creatingFourthPageOpensItAndPadsThePreviousPage() {
        beginFourthPage()
        saveSpaceCard()

        assertEquals(3, grid.currentPage())
        assertEquals(4, grid.getPagesCount())
        assertEquals(1, cell(0).getCard()?.cardType)
        assertEquals(7, cell(1).getCard()?.id)
        grid.prevPage()
        assertEquals(4, cell(0).getCard()?.id)
        assertEquals(3, cell(1).getCard()?.cardType)
    }

    @Test
    fun cancellingPageConfirmationOrCardKeepsThirdPage() {
        activity.findViewById<View>(R.id.next_button).performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, grid.currentPage())

        beginFourthPage()
        (ShadowDialog.getLatestDialog() as EditCardDialog)
            .findViewById<View>(R.id.cancel_button)!!.performClick()

        assertEquals(2, grid.currentPage())
        assertEquals(3, grid.getPagesCount())
        assertFalse(grid.nextPage())
        assertEquals(3, cell(1).getCard()?.cardType)
    }

    private fun beginFourthPage() {
        activity.findViewById<View>(R.id.next_button).performClick()
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun saveSpaceCard() {
        val dialog = ShadowDialog.getLatestDialog() as EditCardDialog
        dialog.findViewById<RadioGroup>(R.id.card_type_radiogroup)!!.check(R.id.space_card_radio)
        dialog.findViewById<View>(R.id.positiveBtn)!!.performClick()
    }

    private fun cell(column: Int): GridButton =
        (grid.getChildAt(0) as LinearLayout).getChildAt(column) as GridButton
}
