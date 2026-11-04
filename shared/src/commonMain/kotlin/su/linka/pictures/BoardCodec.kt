package su.linka.pictures

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object BoardCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decode(source: String): BoardDocument {
        val element = json.parseToJsonElement(source)
        validateVersion(element.jsonObject["version"]?.jsonPrimitive?.content ?: "1.0")
        val document = json.decodeFromJsonElement<BoardDocument>(element)
        return document.copy(cards = document.cards.map(::normalizeMediaPaths)).also(::validate)
    }

    fun encode(document: BoardDocument): String {
        validate(document)
        // The legacy format persists editor placeholders (and higher types) as empty cards.
        val persisted = document.copy(cards = document.cards.map {
            normalizeMediaPaths(if (it.cardType >= 3) it.copy(cardType = 2) else it)
        })
        return json.encodeToString(persisted)
    }

    private fun normalizeMediaPaths(card: Card): Card = card.copy(
        imagePath = card.imagePath?.takeUnless { it.isEmpty() },
        audioPath = card.audioPath?.takeUnless { it.isEmpty() }
    )

    internal fun validate(document: BoardDocument) {
        validateVersion(document.version)
        document.cards.forEach { card ->
            validateMediaPath(card.imagePath)
            validateMediaPath(card.audioPath)
        }
    }

    private fun validateVersion(version: String) {
        require(version in listOf("1.0", "1.1", "1.2", "1.3")) {
            "Unsupported board version: $version (supported: 1.0–1.3)"
        }
    }

    private fun validateMediaPath(path: String?) {
        if (path.isNullOrEmpty()) return
        require(!path.startsWith('/') && !path.contains('\\') && !path.contains(':') &&
            !path.contains('\u0000') && path.split('/').none { it == ".." }) {
            "Unsafe board media path: $path"
        }
    }
}
