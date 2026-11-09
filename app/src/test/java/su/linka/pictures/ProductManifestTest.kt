package su.linka.pictures

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ProductManifestTest {
    @Test fun bothVariantsUseTheSharedLauncherWithoutLegacyWritersOrCollectors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.packageManager
        val main = "su.linka.pictures.activity.MainActivity"
        val launcher = manager.queryIntentActivities(Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
        assertEquals(listOf(main), launcher.map { it.activityInfo.name })
        val alias = manager.getActivityInfo(ComponentName(context.packageName,
            "su.linka.pictures.activity.BroadcastReceiverActivity"), 0)
        assertEquals(main, alias.targetActivity)
        assertNull(manager.resolveContentProvider("${context.packageName}.firebaseinitprovider", 0))
        for (name in listOf("GridActivity", "SetEditActivity", "SettingsActivity")) {
            assertThrows(PackageManager.NameNotFoundException::class.java) {
                manager.getActivityInfo(ComponentName(context.packageName, "su.linka.pictures.activity.$name"), PackageManager.MATCH_DISABLED_COMPONENTS)
            }
        }
        if (!BuildConfig.DEBUG) assertNull(manager.resolveContentProvider("${context.packageName}.parity-fixtures", 0))
    }
}
