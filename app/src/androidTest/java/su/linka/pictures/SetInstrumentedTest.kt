package su.linka.pictures

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SetInstrumentedTest {

    private lateinit var workingDir: File
    private lateinit var manifest: SetManifest
    private lateinit var set: Set

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        workingDir = File(context.cacheDir, "set-test-${System.currentTimeMillis()}").apply {
            deleteRecursively()
            mkdirs()
        }
        val configFile = File(workingDir, "config.json")
        manifest = SetManifest(configFile)
        set = Set(manifest, workingDir)
    }

    @After
    fun tearDown() {
        workingDir.deleteRecursively()
    }

    @Test
    fun copyAudioFile_whenSourceAlreadyInSet_reusesSameFile() {
        val audioFile = File(workingDir, "voice.3gp").apply {
            writeText("original")
        }

        val result = set.copyAudioFile(audioFile)

        assertEquals(audioFile.absolutePath, result.absolutePath)
        assertEquals("original", result.readText())
    }

    @Test
    fun copyAudioFile_whenNameConflicts_generatesUniqueTarget() {
        File(workingDir, "voice.3gp").apply {
            writeText("existing")
        }
        val externalDir = File(workingDir.parentFile, "external-${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        val source = File(externalDir, "voice.3gp").apply {
            writeText("fresh")
        }

        val result = set.copyAudioFile(source)

        assertTrue(result.name.startsWith("voice-"))
        assertEquals("fresh", result.readText())
        assertNotEquals(File(workingDir, "voice.3gp").absolutePath, result.absolutePath)
        externalDir.deleteRecursively()
    }

    @Test
    fun writeConfig_persistsManifestToConfigFile() {
        manifest.columns = 5
        manifest.rows = 2
        manifest.withoutSpace = true
        manifest.cards.clear()
        manifest.cards.add(
            Card(
                id = 1,
                imagePath = "image.png",
                title = "Hello",
                audioPath = "voice.3gp",
                cardType = 0
            )
        )

        set.writeConfig()

        val json = JSONObject(manifest.configFile.readText())
        assertEquals(5, json.getInt("columns"))
        assertEquals(2, json.getInt("rows"))
        assertEquals(true, json.getBoolean("withoutSpace"))
        val firstCard = json.getJSONArray("cards").getJSONObject(0)
        assertEquals("Hello", firstCard.getString("title"))
        assertEquals("voice.3gp", firstCard.getString("audioPath"))
        assertEquals("image.png", firstCard.getString("imagePath"))
    }
}
