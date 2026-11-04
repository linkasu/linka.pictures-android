package su.linka.pictures.activity

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.GridView
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowDialog
import su.linka.pictures.Callback
import su.linka.pictures.Card
import su.linka.pictures.R
import su.linka.pictures.Set
import su.linka.pictures.SetsAdapter
import su.linka.pictures.SetsManager
import su.linka.pictures.components.SetContextDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MainActivityEditResultTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var manager: SetsManager
    private lateinit var set: Set
    private lateinit var list: GridView
    private lateinit var adapter: SetsAdapter

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = SetsManager(context)
        set = manager.createSet()
        set.getManifest().cards.apply {
            clear()
            add(Card(0, cardType = 2))
        }
        saveSet()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        (ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog)
            ?.getButton(DialogInterface.BUTTON_NEGATIVE)?.performClick()
        list = activity.findViewById(R.id.sets_list)
        adapter = list.adapter as SetsAdapter
    }

    @After
    fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test
    fun successfulExistingSetEditReloadsCover() {
        val position = position()
        val before = adapter.getItem(position)!!
        val editorIntent = openExistingEditor(position)
        val image = set.saveBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        set.addCard(0, Card(0, cardType = 0, imagePath = image.name))
        saveSet()

        shadowOf(activity).receiveResult(editorIntent, Activity.RESULT_OK, Intent())

        val after = adapter.getItem(position())!!
        assertNotSame(before, after)
        assertEquals(image.name, after.getDefaultBitmap())
        val view = adapter.getView(position(), null, list)
        assertEquals("${after.cacheKey}/${image.name}", view.findViewById<ImageView>(R.id.picture).tag)
    }

    @Test
    fun cancelledExistingSetEditDoesNotReloadList() {
        val position = position()
        val before = adapter.getItem(position)
        val editorIntent = openExistingEditor(position)

        shadowOf(activity).receiveResult(editorIntent, Activity.RESULT_CANCELED, Intent())

        assertSame(before, adapter.getItem(position))
    }

    private fun openExistingEditor(position: Int): Intent {
        list.onItemLongClickListener!!.onItemLongClick(list, View(activity), position, adapter.getItemId(position))
        shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(SetContextDialog.EDIT)
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotEquals(-1, started.requestCode)
        assertEquals(SetEditActivity::class.java.name, started.intent.component?.className)
        assertEquals("edit-result.linka", started.intent.getStringExtra(SetEditActivity.EXTRA_FILE))
        return started.intent
    }

    private fun position(): Int = (0 until adapter.count).first {
        adapter.getItem(it)?.name == "edit-result.linka"
    }

    private fun saveSet() {
        manager.save(set, "edit-result.linka", object : Callback<Any?>() {
            override fun onDone(result: Any?) = Unit
            override fun onFail(error: Exception?) = throw AssertionError(error)
        })
    }
}
