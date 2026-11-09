package su.linka.pictures.migration

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okio.buffer
import org.jetbrains.compose.resources.ExperimentalResourceApi
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.resources.Res
import su.linka.pictures.storage.StorageAbort
import kotlin.test.*

@OptIn(ExperimentalResourceApi::class)
class StarterMigrationSourceTest {
    @Test fun realResourcesAreHashedAndFreshReadersAreIndependent() = runTest {
        val source = StarterMigrationSource()
        val entries = source.entries()
        assertEquals(5, entries.size)
        assertEquals(setOf("Общаюсь", "Еда и питьё", "Занятия", "Самочувствие", "Буквы"), entries.map { it.name }.toSet())
        for (entry in entries) {
            assertEquals(entry.hash, sourceHash(source.open(entry), entry.size))
            source.open(entry).buffer().use { it.readByte() }
            assertEquals(entry.hash, sourceHash(source.open(entry), entry.size))
        }
        val settings = LegacyUpgradeSettings(emptyMap(), false)
        assertEquals(inventory(source, settings), inventory(source, settings))
        assertEquals(entries, source.entries())
    }

    @Test fun cachedBytesDoNotAliasTheResourceCallback() = runTest {
        val resources = mutableMapOf<String, ByteArray>()
        val source = StarterMigrationSource { path ->
            Res.readBytes(path).also { resources[path] = it }
        }
        val entries = source.entries()
        resources.filterKeys { it.endsWith(".linka") }.values.forEach { it.fill(0) }
        for (entry in entries) assertEquals(entry.hash, sourceHash(source.open(entry), entry.size))
        assertFailsWith<StorageAbort> { source.open(entries.first().copy(name = "unknown")) }
    }

    @Test fun invalidCatalogsFailBeforeReadingAnyArchive() = runTest {
        val original = Json.parseToJsonElement(Res.readBytes("files/starter/catalog.json").decodeToString()).jsonObject
        fun changedSet(index: Int = 0, change: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
            val sets = original.getValue("sets").jsonArray.toMutableList()
            sets[index] = JsonObject(sets[index].jsonObject.toMutableMap().also(change))
            return JsonObject(original + ("sets" to JsonArray(sets)))
        }
        val cases = listOf(
            JsonObject(original + ("schema" to JsonPrimitive("unknown"))) to ProblemCode.INVALID_DATA,
            changedSet { it["file"] = JsonPrimitive("../ru.core.v1.linka") } to ProblemCode.INVALID_DATA,
            changedSet { it["seedKey"] = JsonPrimitive("../core") } to ProblemCode.INVALID_DATA,
            changedSet(1) { it["seedKey"] = JsonPrimitive("ru.core.v1"); it["file"] = JsonPrimitive("ru.core.v1.linka") } to ProblemCode.INVALID_DATA,
            changedSet(1) { it["displayName"] = JsonPrimitive("Общаюсь") } to ProblemCode.INVALID_DATA,
            changedSet { it["displayName"] = JsonPrimitive(" ") } to ProblemCode.INVALID_DATA,
            changedSet { it["sha256"] = JsonPrimitive("not-a-hash") } to ProblemCode.INVALID_DATA,
            changedSet { it["size"] = JsonPrimitive(0) } to ProblemCode.LIMIT_EXCEEDED,
            changedSet { it["size"] = JsonPrimitive(16L * 1024 * 1024 + 1) } to ProblemCode.LIMIT_EXCEEDED,
            changedSet { it["size"] = JsonPrimitive("1") } to ProblemCode.INVALID_DATA,
            JsonObject(original + ("sets" to JsonArray(original.getValue("sets").jsonArray.dropLast(1)))) to ProblemCode.INVALID_DATA,
            JsonObject(original + ("sets" to JsonArray(original.getValue("sets").jsonArray.map {
                JsonObject(it.jsonObject + ("size" to JsonPrimitive(16L * 1024 * 1024)))
            }))) to ProblemCode.LIMIT_EXCEEDED,
        )
        for ((catalog, code) in cases) {
            val source = StarterMigrationSource { path ->
                assertEquals("files/starter/catalog.json", path)
                catalog.toString().encodeToByteArray()
            }
            val failure = assertFailsWith<StorageAbort> { source.entries() }
            assertEquals(code, assertIs<OperationResult.Failed>(failure.result).problem.code)
        }
    }

    @Test fun oversizedMalformedAndDuplicateKeyCatalogsAreRejected() = runTest {
        for ((bytes, code) in listOf(
            ByteArray(64 * 1024 + 1) to ProblemCode.LIMIT_EXCEEDED,
            "{".encodeToByteArray() to ProblemCode.INVALID_DATA,
            byteArrayOf(0xc3.toByte(), 0x28) to ProblemCode.INVALID_DATA,
            "{\"schema\":\"a\",\"schema\":\"b\"}".encodeToByteArray() to ProblemCode.INVALID_DATA,
        )) {
            val failure = assertFailsWith<StorageAbort> { StarterMigrationSource { bytes }.entries() }
            assertEquals(code, assertIs<OperationResult.Failed>(failure.result).problem.code)
        }
    }

    @Test fun sizeAndActualHashMustMatchBeforeBytesAreExposed() = runTest {
        for (truncate in listOf(false, true)) {
            val source = StarterMigrationSource { path ->
                val bytes = Res.readBytes(path)
                if (path.endsWith(".linka")) {
                    if (truncate) bytes.copyOf(bytes.size - 1) else bytes.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                } else bytes
            }
            val failure = assertFailsWith<StorageAbort> { source.entries() }
            assertEquals(ProblemCode.INTEGRITY_FAILURE, assertIs<OperationResult.Failed>(failure.result).problem.code)
        }
    }
}
