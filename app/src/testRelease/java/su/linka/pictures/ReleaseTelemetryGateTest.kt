package su.linka.pictures

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTelemetryGateTest {
    @Test
    fun releaseBuildAllowsConsentControlledAnalytics() {
        assertTrue(BuildConfig.TELEMETRY_COLLECTION_ALLOWED)
        assertFalse(TelemetryCollectionState.UNKNOWN.collectionEnabledForBuild(
            BuildConfig.TELEMETRY_COLLECTION_ALLOWED
        ))
        assertFalse(TelemetryCollectionState.DISABLED.collectionEnabledForBuild(
            BuildConfig.TELEMETRY_COLLECTION_ALLOWED
        ))
        assertTrue(TelemetryCollectionState.ENABLED.collectionEnabledForBuild(
            BuildConfig.TELEMETRY_COLLECTION_ALLOWED
        ))
    }

    @Test
    fun oldPendingReportsCannotUploadWithoutCrashlyticsRuntime() {
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
        }
    }
}
