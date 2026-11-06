package su.linka.pictures.ui.settings

import kotlin.test.*
import su.linka.pictures.application.ports.VoiceStatus

class SettingsPresentationTest {
    @Test fun portableScaleKeepsExistingRangeAndRejectsNonFiniteInput() {
        assertTrue(settingsInputValid(0.5f, "ru-RU"))
        assertTrue(settingsInputValid(3f, "ru-RU"))
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, 0.49f, 3.01f).forEach {
            assertFalse(settingsInputValid(it, "ru-RU"))
        }
    }

    @Test fun languageRemainsPortableAndDoesNotRequireAnInstalledVoiceIdentifier() {
        assertTrue(settingsInputValid(1f, "xx-Unknown"))
        assertTrue(settingsInputValid(1f, "x".repeat(128)))
        assertFalse(settingsInputValid(1f, "x".repeat(129)))
        assertFalse(settingsInputValid(1f, "  "))
    }

    @Test fun actualVoiceReadinessRemainsDistinguishable() {
        assertEquals(VoiceStatus.entries.size, VoiceStatus.entries.map(::voiceStatusLabel).toSet().size)
        assertTrue(voiceStatusLabel(VoiceStatus.MISSING_VOICE).contains("установка"))
        assertTrue(voiceStatusLabel(VoiceStatus.LOCAL_VOICE_READY).contains("готов"))
    }
}
