package su.linka.pictures.components

import android.content.Context
import android.content.DialogInterface
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class InputDialogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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

        val alertDialog = ShadowAlertDialog.getLatestAlertDialog()
        val editText = alertDialog.findViewById<EditText>(R.id.input_prompt)!!
        editText.setText(" value ")
        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

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

        val alertDialog = ShadowAlertDialog.getLatestAlertDialog()
        val editText = alertDialog.findViewById<EditText>(R.id.input_prompt)!!
        editText.setText("   ")
        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

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

        val alertDialog = ShadowAlertDialog.getLatestAlertDialog()
        alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()

        assertTrue(failed)
    }
}
