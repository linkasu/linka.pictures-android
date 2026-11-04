package su.linka.pictures.portability.linka

import kotlinx.serialization.json.*
import su.linka.pictures.application.OperationResult
import su.linka.pictures.application.Problem
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.text.archivePathCollisionKey

internal const val ARCHIVE_LIMIT = 500L * 1024 * 1024
internal const val MEDIA_LIMIT = 64L * 1024 * 1024
internal const val CONFIG_LIMIT = 8L * 1024 * 1024
internal val activeKeys = setOf("action", "actions", "command", "commands", "event", "events", "script", "scripts", "navigation")
internal val wireJson = Json { isLenient = false }

internal class CodecFailure(val problem: Problem) : Exception()
internal fun fail(code: ProblemCode): Nothing = throw CodecFailure(Problem(code))
internal fun checkData(ok: Boolean) { if (!ok) fail(ProblemCode.INVALID_DATA) }
internal fun checkZip(ok: Boolean) { if (!ok) fail(ProblemCode.UNSAFE_ARCHIVE) }
internal fun limit(ok: Boolean) { if (!ok) fail(ProblemCode.LIMIT_EXCEEDED) }
internal fun <T> OperationResult<T>.value(): T = when (this) {
    is OperationResult.Completed -> value
    is OperationResult.Failed -> throw CodecFailure(problem)
    OperationResult.Cancelled -> throw CodecCancelled()
    OperationResult.UnconfirmedCommit -> throw CodecUnconfirmed()
}
internal class CodecCancelled : Exception()
internal class CodecUnconfirmed : Exception()

internal fun safePath(path: String): String {
    checkZip(path.isNotEmpty() && path.length <= 65535 && path.none { it == '\\' || it == '\u0000' || it == ':' })
    val segments = path.removeSuffix("/").split('/')
    for (s in segments) {
        checkZip(s.isNotEmpty() && s != "." && s != ".." && !s.endsWith('.') && !s.endsWith(' '))
        val base = s.substringBefore('.').uppercase()
        checkZip(base !in setOf("CON", "PRN", "AUX", "NUL") &&
            !(base.length == 4 && (base.startsWith("COM") || base.startsWith("LPT")) && base[3] in '1'..'9'))
    }
    return archivePathCollisionKey(path)
}

// Bound nesting and decoded strings before constructing the JSON DOM; reject duplicate object keys.
internal fun boundedJson(text: String): JsonObject {
    limit(text.encodeToByteArray().size.toLong() <= CONFIG_LIMIT)
    val objects = mutableListOf<MutableSet<String>?>()
    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '{', '[' -> { objects.add(if (text[i] == '{') mutableSetOf() else null); limit(objects.size <= 64); i++ }
            '}', ']' -> { checkData(objects.isNotEmpty()); objects.removeAt(objects.lastIndex); i++ }
            '"' -> {
                val start = i++
                var units = 0
                var closed = false
                while (i < text.length) {
                    val c = text[i++]
                    if (c == '"') { closed = true; break }
                    if (c == '\\') {
                        checkData(i < text.length)
                        if (text[i++] == 'u') { checkData(i + 4 <= text.length); i += 4 }
                    }
                    units++
                    limit(units <= 65536)
                }
                checkData(closed)
                var next = i
                while (next < text.length && text[next].isWhitespace()) next++
                if (next < text.length && text[next] == ':') {
                    limit(units <= 256)
                    val key = wireJson.decodeFromString<String>(text.substring(start, i))
                    val keys = objects.lastOrNull()
                    checkData(keys != null && keys.add(key))
                }
            }
            else -> i++
        }
    }
    checkData(objects.isEmpty())
    return wireJson.parseToJsonElement(text) as? JsonObject ?: fail(ProblemCode.INVALID_DATA)
}

internal fun JsonObject.structural() {
    if (keys.any { it in activeKeys }) fail(ProblemCode.UNSUPPORTED_FEATURE)
}
internal fun JsonObject.string(key: String, default: String? = null): String? {
    val v = this[key] ?: return default
    if (v == JsonNull) return null
    checkData(v is JsonPrimitive && v.isString)
    return v.jsonPrimitive.content
}
internal fun JsonObject.bool(key: String, default: Boolean = false): Boolean {
    val v = this[key] ?: return default
    checkData(v is JsonPrimitive && !v.isString && v.booleanOrNull != null)
    return v.jsonPrimitive.boolean
}
internal fun JsonObject.dimension(key: String, legacy: Boolean): Int {
    val v = this[key] as? JsonPrimitive ?: fail(ProblemCode.INVALID_DATA)
    checkData(!v.isString || legacy)
    checkData(v.content.isNotEmpty() && v.content[0] in '1'..'9' && v.content.all { it in '0'..'9' })
    val n = v.content.toLongOrNull() ?: fail(ProblemCode.LIMIT_EXCEEDED)
    limit(n <= 64)
    return n.toInt()
}

internal fun passive(fields: JsonObject): JsonObject {
    fun validate(v: JsonElement) {
        when (v) {
            is JsonObject -> v.values.forEach(::validate)
            is JsonArray -> v.forEach(::validate)
            is JsonPrimitive -> if (v.isString) {
                val s = v.content.lowercase()
                checkData(listOf("file:", "content:", "ph:", "assets-library:").none(s::startsWith))
            }
        }
    }
    validate(fields)
    return fields
}
