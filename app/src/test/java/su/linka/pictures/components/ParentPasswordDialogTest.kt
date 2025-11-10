package su.linka.pictures.components

import android.content.DialogInterface
import android.content.SharedPreferences
import android.widget.EditText
import androidx.preference.PreferenceManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
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
class ParentPasswordDialogTest {

    private lateinit var context: ContextThemeWrapper
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        context = ContextThemeWrapper(appContext, R.style.AppTheme)
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().clear().commit()
    }

    @Test
    fun parentControlDisabled_callsOnDoneImmediately() {
        var done = false
        ParentPasswordDialog.showDialog(context, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                done = true
            }

            override fun onFail(error: Exception?) {
                done = false
            }
        })

        assertTrue(done)
    }

    @Test
    fun correctPassword_callsOnDone() {
        preferences.edit().putBoolean("parent", true).putString("parent_password", "secret").apply()
        var done = false
        ParentPasswordDialog.showDialog(context, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                done = true
            }

            override fun onFail(error: Exception?) {
                done = false
            }
        })

        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        val input = dialog.findViewById<EditText>(R.id.input_prompt)!!
        input.setText("secret")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue(done)
    }

    @Test
    fun incorrectPassword_callsOnFail() {
        preferences.edit().putBoolean("parent", true).putString("parent_password", "secret").apply()
        var failed = false
        ParentPasswordDialog.showDialog(context, object : Callback<Any?>() {
            override fun onDone(result: Any?) {
                failed = false
            }

            override fun onFail(error: Exception?) {
                failed = true
            }
        })

        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        val input = dialog.findViewById<EditText>(R.id.input_prompt)!!
        input.setText("wrong")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue(failed)
    }
}
