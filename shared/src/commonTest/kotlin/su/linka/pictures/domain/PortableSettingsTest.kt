package su.linka.pictures.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class PortableSettingsTest {
    @Test fun missingMessageRowPercentDefaultsWithoutChangingPositionalFields() {
        val boards = mapOf(BoardId("board") to BoardPresentation(PageNavigation.SIDE, true))
        val positional = PortableSettings(1.5f, true, "en-US", boards)
        assertEquals(20, positional.messageRowPercent)
        val old = Json.decodeFromString<PortableSettings>("""{"textScale":1.5,"highContrast":true,"languageTag":"en-US","boards":{"board":{"navigation":"SIDE","dragToCompose":true}}}""")
        assertEquals(positional, old)
        assertEquals(20, PortableSettings().messageRowPercent)
    }

    @Test fun onlySupportedMessageRowPercentsConstructAndDecode() {
        assertEquals(setOf(16, 20, 24, 28, 32), PortableSettings.allowedMessageRowPercents)
        for (percent in PortableSettings.allowedMessageRowPercents) {
            val value = PortableSettings(messageRowPercent = percent)
            assertEquals(value, Json.decodeFromString<PortableSettings>(Json.encodeToString(value)))
        }
        for (percent in listOf(Int.MIN_VALUE, 0, 15, 17, 19, 21, 33, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { PortableSettings(messageRowPercent = percent) }
            assertFailsWith<IllegalArgumentException> { Json.decodeFromString<PortableSettings>("""{"messageRowPercent":$percent}""") }
        }
        for (value in listOf("null", "20.5", "true")) {
            assertFails { Json.decodeFromString<PortableSettings>("""{"messageRowPercent":$value}""") }
        }
    }
}
