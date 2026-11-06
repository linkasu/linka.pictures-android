package su.linka.pictures.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunicationCaptionSizeTest {
    @Test
    fun narrowPortraitFitsTheWidestWordWithoutBreakingOffLetters() {
        val measured = mutableListOf<Pair<String, Float>>()
        val size = communicationCaptionSize("КУШАТЬ\nКУПАТЬСЯ\tПИТЬ", 156f, 84f) { word, preferred ->
            measured += word to preferred
            word.length * preferred * 0.65f
        }
        assertEquals(listOf("КУШАТЬ", "КУПАТЬСЯ", "ПИТЬ"), measured.map { it.first })
        assertTrue(measured.all { it.second == 26f })
        assertTrue(size in 12f..26f)
        assertEquals(83f, 8 * size * 0.65f, 0.001f)

        // A shorter word can be wider: use measured glyphs, not character count.
        assertEquals(19.8f, communicationCaptionSize("iiii WWW", 180f, 100f) { word, _ ->
            if (word == "WWW") 150f else 30f
        }, 0.001f)
    }

    @Test
    fun fittingTabletAndLargeSingleCardKeepHeightDerivedTypography() {
        assertEquals(40f, communicationCaptionSize("КУПАТЬСЯ", 240f, 240f) { _, _ -> 180f })
        assertEquals(100f, communicationCaptionSize("ХОЧУ", 600f, 600f) { _, _ -> 350f })
        assertEquals(40f, communicationCaptionSize("ХОЧУ", 240f, 180f) { _, _ -> 180f })
    }

    @Test
    fun emptyAndUnboundedCaptionsKeepTheirPreferredSize() {
        val neverMeasure: (String, Float) -> Float = { _, _ -> error("No word needs measuring") }
        assertEquals(30f, communicationCaptionSize(" \t\n", 180f, 90f, neverMeasure))
        assertEquals(30f, communicationCaptionSize("ХОЧУ", 180f, Float.POSITIVE_INFINITY, neverMeasure))
        assertEquals(14f, communicationCaptionSize("ХОЧУ", Float.POSITIVE_INFINITY, 90f) { _, size ->
            assertEquals(14f, size)
            40f
        })
    }

    @Test
    fun tinyCellsAndVeryLongWordsNeverShrinkBelowTwelveSp() {
        assertEquals(12f, communicationCaptionSize("Я", 30f, 20f) { _, size ->
            assertEquals(12f, size)
            10f
        })
        assertEquals(12f, communicationCaptionSize("Сверхдлинноеслово", 180f, 40f) { _, _ -> 600f })
        assertEquals(12f, communicationCaptionSize("Я", 0f, 0f) { _, _ -> error("No available width") })
    }

    @Test
    fun invalidDimensionsAndMeasurementsCannotProduceAnUnsafeSize() {
        assertEquals(14f, communicationCaptionSize("Я", Float.NaN, 90f) { _, _ -> 10f })
        assertEquals(12f, communicationCaptionSize("Я", 180f, Float.NaN) { _, _ -> 10f })
        for (width in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertEquals(12f, communicationCaptionSize("Я", 180f, 90f) { _, _ -> width })
        }
        assertEquals(30f, communicationCaptionSize("Я", 180f, 90f) { _, _ -> 0f })
    }
}
