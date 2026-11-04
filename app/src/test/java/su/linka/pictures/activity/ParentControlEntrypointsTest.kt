package su.linka.pictures.activity

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.widget.GridView
import android.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import su.linka.pictures.Callback
import su.linka.pictures.ParentControl
import su.linka.pictures.R
import su.linka.pictures.SetsAdapter
import su.linka.pictures.SetsManager
import su.linka.pictures.TelemetryPrivacyPreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ParentControlEntrypointsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        TelemetryPrivacyPreferences(context).setAnalyticsEnabled(false)
    }

    private fun protect(password: String = "secret") {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("parent", true).putString("parent_password", password).commit()
    }

    private fun latestDialog() = requireNotNull(ShadowDialog.getLatestDialog()) as AlertDialog

    private fun answer(password: String) {
        latestDialog().findViewById<EditText>(R.id.input_prompt)!!.setText(password)
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    @Test
    fun contextDeleteRequiresCorrectPasswordThenConfirmation() {
        protect()
        val manager = SetsManager(context)
        manager.save(manager.createSet(), "protected.linka", object : Callback<Any?>() {
            override fun onDone(result: Any?) = Unit
            override fun onFail(error: Exception?) = throw AssertionError(error)
        })
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val grid = activity.findViewById<GridView>(R.id.sets_list)
        val manifest = (grid.adapter as SetsAdapter).getItem(0)!!
        val archive = requireNotNull(manifest.archiveFile)
        fun requestDelete() {
            grid.onItemLongClickListener!!.onItemLongClick(grid, null, 0, 0)
            val menu = latestDialog()
            menu.listView.performItemClick(null, 3, 3)
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        }
        requestDelete()
        answer("wrong")
        assertTrue(archive.exists())
        requestDelete()
        latestDialog().cancel()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue(archive.exists())
        requestDelete()
        answer("secret")
        assertTrue(archive.exists())
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertFalse(archive.exists())
        controller.pause().stop().destroy()
    }

    @Test
    fun contextEditAndRenameCannotBypassPassword() {
        protect()
        val manager = SetsManager(context)
        manager.save(manager.createSet(), "protected.linka", object : Callback<Any?>() {
            override fun onDone(result: Any?) = Unit
            override fun onFail(error: Exception?) = throw AssertionError(error)
        })
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val grid = controller.get().findViewById<GridView>(R.id.sets_list)
        for (action in listOf(1, 2)) {
            grid.onItemLongClickListener!!.onItemLongClick(grid, null, 0, 0)
            latestDialog().listView.performItemClick(null, action, action.toLong())
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
            assertNotNull(latestDialog().findViewById<EditText>(R.id.input_prompt))
            answer("wrong")
            assertNull(shadowOf(controller.get()).nextStartedActivity)
            assertFalse(latestDialog().isShowing)
        }
        controller.pause().stop().destroy()
    }

    @Test
    fun directSettingsLaunchAndRecreationRequireAuthentication() {
        protect()
        val denied = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        answer("wrong")
        assertTrue(denied.get().isFinishing)
        denied.pause().stop().destroy()

        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        answer("secret")
        controller.get().supportFragmentManager.executePendingTransactions()
        assertNotNull(controller.get().supportFragmentManager.findFragmentById(R.id.settings))
        controller.recreate()
        controller.get().supportFragmentManager.executePendingTransactions()
        assertNull(controller.get().supportFragmentManager.findFragmentById(R.id.settings))
        latestDialog().cancel()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun settingsCannotEnableWithoutPasswordOrClearActivePassword() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.supportFragmentManager.executePendingTransactions()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings)
            as SettingsActivity.SettingsFragment
        val parent = fragment.findPreference<SwitchPreferenceCompat>("parent")!!
        val password = fragment.findPreference<EditTextPreference>("parent_password")!!
        assertTrue(password.isEnabled)
        assertFalse(parent.callChangeListener(true))
        assertTrue(password.callChangeListener("secret"))
        password.text = "secret"
        assertTrue(parent.callChangeListener(true))
        parent.isChecked = true
        assertFalse(password.callChangeListener(""))
        assertFalse(password.callChangeListener("   "))
        assertTrue(ParentControl(context).enabled)
        assertTrue(ParentControl(context).accepts("secret"))
        controller.pause().stop().destroy()
    }

    @Test
    fun legacyRecoveryOnlyAllowsPasswordSetupAndNextLaunchRequiresIt() {
        protect("")
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.supportFragmentManager.executePendingTransactions()
        val fragment = activity.supportFragmentManager.findFragmentById(R.id.settings)
            as SettingsActivity.SettingsFragment
        assertFalse(fragment.findPreference<SwitchPreferenceCompat>("parent")!!.isEnabled)
        assertFalse(fragment.findPreference<SwitchPreferenceCompat>("telemetry_analytics")!!.isEnabled)
        val password = fragment.findPreference<EditTextPreference>("parent_password")!!
        assertTrue(password.isEnabled)
        assertFalse(password.callChangeListener(""))
        assertTrue(password.callChangeListener("restored"))
        password.text = "restored"
        assertFalse(ParentControl(context).needsRecovery)
        controller.pause().stop().destroy()

        val next = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        next.get().supportFragmentManager.executePendingTransactions()
        assertNull(next.get().supportFragmentManager.findFragmentById(R.id.settings))
        answer("restored")
        next.get().supportFragmentManager.executePendingTransactions()
        assertNotNull(next.get().supportFragmentManager.findFragmentById(R.id.settings))
        next.pause().stop().destroy()
    }

    @Test
    fun exportedImportCannotReachConfirmationBeforePasswordAndCancelFinishes() {
        protect()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://test/set.linka"))
        val controller = Robolectric.buildActivity(BroadcastReceiverActivity::class.java, intent).setup()
        assertNotNull(latestDialog().findViewById<EditText>(R.id.input_prompt))
        latestDialog().cancel()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun exportedImportOnlyShowsConfirmationAfterCorrectPassword() {
        protect()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://test/set.linka"))
        val controller = Robolectric.buildActivity(BroadcastReceiverActivity::class.java, intent).setup()
        answer("secret")
        assertNull(latestDialog().findViewById<EditText>(R.id.input_prompt))
        assertTrue(latestDialog().isShowing)
        latestDialog().getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }
}
