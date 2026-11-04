package su.linka.pictures.activity

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.widget.EditText
import android.app.AlertDialog
import androidx.preference.PreferenceManager
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
import su.linka.pictures.Card
import su.linka.pictures.PhraseDraftStore
import su.linka.pictures.ParentControl
import su.linka.pictures.R
import su.linka.pictures.SetsManager
import su.linka.pictures.TelemetryPrivacyPreferences
import su.linka.pictures.ui.LibraryAction
import su.linka.pictures.ui.SettingsAction
import su.linka.pictures.ui.BoardAction

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
        assertComposeHost(activity)
        val summary = activity.sets.first { it.name == "protected.linka" }
        val archive = manager.getSetFile(summary.name)
        fun requestDelete() {
            activity.onAction(LibraryAction.Delete(summary))
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
        su.linka.pictures.awaitHostWork(activity)
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
        su.linka.pictures.awaitHostWork(controller.get())
        val summary = controller.get().sets.first { it.name == "protected.linka" }
        for (action in listOf(LibraryAction.Edit(summary), LibraryAction.Rename(summary))) {
            controller.get().onAction(action)
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
        assertTrue(controller.get().authenticated)
        assertComposeHost(controller.get())
        controller.recreate()
        assertFalse(controller.get().authenticated)
        latestDialog().cancel()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertTrue(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test
    fun settingsCannotEnableWithoutPasswordOrClearActivePassword() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        activity.onAction(SettingsAction.ToggleParent(true))
        assertFalse(ParentControl(context).enabled)
        activity.onAction(SettingsAction.ChangePassword)
        answer("secret")
        assertTrue(ParentControl(context).hasPassword)
        activity.onAction(SettingsAction.ToggleParent(true))
        for (invalid in listOf("", "   ")) {
            activity.onAction(SettingsAction.ChangePassword)
            answer(invalid)
            assertTrue(ParentControl(context).accepts("secret"))
        }
        assertTrue(ParentControl(context).enabled)
        assertTrue(ParentControl(context).accepts("secret"))
        controller.pause().stop().destroy()
    }

    @Test
    fun legacyRecoveryOnlyAllowsPasswordSetupAndNextLaunchRequiresIt() {
        protect("")
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(activity.passwordRecoveryRequired)
        activity.onAction(SettingsAction.ToggleParent(false))
        activity.onAction(SettingsAction.ToggleAnalytics(true))
        activity.onAction(SettingsAction.Back)
        assertTrue(ParentControl(context).enabled)
        assertFalse(activity.analyticsEnabled)
        assertFalse(activity.isFinishing)
        activity.onAction(SettingsAction.ChangePassword)
        answer("")
        assertTrue(activity.passwordRecoveryRequired)
        activity.onAction(SettingsAction.ChangePassword)
        answer("restored")
        assertFalse(activity.passwordRecoveryRequired)
        assertFalse(ParentControl(context).needsRecovery)
        controller.pause().stop().destroy()

        val next = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertFalse(next.get().authenticated)
        answer("restored")
        assertTrue(next.get().authenticated)
        next.pause().stop().destroy()
    }

    @Test
    fun assigningAndAuthenticatingPasswordKeepsLiteralWhitespace() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        controller.get().onAction(SettingsAction.ChangePassword)
        answer(" secret ")
        assertTrue(ParentControl(context).accepts(" secret "))
        assertFalse(ParentControl(context).accepts("secret"))
        controller.get().onAction(SettingsAction.ToggleParent(true))
        controller.recreate()
        assertFalse(controller.get().authenticated)
        answer(" secret ")
        assertTrue(controller.get().authenticated)
        controller.pause().stop().destroy()
    }

    @Test
    fun createAndImportCannotLaunchBeforeParentApproval() {
        protect()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            for (action in listOf(LibraryAction.Create, LibraryAction.Import)) {
                controller.get().onAction(action)
                assertNotNull(latestDialog().findViewById<EditText>(R.id.input_prompt))
                answer("wrong")
                assertNull(shadowOf(controller.get()).nextStartedActivityForResult)
                assertFalse(controller.get().isFinishing)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test
    fun communicationExitOnlyDiscardsPhraseAfterCorrectParentPassword() {
        protect()
        val manager = SetsManager(context)
        val source = manager.createSet()
        try {
            source.addCard(0, Card(0, title = "word"))
            manager.save(source, "protected-phrase.linka", object : Callback<Any?>() {
                override fun onDone(result: Any?) = Unit
                override fun onFail(error: Exception?) = throw AssertionError(error)
            })
        } finally { source.close() }
        val intent = Intent(context, GridActivity::class.java).putExtra(GridActivity.EXTRA_FILE, "protected-phrase.linka")
        val controller = Robolectric.buildActivity(GridActivity::class.java, intent).setup()
        try {
            val activity = controller.get()
            activity.onAction(BoardAction.SelectCard(0))
            val state = Bundle()
            controller.saveInstanceState(state)
            val token = state.getString("phraseDraftToken")!!
            val identity = state.getString("documentIdentity")!!
            val drafts = PhraseDraftStore(context)
            activity.onAction(BoardAction.Close)
            answer("wrong")
            assertFalse(activity.isFinishing)
            assertNotNull(drafts.restore(token, identity))
            activity.onAction(BoardAction.Close)
            latestDialog().cancel()
            assertFalse(activity.isFinishing)
            assertNotNull(drafts.restore(token, identity))
            activity.onAction(BoardAction.Close)
            answer("secret")
            assertTrue(activity.isFinishing)
            assertNull(drafts.restore(token, identity))
        } finally { controller.pause().stop().destroy() }
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
