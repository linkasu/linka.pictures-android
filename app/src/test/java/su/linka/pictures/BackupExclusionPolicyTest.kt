package su.linka.pictures

import android.content.Context
import androidx.annotation.XmlRes
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BackupExclusionPolicyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun parentPasswordSetsImagesAndAudioStayOutOfBackupAndTransfer() {
        val legacyRules = readExclusions(R.xml.backup_rules)
        assertSensitiveDomainsExcluded(legacyRules, scope = "legacy")

        val extractionRules = readExclusions(R.xml.data_extraction_rules)
        assertSensitiveDomainsExcluded(extractionRules, scope = "cloud-backup")
        assertSensitiveDomainsExcluded(extractionRules, scope = "device-transfer")
    }

    private fun assertSensitiveDomainsExcluded(exclusions: kotlin.collections.Set<Exclusion>, scope: String) {
        assertTrue(Exclusion(scope, "sharedpref", ".") in exclusions)
        assertTrue(Exclusion(scope, "file", ".") in exclusions)
        assertTrue(Exclusion(scope, "external", ".") in exclusions)
        assertTrue(Exclusion(scope, "device_sharedpref", ".") in exclusions)
        assertTrue(Exclusion(scope, "device_file", ".") in exclusions)
    }

    private fun readExclusions(@XmlRes resourceId: Int): kotlin.collections.Set<Exclusion> {
        val parser = context.resources.getXml(resourceId)
        val exclusions = mutableSetOf<Exclusion>()
        var scope = "legacy"
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "cloud-backup", "device-transfer" -> scope = parser.name
                    "exclude" -> exclusions += Exclusion(
                        scope = scope,
                        domain = parser.getAttributeValue(null, "domain"),
                        path = parser.getAttributeValue(null, "path")
                    )
                }
            }
            parser.next()
        }
        parser.close()
        return exclusions
    }

    private data class Exclusion(val scope: String, val domain: String, val path: String)
}
