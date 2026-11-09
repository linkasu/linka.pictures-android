package su.linka.pictures.ui.settings

import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.ExperimentalResourceApi
import su.linka.pictures.resources.Res
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalResourceApi::class)
class ThirdPartyLicensesTest {
    @Test fun shippedLegalTextsMatchTheVerifiedFfmpeg812SourceArchive() = runTest {
        // Hashes of exact upstream members, not text reconstructed from a license URL.
        val expected = mapOf(
            "COPYING.LGPLv2.1" to "246041b6ecf9bc32d718a62c57877c78b5eb397b6467e74ed7ae2626ab189c30",
            "LICENSE.md" to "2e1d16c72fd74e12063776371da757322f8b77589386532f4fd8634bde7de1af",
        )
        for ((name, hash) in expected) {
            val bytes = Res.readBytes("files/licenses/$name")
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(hash, actual, name)
        }
        assertEquals("8.1.2", FFMPEG_VERSION)
        assertEquals("https://github.com/linkasu/linka.pictures-android/tree/main/third_party/ffmpeg", FFMPEG_SOURCE_URL)
        val notice = Res.readBytes("files/licenses/LICENSE.md").decodeToString()
        assertTrue(notice.contains("LGPL v2.1+"))
    }
}
