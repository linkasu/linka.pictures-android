package su.linka.pictures

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BundledBoardsTest {
    @Test fun allSevenOriginalArchivesArePackagedWithoutCaseCollisions() {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val names = assets.list("bundled")!!.toList()
        assertEquals(7, names.size)
        assertEquals(7, names.map { it.lowercase(Locale.ROOT) }.toSet().size)
        val hashes = names.associateWith { name ->
            assets.open("bundled/$name").use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
        assertEquals("ae6f1e58f2d7e7a3826e3586aab3d862f38a43d9cecf59168766dc536ca95985", hashes["pain.linka"])
        assertEquals("a0589a029f77bfff7307d6ee3c4dbd41e1b2badd52ca90896ca5c835ccfb293b", hashes["want.linka"])
        assertEquals("4e2a43a6b62ff34a8675fb9e02a3998a0c4db09558044b611bb40c81115c798c", hashes["numbers-upper.linka"])
        assertEquals(hashes["numbers-upper.linka"], hashes["numbers-lower.linka"])
    }
}
