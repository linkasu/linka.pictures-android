package su.linka.pictures.shared

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.HashingSource
import okio.buffer
import okio.use
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BootstrapDependenciesTest {
    @Serializable
    private data class Payload(val text: String)

    @Test
    fun unicodeJsonStreamHashAndSQLiteRoundTrip() = runTest {
        assertTrue(platformName().isNotBlank())
        val text = "P02 'мёд' 👋\n"
        val encoded = async { Json.encodeToString(Payload(text)) }.await()
        assertEquals(text, Json.decodeFromString<Payload>(encoded).text)
        val fs = FileSystem.SYSTEM
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "linka-p02-${Random.nextLong().toString(16)}"
        fs.createDirectory(directory, mustCreate = true)
        val stream = directory / "text.utf8"
        val database = directory / "bootstrap.sqlite"
        try {
            fs.sink(stream).buffer().use { it.writeUtf8(Json.decodeFromString<Payload>(encoded).text) }
            val hashing = HashingSource.sha256(fs.source(stream))
            hashing.buffer().use { assertEquals(text, it.readUtf8()) }
            assertEquals("de65987ee7e25b5910ba398715cba5745769314796ceb57e5bf7f8959d00d4f3", hashing.hash.hex())
            BundledSQLiteDriver().open(database.toString()).use { connection ->
                connection.prepare("CREATE TABLE bootstrap (text TEXT NOT NULL)").use { it.step() }
                connection.prepare("INSERT INTO bootstrap VALUES (?)").use {
                    it.bindText(1, text)
                    it.step()
                }
            }
            BundledSQLiteDriver().open(database.toString()).use { connection ->
                connection.prepare("SELECT text FROM bootstrap").use {
                    assertTrue(it.step())
                    assertEquals(text, it.getText(0))
                }
            }
        } finally {
            fs.delete(stream)
            fs.delete(database)
            fs.delete(directory, mustExist = true)
        }
        assertTrue(!fs.exists(directory))
    }
}
