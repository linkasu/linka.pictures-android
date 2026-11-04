package su.linka.pictures.components

import android.content.Context
import android.widget.ListView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import su.linka.pictures.Callback
import su.linka.pictures.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SetContextDialogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun exportIsAnAccessibleContextActionWithoutChangingExistingOrdinals() {
        val actions = context.resources.getStringArray(R.array.set_context_actions)
        assertEquals(context.getString(R.string.open), actions[SetContextDialog.OPEN])
        assertEquals(context.getString(R.string.edit), actions[SetContextDialog.EDIT])
        assertEquals(context.getString(R.string.rename), actions[SetContextDialog.RENAME])
        assertEquals(context.getString(R.string.delete), actions[SetContextDialog.DELETE])
        assertEquals(context.getString(R.string.export_set), actions[SetContextDialog.EXPORT])
    }

    @Test
    fun selectingAction_invokesCallbackWithIndex() {
        var selectedIndex = -1
        SetContextDialog.show(context, "title", object : Callback<Int>() {
            override fun onDone(result: Int) {
                selectedIndex = result
            }

            override fun onFail(error: Exception?) {
                selectedIndex = -2
            }
        })

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val listView: ListView = dialog.listView
        val index = 2
        listView.performItemClick(listView.adapter.getView(index, null, listView), index, index.toLong())

        assertEquals(index, selectedIndex)
    }
}
