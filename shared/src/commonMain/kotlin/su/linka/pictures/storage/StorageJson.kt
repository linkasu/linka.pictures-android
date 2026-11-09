package su.linka.pictures.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import su.linka.pictures.application.ProblemCode
import su.linka.pictures.text.isNativeText

@Serializable
internal data class StorageValue<T>(val schema: Int, val value: T)

internal val storageJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

internal inline fun <reified T> encode(value: T): String {
    val encoded = storageJson.encodeToString(StorageValue(schema = 1, value = value))
    if (!isNativeText(encoded)) fail(ProblemCode.INVALID_DATA)
    return encoded
}

internal inline fun <reified T> decode(value: String): T {
    if (!isNativeText(value)) fail(ProblemCode.INVALID_DATA)
    return storageJson.decodeFromString<StorageValue<T>>(value).also { require(it.schema == 1) }.value
}
