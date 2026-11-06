package su.linka.pictures.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunicationCaptionWidthTest {
    @Test
    fun balancesNinePlusOneIntoFivePlusFiveWithoutAddingLines() {
        val calls = mutableListOf<Int>()
        val width = communicationCaptionWidth(90, 2, true) {
            calls += it
            val glyphsPerLine = (it / 10).coerceAtLeast(1)
            (10 + glyphsPerLine - 1) / glyphsPerLine
        }
        assertEquals(50, width)
        assertTrue(calls.size <= 8)
    }

    @Test
    fun unchangedCaptionsDoNotNeedMeasurements() {
        val neverMeasure: (Int) -> Int = { error("No balancing needed") }
        assertEquals(90, communicationCaptionWidth(90, 1, true, neverMeasure))
        assertEquals(90, communicationCaptionWidth(90, 2, false, neverMeasure))
        assertEquals(0, communicationCaptionWidth(0, 2, true, neverMeasure))
        assertEquals(-1, communicationCaptionWidth(-1, 2, true, neverMeasure))
        assertEquals(1, communicationCaptionWidth(1, 2, true, neverMeasure))
    }

    @Test
    fun usesMeasuredThresholdsRatherThanEqualGlyphWidths() {
        var calls = 0
        assertEquals(637, communicationCaptionWidth(1024, 2, true) { width ->
            calls++
            when {
                width < 637 -> 3
                width < 1400 -> 2
                else -> 1
            }
        })
        assertTrue(calls <= 11)
    }

    @Test
    fun keepsOriginalWidthWhenFinalCandidateChangesLineCount() {
        assertEquals(90, communicationCaptionWidth(90, 2, true) { 1 })
        var callsAtCandidate = 0
        assertEquals(90, communicationCaptionWidth(90, 2, true) { width ->
            if (width < 50) 3
            else if (width == 50 && ++callsAtCandidate > 1) 3
            else 2
        })
    }
}
