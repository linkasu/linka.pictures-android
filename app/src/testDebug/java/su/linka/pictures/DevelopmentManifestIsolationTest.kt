package su.linka.pictures

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DevelopmentManifestIsolationTest {
    @Test fun developmentManifestDoesNotStartLegacyCollectorsOrWriters() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(Application::class.java, context.applicationContext.javaClass)
        assertFalse(BuildConfig.TELEMETRY_COLLECTION_ALLOWED)
        val manager = context.packageManager
        val application = manager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertTrue(application.metaData.getBoolean("firebase_analytics_collection_deactivated"))
        assertNull(manager.resolveContentProvider("${context.packageName}.firebaseinitprovider", 0))
        for (name in listOf("MainActivity", "GridActivity", "SetEditActivity", "SettingsActivity", "BroadcastReceiverActivity")) {
            val activity = manager.getActivityInfo(
                ComponentName(context.packageName, "su.linka.pictures.activity.$name"),
                PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertFalse("Legacy writer must stay disabled in development: $name", activity.enabled)
        }
    }
}
