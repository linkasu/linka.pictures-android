package su.linka.pictures.activity

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.RadioGroup
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
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
import su.linka.pictures.ui.EditorAction

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SetEditActivityPaginationTest {
    private lateinit var controller: ActivityController<SetEditActivity>
    private lateinit var activity: SetEditActivity

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
        assertComposeHost(activity)
        activity.onAction(EditorAction.Page(2))
    }

    @After
    fun tearDown() {
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.dismiss()
        controller.pause().stop().destroy()
    }

    @Test
    fun editingThirdPageKeepsPageAndUpdatesAbsolutePosition() {
        activity.onAction(EditorAction.EditCard(4))
        saveSpaceCard()

        assertEquals(2, activity.page)
        assertEquals(1, activity.document!!.cards[4].cardType)
        assertEquals(4, activity.document!!.cards[4].id)
        activity.onAction(EditorAction.Page(0))
        assertEquals(2, activity.document!!.cards[0].cardType)
    }

    @Test
    fun creatingFourthPageOpensItAndPadsThePreviousPage() {
        beginFourthPage()
        saveSpaceCard()

        assertEquals(3, activity.page)
        assertEquals(7, activity.document!!.cards.size)
        assertEquals(1, activity.document!!.cards[6].cardType)
        assertEquals(null, activity.document!!.cards.getOrNull(7))
        activity.onAction(EditorAction.Page(2))
        assertEquals(4, activity.document!!.cards[4].id)
        assertEquals(3, activity.document!!.cards[5].cardType)
    }

    @Test
    fun resizingResetsToFirstPageWithoutChangingCards() {
        val cards = activity.document!!.cards
        activity.onAction(EditorAction.Resize(2, 2))
        assertEquals(0, activity.page)
        assertEquals(2, activity.document!!.rows)
        assertEquals(2, activity.document!!.columns)
        assertEquals(cards, activity.document!!.cards)
    }

    @Test
    fun cancellingPageConfirmationOrCardKeepsThirdPage() {
        activity.onAction(EditorAction.NewPage)
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, activity.page)

        beginFourthPage()
        (ShadowDialog.getLatestDialog() as EditCardDialog)
            .findViewById<View>(R.id.cancel_button)!!.performClick()

        assertEquals(2, activity.page)
        assertEquals(5, activity.document!!.cards.size)
        activity.onAction(EditorAction.Page(3))
        assertEquals(2, activity.page)
        assertEquals(null, activity.document!!.cards.getOrNull(5))
    }

    private fun beginFourthPage() {
        activity.onAction(EditorAction.NewPage)
        (ShadowDialog.getLatestDialog() as AlertDialog)
            .getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun saveSpaceCard() {
        val dialog = ShadowDialog.getLatestDialog() as EditCardDialog
        dialog.findViewById<RadioGroup>(R.id.card_type_radiogroup)!!.check(R.id.space_card_radio)
        dialog.findViewById<View>(R.id.positiveBtn)!!.performClick()
    }

}
