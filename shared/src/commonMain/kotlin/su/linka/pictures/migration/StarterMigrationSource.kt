package su.linka.pictures.migration

import kotlinx.serialization.json.*
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.Path
import okio.Source
import org.jetbrains.compose.resources.ExperimentalResourceApi
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.portability.zip.CodecFailure
import su.linka.pictures.portability.zip.boundedJson
import su.linka.pictures.portability.zip.string
import su.linka.pictures.resources.Res
import su.linka.pictures.storage.StorageAbort

/** Packaged technical assets, not evidence of human or native-media approval. */
@OptIn(ExperimentalResourceApi::class)
internal class StarterMigrationSource(
    private val readResource: suspend (String) -> ByteArray = { Res.readBytes(it) },
) : MigrationSource {
    override val kind = MigrationSourceKind.BUNDLED
    private var validated = emptyMap<LegacyEntry, ByteString>()

    override fun validateTarget(target: Path) { require(target.isAbsolute) }

    override suspend fun entries(): List<LegacyEntry> {
        validated = emptyMap()
        val catalog = readResource("files/starter/catalog.json")
        if (catalog.size > 64 * 1024) reject(ProblemCode.LIMIT_EXCEEDED)
        val boards = try {
            val root = boundedJson(catalog.decodeToString(throwOnInvalidSequence = true))
            if (root.string("schema") != "linka-starter-archives-v1" || root.string("archiveVersion") != "3.0") reject(ProblemCode.INVALID_DATA)
            val sets = root["sets"] as? JsonArray ?: reject(ProblemCode.INVALID_DATA)
            if (sets.size != 5) reject(ProblemCode.INVALID_DATA)
            val keys = mutableSetOf<String>()
            val names = mutableSetOf<String>()
            var total = 0L
            sets.map { value ->
                val set = value as? JsonObject ?: reject(ProblemCode.INVALID_DATA)
                val key = set.string("seedKey") ?: reject(ProblemCode.INVALID_DATA)
                val file = set.string("file") ?: reject(ProblemCode.INVALID_DATA)
                val name = set.string("displayName") ?: reject(ProblemCode.INVALID_DATA)
                val hash = set.string("sha256") ?: reject(ProblemCode.INVALID_DATA)
                val sizeValue = set["size"] as? JsonPrimitive ?: reject(ProblemCode.INVALID_DATA)
                val size = sizeValue.longOrNull ?: reject(ProblemCode.INVALID_DATA)
                if (!key.matches(Regex("[a-z0-9]+(?:\\.[a-z0-9]+)+")) || key.length > 128 || file != "$key.linka" || !keys.add(key) ||
                    name.isBlank() || name != name.trim() || name.length > 128 || name.any { it.isISOControl() || it in "/\\:" } || !names.add(name.lowercase()) ||
                    !hash.matches(Regex("[0-9a-f]{64}")) || sizeValue.isString) reject(ProblemCode.INVALID_DATA)
                if (size !in 1..16L * 1024 * 1024) reject(ProblemCode.LIMIT_EXCEEDED)
                total += size
                if (total > 64L * 1024 * 1024) reject(ProblemCode.LIMIT_EXCEEDED)
                file to LegacyEntry(name, size, hash)
            }
        } catch (error: CodecFailure) {
            reject(error.problem.code)
        } catch (error: StorageAbort) {
            throw error
        } catch (_: Exception) {
            reject(ProblemCode.INVALID_DATA)
        }
        val next = linkedMapOf<LegacyEntry, ByteString>()
        for ((file, entry) in boards) {
            // Resource API returns whole packaged files; bound declarations before loading archives.
            val bytes = readResource("files/starter/$file")
            if (bytes.size.toLong() != entry.size) reject(ProblemCode.INTEGRITY_FAILURE)
            val immutable = bytes.toByteString()
            if (immutable.sha256().hex() != entry.hash) reject(ProblemCode.INTEGRITY_FAILURE)
            next[entry] = immutable
        }
        validated = next
        return next.keys.toList()
    }

    override fun open(entry: LegacyEntry): Source = Buffer().write(validated[entry] ?: reject(ProblemCode.INVALID_DATA))
}
