package su.linka.pictures

import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import su.linka.pictures.activity.MainActivity

@RunWith(AndroidJUnit4::class)
class ComposeHostInstrumentedTest {
    @Test fun launcherActuallyHostsTheSharedComposeScreensOnAndroid() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertTrue("Launcher must render the shared UI, not the legacy XML screen",
                    content.getChildAt(0) is ComposeView)
            }
        }
    }
}
