package su.linka.pictures.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeTextContractTest {
    private val page = BoardPage(PageId("page"), 1, 1, emptyList())
    private val asset = AssetId("a".repeat(64))

    @Test fun identifiersRejectNulAndMalformedUtf16BeforeNativeBinding() {
        for (value in listOf("left\u0000right", "left\uD800right", "left\uDC00right")) {
            assertFailsWith<IllegalArgumentException> { BoardId(value) }
            assertFailsWith<IllegalArgumentException> { PageId(value) }
            assertFailsWith<IllegalArgumentException> { CardId(value) }
            assertFailsWith<IllegalArgumentException> { DraftId(value) }
            assertFailsWith<IllegalArgumentException> { MessagePartId(value) }
            assertFailsWith<IllegalArgumentException> { RecoveryId(value) }
        }
    }

    @Test fun nativeBoundNamesAndMediaTypesRejectLossyValues() {
        for (value in listOf("left\u0000right", "left\uD800right", "left\uDC00right")) {
            assertFailsWith<IllegalArgumentException> { BoardDocument(BoardId("board"), value, listOf(page)) }
            assertFailsWith<IllegalArgumentException> { RetainedArchive(BoardId("retained"), value, 1, asset, RetainedReason.INVALID_ARCHIVE) }
            assertFailsWith<IllegalArgumentException> { MediaAsset(asset, MediaKind.IMAGE, value, 1) }
        }
    }

    @Test fun ordinaryUnicodeIsNotNormalizedTrimmedOrReplaced() {
        val value = "  Доска e\u0301 😀  "
        val document = BoardDocument(BoardId("доска-😀"), value, listOf(page))
        assertEquals(value, document.name)
        assertEquals(document, Json.decodeFromString<BoardDocument>(Json.encodeToString(document)))
        val card = CardDocument(CardId("card"), "left\u0000right")
        assertEquals(card, Json.decodeFromString<CardDocument>(Json.encodeToString(card)))
    }

    @Test fun deserializationCannotBypassIdentifierOrNameGuards() {
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString<BoardId>("\"left\\u0000right\"") }
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString<AssetId>("\"../unsafe\"") }
        val good = BoardDocument(BoardId("board"), "GOOD_NAME", listOf(page))
        val invalid = Json.encodeToString(good).replace("GOOD_NAME", "left\\u0000right")
        assertFailsWith<IllegalArgumentException> { Json.decodeFromString<BoardDocument>(invalid) }
    }
}
