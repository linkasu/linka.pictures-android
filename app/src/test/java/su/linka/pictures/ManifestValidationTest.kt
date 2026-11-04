package su.linka.pictures

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ManifestValidationTest {
    @Test fun allRealBuiltInArchivesRemainReadable() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = SetsManager(context)
        val assets = requireNotNull(File("src/main/assets/sets").listFiles())
        assertEquals(6, assets.size)
        for (asset in assets) {
            val archive = File.createTempFile("built-in-", ".linka", manager.getSetsDirectory())
            try {
                asset.copyTo(archive, overwrite = true)
                val manifest = manager.getSetManifest(archive)
                assertTrue(manifest.cards.isNotEmpty())
                if (asset.name == "Клавиатура.linka") {
                    assertEquals(12, manifest.columns)
                    assertEquals(4, manifest.rows)
                    assertNull(manifest.cards.first().audioPath)
                }
                val set = manager.getSet(archive.name)
                assertEquals(manifest.cards.size, set.getManifest().cards.size)
                set.getFolder().deleteRecursively()
            } finally {
                archive.delete()
            }
        }
    }

    @Test fun importedJsonUsesSharedLimitsBeforeAllocation() {
        val invalid = listOf(
            """{"rows":0}""", """{"columns":2147483648}""", """{"rows":"4"}""",
            """{"rows":1.5}""", """{"cards":[{"cardType":4}]}""",
            """{"cards":[{"imagePath":"../outside"}]}""", """{"cards":[null]}""",
            """{"cards":[{"audioPath":12}]}""", """{"cards":false}""",
            """{"cards":[${List(10_001) { """{"id":$it}""" }.joinToString(",")}]}"""
        )
        for (json in invalid) {
            assertThrows(JSONException::class.java) { SetManifest(File("config.json"), JSONObject(json)) }
        }
        val valid = SetManifest(File("config.json"), JSONObject("""{"rows":12,"columns":4,"cards":[{"id":1,"cardType":3,"imagePath":"media/nested/a"}]}"""))
        assertEquals(12, valid.rows)
        assertEquals(1, valid.cards.size)
    }
}
