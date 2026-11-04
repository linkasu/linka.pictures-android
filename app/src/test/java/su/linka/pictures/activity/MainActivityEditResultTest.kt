package su.linka.pictures.activity

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
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
import su.linka.pictures.Set
import su.linka.pictures.SetsManager
import su.linka.pictures.ui.LibraryAction
import su.linka.pictures.ui.SetSummary
import su.linka.pictures.awaitHostWork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MainActivityEditResultTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var manager: SetsManager
    private lateinit var set: Set

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        manager = SetsManager(context)
        set = manager.createSet()
        set.getManifest().cards.add(Card(0, cardType = 2))
        saveSet()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        (ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog)
            ?.getButton(DialogInterface.BUTTON_NEGATIVE)?.performClick()
        assertComposeHost(activity)
    }

    @After fun tearDown() { controller.pause().stop().destroy(); set.close() }

    @Test fun successfulExistingSetEditReloadsCover() {
        val before = summary()
        val editorIntent = openExistingEditor()
        val image = set.saveBitmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        set.addCard(0, Card(0, cardType = 0, imagePath = image.name))
        saveSet()
        shadowOf(activity).receiveResult(editorIntent, Activity.RESULT_OK, Intent())
        assertNotSame(before, summary())
        assertEquals(image.name, summary().coverPath)
    }

    @Test fun cancelledExistingSetEditDoesNotReloadList() {
        val before = summary()
        val editorIntent = openExistingEditor()
        shadowOf(activity).receiveResult(editorIntent, Activity.RESULT_CANCELED, Intent())
        assertSame(before, summary())
    }

    private fun openExistingEditor(): Intent {
        activity.onAction(LibraryAction.Edit(summary()))
        val started = shadowOf(activity).nextStartedActivityForResult
        assertNotEquals(-1, started.requestCode)
        assertEquals(SetEditActivity::class.java.name, started.intent.component?.className)
        assertEquals("edit-result.linka", started.intent.getStringExtra(SetEditActivity.EXTRA_FILE))
        return started.intent
    }

    private fun summary(): SetSummary {
        awaitHostWork(activity)
        return activity.sets.first { it.name == "edit-result.linka" }
    }
    private fun saveSet() = manager.save(set, "edit-result.linka", object : Callback<Any?>() {
        override fun onDone(result: Any?) = Unit
        override fun onFail(error: Exception?) = throw AssertionError(error)
    })
}
