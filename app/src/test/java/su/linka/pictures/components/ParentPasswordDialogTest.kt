package su.linka.pictures.components

import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.widget.EditText
import androidx.preference.PreferenceManager
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
class ParentPasswordDialogTest {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
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

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = dialog.findViewById<EditText>(R.id.input_prompt)!!
        input.setText("secret")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

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

        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = dialog.findViewById<EditText>(R.id.input_prompt)!!
        input.setText("wrong")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        assertTrue(failed)
    }
}
