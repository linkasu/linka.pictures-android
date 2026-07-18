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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TelemetryProcessInitializationTest {
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
    fun freshInitializationKeepsUnknownAndDisabledCollectionOff() {
        Telemetry.init(context, collectionAllowed = true)

        assertEquals(TelemetryCollectionState.UNKNOWN, Telemetry.privacyState())
        assertFalse(Telemetry.isCollectionEnabledForCurrentProcess())

        preferences.setAnalyticsEnabled(false)
        Telemetry.init(context, collectionAllowed = true)

        assertEquals(TelemetryCollectionState.DISABLED, Telemetry.privacyState())
        assertFalse(Telemetry.isCollectionEnabledForCurrentProcess())
    }

    @Test
    fun freshInitializationRestoresEnabledOnlyWhenBuildAllowsCollection() {
        preferences.setAnalyticsEnabled(true)

        Telemetry.init(context, collectionAllowed = false)
        assertEquals(TelemetryCollectionState.ENABLED, Telemetry.privacyState())
        assertFalse(Telemetry.isCollectionEnabledForCurrentProcess())

        Telemetry.init(context, collectionAllowed = true)
        assertTrue(Telemetry.isCollectionEnabledForCurrentProcess())
    }
}
