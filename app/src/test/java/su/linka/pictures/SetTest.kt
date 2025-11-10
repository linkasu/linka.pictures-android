package su.linka.pictures

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.io.extension
import kotlin.io.nameWithoutExtension

class SetTest {

    private lateinit var workingDir: File
    private lateinit var manifest: SetManifest
    private lateinit var set: Set

    @Before
    fun setUp() {
        workingDir = Files.createTempDirectory("set-test").toFile()
        manifest = SetManifest(File(workingDir, "config.json"))
        manifest.cards.clear()
        set = Set(manifest, workingDir)
    }

    @After
    fun tearDown() {
        workingDir.deleteRecursively()
    }

    @Test
    fun addCard_extendsListWithPlaceholderCards() {
        manifest.cards.add(Card(1, cardType = 0))
        val newCard = Card(id = 5, imagePath = "image.png", title = "New", audioPath = "audio.mp3", cardType = 1)

        set.addCard(3, newCard)

        assertEquals(4, manifest.cards.size)
        assertEquals(Card(0, cardType = 3), manifest.cards[1])
        assertEquals(Card(0, cardType = 3), manifest.cards[2])
        assertSame(newCard, manifest.cards[3])
    }

    @Test
    fun addCard_replacesExistingCardAtPosition() {
        manifest.cards.add(Card(1, cardType = 0))
        manifest.cards.add(Card(2, cardType = 1))
        val replacement = Card(id = 7, cardType = 2)

        set.addCard(1, replacement)

        assertEquals(2, manifest.cards.size)
        assertSame(replacement, manifest.cards[1])
    }

    @Test
    fun copyAudioFile_generatesUniqueNamesForDuplicates() {
        val source = File.createTempFile("audio", ".mp3").apply {
            writeText("original")
        }

        val firstCopy = set.copyAudioFile(source)
        assertTrue(firstCopy.exists())
        assertEquals("original", firstCopy.readText())

        val otherDir = Files.createTempDirectory("set-test-src").toFile()
        val duplicateSource = File(otherDir, source.name).apply {
            writeText("duplicate")
        }

        val secondCopy = set.copyAudioFile(duplicateSource)

        assertTrue(secondCopy.exists())
        assertEquals("duplicate", secondCopy.readText())
        assertEquals("${source.nameWithoutExtension}-1.${source.extension}", secondCopy.name)

        source.delete()
        duplicateSource.delete()
        otherDir.deleteRecursively()
    }
}
