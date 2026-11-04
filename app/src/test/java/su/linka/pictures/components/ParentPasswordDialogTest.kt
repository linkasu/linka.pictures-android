package su.linka.pictures.components

import android.content.DialogInterface
import android.content.SharedPreferences
import android.widget.EditText
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceManager
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

    @Test
    fun legacyPreferenceWriter_preservesEmptyWhitespaceAndUnicode() {
        val password = EditTextPreference(context).apply { key = "parent_password" }
        PreferenceManager(context).createPreferenceScreen(context).addPreference(password)

        for (value in listOf("", "  ", " мёд🔑 ", "е\u0308")) {
            password.text = value
            assertEquals(value, preferences.getString("parent_password", null))
        }
    }

    @Test
    fun legacyVerification_preservesUnicodeAndExistingUnusablePasswords() {
        val cases = listOf(
            Triple(null, "", false),
            Triple("", "", false),
            Triple("  ", "  ", false),
            Triple(" мёд🔑 ", " мёд🔑 ", false),
            Triple("мёд🔑", "мёд🔑", true),
            Triple("мёд🔑", " мёд🔑 ", true),
            Triple("ё", "е\u0308", false)
        )

        for ((stored, entered, expected) in cases) {
            preferences.edit().clear().putBoolean("parent", true).apply {
                if (stored != null) putString("parent_password", stored)
            }.commit()
            var authorized = false
            var failed = false
            ParentPasswordDialog.showDialog(context, object : Callback<Any?>() {
                override fun onDone(result: Any?) {
                    authorized = true
                }

                override fun onFail(error: Exception?) {
                    failed = true
                }
            })

            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            dialog.findViewById<EditText>(R.id.input_prompt)!!.setText(entered)
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).callOnClick()
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertEquals(expected, authorized)
            assertEquals(!expected, failed)
        }
    }
}
