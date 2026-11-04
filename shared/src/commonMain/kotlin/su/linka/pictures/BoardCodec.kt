package su.linka.pictures

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import su.linka.pictures.shared.BoardLimits
import su.linka.pictures.shared.GridDimensions

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
        // The legacy format persists editor placeholders as empty cards.
        val persisted = document.copy(cards = document.cards.map {
            normalizeMediaPaths(if (it.cardType >= 3) it.copy(cardType = 2) else it)
        })
        return json.encodeToString(persisted)
    }

    private fun normalizeMediaPaths(card: Card): Card = card.copy(
        imagePath = card.imagePath?.takeUnless { it.isEmpty() },
        audioPath = card.audioPath?.takeUnless { it.isEmpty() }
    )

    fun validate(document: BoardDocument) {
        validateVersion(document.version)
        GridDimensions(document.rows, document.columns)
        BoardLimits.validateCardCount(document.cards.size)
        document.cards.forEach { card ->
            BoardLimits.validateCardType(card.cardType)
            card.imagePath?.takeIf { it.isNotEmpty() }?.let(BoardLimits::validateMediaPath)
            card.audioPath?.takeIf { it.isNotEmpty() }?.let(BoardLimits::validateMediaPath)
        }
    }

    private fun validateVersion(version: String) {
        require(version in listOf("1.0", "1.1", "1.2", "1.3")) {
            "Unsupported board version: $version (supported: 1.0–1.3)"
        }
    }

}
