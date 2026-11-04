package su.linka.pictures

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ArchiveIntegrityInstrumentedTest {
    @Test fun nestedMediaRoundTripAndFailedReplacementPreserveTheOriginalOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = SetsManager(context)
        val name = "integrity-${UUID.randomUUID()}.linka"
        val source = manager.createSet()
        var exported: File? = null
        try {
            val image = File(source.getFolder(), "images/card.png").apply {
                parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3))
            }
            val audio = File(source.getFolder(), "audio/card.ogg").apply {
                parentFile!!.mkdirs(); writeBytes(byteArrayOf(4, 5, 6))
            }
            source.addCard(0, Card(0, "images/card.png", "Round trip", "audio/card.ogg"))
            exported = manager.prepareExport(source)
            val original = exported.readBytes()
            exported.inputStream().use { manager.importSet(it, name) }
            manager.getSet(name).use { reopened ->
                assertEquals(source.getManifest().toDocument(), reopened.getManifest().toDocument())
                assertArrayEquals(image.readBytes(), File(reopened.getFolder(), "images/card.png").readBytes())
                assertArrayEquals(audio.readBytes(), File(reopened.getFolder(), "audio/card.ogg").readBytes())
            }
            val broken = object : InputStream() {
                private var count = 0
                override fun read(): Int = if (count++ < 16) 42 else throw IOException("Interrupted import")
            }
            try {
                manager.importSet(broken, name, true)
                fail("Interrupted replacement must fail")
            } catch (_: IOException) { }
            assertArrayEquals(original, manager.getSetFile(name).readBytes())
            val manifest = manager.getSetManifest(name)
            manager.delete(manifest)
            assertFalse(manager.getSetFile(name).exists())
        } finally {
            source.close()
            exported?.delete()
            manager.getSetFile(name).delete()
        }
    }
}
