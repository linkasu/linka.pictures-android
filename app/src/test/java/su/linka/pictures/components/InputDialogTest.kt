package su.linka.pictures.components

import android.content.DialogInterface
import android.widget.EditText
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowLooper
import su.linka.pictures.Callback
import su.linka.pictures.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class InputDialogTest {

    private lateinit var context: ContextThemeWrapper

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        context = ContextThemeWrapper(appContext, R.style.AppTheme)
    }

    @Test
    fun inputDialog_positiveWithText_callsOnDone() {
        var captured: String? = null
        InputDialog.showDialog(context, R.string.app_name, object : Callback<String>() {
            override fun onDone(result: String) {
                captured = result
            }

            override fun onFail(error: Exception?) {
                captured = null
            }
        })

        val alertDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        val editText = alertDialog.findViewById<EditText>(R.id.input_prompt)!!
        editText.setText(" value ")
        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("value", captured)
    }

    @Test
    fun inputDialog_emptyText_callsOnFail() {
        var failed = false
        InputDialog.showDialog(context, R.string.app_name, object : Callback<String>() {
            override fun onDone(result: String) {
                failed = false
            }

            override fun onFail(error: Exception?) {
                failed = true
            }
        })

        val alertDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        val editText = alertDialog.findViewById<EditText>(R.id.input_prompt)!!
        editText.setText("   ")
        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue(failed)
    }

    @Test
    fun inputDialog_negative_callsOnFail() {
        var failed = false
        InputDialog.showDialog(context, R.string.app_name, object : Callback<String>() {
            override fun onDone(result: String) {
                failed = false
            }

            override fun onFail(error: Exception?) {
                failed = true
            }
        })

        val alertDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE).callOnClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue(failed)
    }
}
