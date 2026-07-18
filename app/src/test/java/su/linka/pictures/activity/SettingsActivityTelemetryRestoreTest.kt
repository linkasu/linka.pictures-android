package su.linka.pictures.activity

import android.content.Context
import androidx.preference.SwitchPreferenceCompat
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
        firstController.get().supportFragmentManager.executePendingTransactions()
        val firstPreference = analyticsPreference(firstController.get())

        assertTrue(firstPreference.callChangeListener(true))
        assertEquals(TelemetryCollectionState.ENABLED, TelemetryPrivacyPreferences(context).state())
        firstController.pause().stop().destroy()

        val restoredController = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        restoredController.get().supportFragmentManager.executePendingTransactions()

        assertTrue(analyticsPreference(restoredController.get()).isChecked)
        restoredController.pause().stop().destroy()
    }

    private fun analyticsPreference(activity: SettingsActivity): SwitchPreferenceCompat {
        val fragment = activity.supportFragmentManager
            .findFragmentById(su.linka.pictures.R.id.settings) as SettingsActivity.SettingsFragment
        return requireNotNull(fragment.findPreference("telemetry_analytics"))
    }
}
