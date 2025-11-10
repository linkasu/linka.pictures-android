package su.linka.pictures.components

import android.content.Context
import android.content.DialogInterface
import androidx.test.core.app.ApplicationProvider
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
class ConfirmDialogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun confirmDialog_positiveTriggersOnDone() {
        var succeeded = false
        ConfirmDialog.showConfirmDialog(context, R.string.ok, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                succeeded = true
            }

            override fun onFail(error: Exception?) {
                succeeded = false
            }
        })

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        assertTrue(succeeded)
    }

    @Test
    fun confirmDialog_negativeTriggersOnFail() {
        var failed = false
        ConfirmDialog.showConfirmDialog(context, R.string.ok, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                failed = false
            }

            override fun onFail(error: Exception?) {
                failed = true
            }
        })

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()

        assertTrue(failed)
    }
}
