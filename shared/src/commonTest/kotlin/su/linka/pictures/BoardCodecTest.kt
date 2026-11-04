package su.linka.pictures

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class BoardCodecTest {
    @Test
    fun missingFieldsUseLegacyDefaultsAndUnknownFieldsAreIgnored() {
        assertEquals(BoardDocument(), BoardCodec.decode("""{"futureField":true}"""))
        assertEquals(Card(7), BoardCodec.decode("""{"cards":[{"id":7,"extra":1}]}""").cards.single())
        assertEquals(Card(0), BoardCodec.decode("""{"cards":[{}]}""").cards.single())
    }

    @Test
    fun supportedVersionsAndNullableMediaRoundTrip() {
        for (version in listOf("1.0", "1.1", "1.2", "1.3")) {
            val document = BoardDocument(version, 5, 2, true, listOf(
                Card(1, "images/nested/кот.png", "Кот", "audio/кот.ogg", 1),
                Card(2), Card(3, title = "", cardType = 2)
            ))
            assertEquals(document, BoardCodec.decode(BoardCodec.encode(document)))
        }
    }

    @Test
    fun emptyLegacyMediaPathsNormalizeToNullWithoutChangingTitlesOrEditorState() {
        val expected = BoardDocument(cards = listOf(Card(1, title = "")))
        assertEquals(expected, BoardCodec.decode("""{"cards":[{"id":1,"imagePath":"","audioPath":"","title":""}]}"""))
        val card = Card(1, "", "", "")
        val encoded = BoardCodec.encode(BoardDocument(cards = listOf(card)))
        assertTrue(encoded.contains("\"imagePath\":null"))
        assertTrue(encoded.contains("\"audioPath\":null"))
        assertEquals(expected, BoardCodec.decode(encoded))
        assertEquals("", card.imagePath)
        assertEquals("", card.audioPath)
    }

    @Test
    fun placeholdersPersistAsEmptyCardsWithoutMutatingEditorState() {
        val card = Card(1, 3)
        val document = BoardDocument(cards = listOf(card))
        assertEquals(listOf(2), BoardCodec.decode(BoardCodec.encode(document)).cards.map { it.cardType })
        assertEquals(3, card.cardType)
        assertEquals(card, card.clone())
        assertNotSame(card, card.clone())
    }

    @Test
    fun dimensionsAndCardCountsCannotBypassLimitsThroughEitherCodecDirection() {
        for (invalid in listOf(Int.MIN_VALUE, -1, 0, 33, Int.MAX_VALUE)) {
            for (document in listOf(BoardDocument(rows = invalid), BoardDocument(columns = invalid))) {
                assertFailsWith<IllegalArgumentException> { BoardCodec.validate(document) }
                assertFailsWith<IllegalArgumentException> { BoardCodec.encode(document) }
                assertFailsWith<IllegalArgumentException> {
                    BoardCodec.decode("""{"rows":${document.rows},"columns":${document.columns}}""")
                }
            }
        }
        for (document in listOf(BoardDocument(rows = 4, columns = 12), BoardDocument(rows = 32, columns = 32))) {
            assertEquals(document, BoardCodec.decode(BoardCodec.encode(document)))
        }
        val boundary = BoardDocument(cards = List(10_000) { Card(it) })
        assertEquals(boundary, BoardCodec.decode(BoardCodec.encode(boundary)))
        val oversized = boundary.copy(cards = boundary.cards + Card(10_000))
        assertFailsWith<IllegalArgumentException> { BoardCodec.validate(oversized) }
        assertFailsWith<IllegalArgumentException> { BoardCodec.encode(oversized) }
        assertFailsWith<IllegalArgumentException> {
            BoardCodec.decode("""{"cards":[${List(10_001) { """{"id":$it}""" }.joinToString(",")}]}""")
        }
    }

    @Test
    fun invalidCardTypesCannotBeSilentlyNormalizedByEncode() {
        for (type in listOf(Int.MIN_VALUE, -1, 4, Int.MAX_VALUE)) {
            val document = BoardDocument(cards = listOf(Card(1, type)))
            assertFailsWith<IllegalArgumentException> { BoardCodec.validate(document) }
            assertFailsWith<IllegalArgumentException> { BoardCodec.encode(document) }
            assertFailsWith<IllegalArgumentException> {
                BoardCodec.decode("""{"cards":[{"id":1,"cardType":$type}]}""")
            }
        }
    }

    @Test
    fun unsupportedVersionsAreRejectedBeforeReadingTheirCardSchema() {
        val error = assertFailsWith<IllegalArgumentException> {
            BoardCodec.decode("""{"version":"2.0","cards":"new schema"}""")
        }
        assertTrue(error.message.orEmpty().contains("Unsupported board version: 2.0"))
        assertFailsWith<IllegalArgumentException> { BoardCodec.encode(BoardDocument(version = "2.0")) }
    }

    @Test
    fun absoluteAndTraversalMediaPathsAreRejectedForBothMediaFields() {
        for (path in listOf("/tmp/image.png", "../image.png", "images/../../image.png",
            "C:/image.png", "C:\\image.png", "\\\\server\\image.png", "images\\..\\image.png", "a\u0000b",
            "images//image.png", "images/./image.png", "images/", "a\nb")) {
            for (card in listOf(Card(1, imagePath = path), Card(1, audioPath = path))) {
                assertFailsWith<IllegalArgumentException> { BoardCodec.encode(BoardDocument(cards = listOf(card))) }
                assertFailsWith<IllegalArgumentException> { BoardCodec.validate(BoardDocument(cards = listOf(card))) }
            }
            val escaped = path.replace("\\", "\\\\").replace("\u0000", "\\u0000").replace("\n", "\\n")
            for (field in listOf("imagePath", "audioPath")) {
                assertFailsWith<IllegalArgumentException> {
                    BoardCodec.decode("""{"cards":[{"id":1,"$field":"$escaped"}]}""")
                }
            }
        }
        for (field in listOf("imagePath", "audioPath")) {
            assertFailsWith<IllegalArgumentException> {
                BoardCodec.decode("""{"cards":[{"id":1,"$field":"media/../secret"}]}""")
            }
        }
    }
}
