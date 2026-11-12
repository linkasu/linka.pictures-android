package su.linka.pictures.storage

import su.linka.pictures.domain.*
import kotlin.test.*

class PortableSettingsStorageTest {
    @Test fun schemaOneSettingsReadOldJsonAndPreserveNewValues() {
        val old = """{"schema":1,"value":{"textScale":1.5,"highContrast":true,"languageTag":"en-US","boards":{"board":{"navigation":"SIDE","dragToCompose":true}}}}"""
        val expected = PortableSettings(1.5f, true, "en-US", mapOf(BoardId("board") to BoardPresentation(PageNavigation.SIDE, true)))
        assertEquals(expected, decode<PortableSettings>(old))
        assertEquals(20, decode<PortableSettings>(old).messageRowPercent)
        for (percent in PortableSettings.allowedMessageRowPercents) {
            val settings = expected.copy(messageRowPercent = percent)
            val encoded = encode(settings)
            assertTrue(encoded.startsWith("{\"schema\":1,"))
            assertEquals(settings, decode<PortableSettings>(encoded))
        }
        assertFails { decode<PortableSettings>("""{"schema":1,"value":{"messageRowPercent":21}}""") }
        assertFails { decode<PortableSettings>("""{"schema":1,"value":{"untrusted":true}}""") }
    }
}
