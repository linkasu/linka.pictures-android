package su.linka.pictures

import org.junit.Assert.assertFalse
import org.junit.Test

class DebugTelemetryGateTest {
    @Test
    fun debugBuildNeverAllowsCollection() {
        assertFalse(BuildConfig.TELEMETRY_COLLECTION_ALLOWED)
    }
}
