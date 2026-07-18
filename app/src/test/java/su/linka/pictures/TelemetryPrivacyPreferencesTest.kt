package su.linka.pictures

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelemetryPrivacyPreferencesTest {
    private lateinit var context: Context
    private lateinit var preferences: TelemetryPrivacyPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            TelemetryPrivacyPreferences.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
        preferences = TelemetryPrivacyPreferences(context)
    }

    @Test
    fun defaultsAreUnknownAndRequireNotice() {
        val state = preferences.state()

        assertEquals(TelemetryCollectionState.UNKNOWN, state)
        assertTrue(state.requiresNotice)
    }

    @Test
    fun analyticsTransitionsFromUnknownToEnabled() {
        preferences.setAnalyticsEnabled(true)

        val state = preferences.state()
        assertEquals(TelemetryCollectionState.ENABLED, state)
        assertFalse(state.requiresNotice)
    }

    @Test
    fun choicesPersistAcrossPreferenceInstances() {
        preferences.setAnalyticsEnabled(false)

        val restored = TelemetryPrivacyPreferences(context).state()

        assertEquals(TelemetryCollectionState.DISABLED, restored)
    }
}
