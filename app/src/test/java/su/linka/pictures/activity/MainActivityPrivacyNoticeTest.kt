package su.linka.pictures.activity

import android.content.Context
import android.content.DialogInterface
import android.widget.CheckBox
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import su.linka.pictures.R
import su.linka.pictures.TelemetryCollectionState
import su.linka.pictures.TelemetryPrivacyPreferences

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MainActivityPrivacyNoticeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            TelemetryPrivacyPreferences.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun unknownNoticeCanBeDismissedWithoutBlockingCoreUi() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val dialog = requireNotNull(ShadowDialog.getLatestDialog()) as androidx.appcompat.app.AlertDialog

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).callOnClick()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertFalse(dialog.isShowing)
        assertNotNull(activity.findViewById<android.view.View>(R.id.sets_list))
        assertEquals(TelemetryCollectionState.UNKNOWN, TelemetryPrivacyPreferences(context).state())
        controller.pause().stop().destroy()
    }

    @Test
    fun pendingChoiceSurvivesRecreationAndNoticeIsRedisplayed() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val firstDialog = requireNotNull(ShadowDialog.getLatestDialog()) as androidx.appcompat.app.AlertDialog
        requireNotNull(firstDialog.findViewById<CheckBox>(R.id.telemetry_analytics_choice)).isChecked = true

        controller.recreate()

        val recreatedDialog = requireNotNull(ShadowDialog.getLatestDialog()) as androidx.appcompat.app.AlertDialog
        assertTrue(recreatedDialog.isShowing)
        assertTrue(requireNotNull(
            recreatedDialog.findViewById<CheckBox>(R.id.telemetry_analytics_choice)
        ).isChecked)
        assertEquals(TelemetryCollectionState.UNKNOWN, TelemetryPrivacyPreferences(context).state())
        controller.pause().stop().destroy()
    }
}
