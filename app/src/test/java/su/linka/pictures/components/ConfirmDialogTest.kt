package su.linka.pictures.components

import android.content.DialogInterface
import androidx.test.core.app.ApplicationProvider
import androidx.appcompat.view.ContextThemeWrapper
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
class ConfirmDialogTest {

    private lateinit var context: ContextThemeWrapper

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        context = ContextThemeWrapper(appContext, R.style.AppTheme)
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

        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

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

        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).callOnClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue(failed)
    }
}
