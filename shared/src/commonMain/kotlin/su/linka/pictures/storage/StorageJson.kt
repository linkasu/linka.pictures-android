package su.linka.pictures.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class StorageValue<T>(val schema: Int, val value: T)

internal val storageJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

internal inline fun <reified T> encode(value: T): String =
    storageJson.encodeToString(StorageValue(schema = 1, value = value))

internal inline fun <reified T> decode(value: String): T =
    storageJson.decodeFromString<StorageValue<T>>(value).also { require(it.schema == 1) }.value
