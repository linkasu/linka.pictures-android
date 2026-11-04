package su.linka.pictures.activity

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import su.linka.pictures.LinkaPicturesApp
import su.linka.pictures.TelemetryCollectionState
import su.linka.pictures.TelemetryPrivacyPreferences
import su.linka.pictures.ui.SettingsAction

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SettingsActivityTelemetryRestoreTest {
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
    fun directSettingsLaunchPersistsAndRestoresAnalyticsChoice() {
        assertTrue(context.applicationContext is LinkaPicturesApp)

        val firstController = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertComposeHost(firstController.get())
        firstController.get().onAction(SettingsAction.ToggleAnalytics(true))
        assertTrue(firstController.get().analyticsEnabled)
        assertEquals(TelemetryCollectionState.ENABLED, TelemetryPrivacyPreferences(context).state())
        firstController.pause().stop().destroy()

        val restoredController = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertComposeHost(restoredController.get())
        assertTrue(restoredController.get().analyticsEnabled)
        assertEquals(TelemetryCollectionState.ENABLED, TelemetryPrivacyPreferences(context).state())
        restoredController.get().onAction(SettingsAction.ToggleAnalytics(false))
        restoredController.recreate()
        assertEquals(false, restoredController.get().analyticsEnabled)
        assertEquals(TelemetryCollectionState.DISABLED, TelemetryPrivacyPreferences(context).state())
        restoredController.pause().stop().destroy()
    }

}
