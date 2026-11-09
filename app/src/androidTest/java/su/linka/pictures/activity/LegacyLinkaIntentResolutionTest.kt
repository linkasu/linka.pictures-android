package su.linka.pictures.activity

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** The historical external component is an alias to the only product activity. */
class LegacyLinkaIntentResolutionTest {
    @Test fun contentMimeMatchingDoesNotDependOnOpaqueProviderPathExtensions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".development"))
        val component = ComponentName(context.packageName, "su.linka.pictures.activity.BroadcastReceiverActivity")
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DEFAULT_ONLY
        assertTrue(context.packageManager.getActivityInfo(component, flags).enabled)
        assertEquals(MainActivity::class.java.name, context.packageManager.getActivityInfo(component, flags).targetActivity)
        fun resolves(uri: String, mime: String): Boolean {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
                .setPackage(context.packageName).addCategory(Intent.CATEGORY_BROWSABLE)
            return context.packageManager.queryIntentActivities(intent, flags).any {
                it.activityInfo.name == component.className
            }
        }
        for (mime in listOf("application/vnd.linka", "application/linkaapp", "application/octet-stream")) {
            for (path in listOf("board.linka", "opaque/42", "board.LINKA")) {
                assertTrue("Content MIME contract changed: $mime / $path", resolves("content://owned-fixture/$path", mime))
            }
        }
        assertFalse(resolves("content://owned-fixture/board.linka", "image/png"))
        assertFalse(resolves("https://example.invalid/board.linka", "application/vnd.linka"))
    }
}
