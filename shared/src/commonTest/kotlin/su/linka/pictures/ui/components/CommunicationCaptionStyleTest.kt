package su.linka.pictures.ui.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunicationCaptionStyleTest {
    @Test
    fun spaceMarkScalesWithCaptionAndFitsNarrowWidthsIncludingItsStroke() {
        for (scale in listOf(0.5f, 1f, 2f, 6f)) {
            with(Density(2f, fontScale = scale)) {
                val fontWidth = CommunicationCaptionStyle.fontSize.toPx()
                val height = CommunicationCaptionStyle.lineHeight.toPx()
                for (width in listOf(0f, 1f, fontWidth / 4f, fontWidth)) {
                    val size = Size(width, height)
                    val bounds = communicationSpaceMarkBounds(size)
                    val halfStroke = size.minDimension * 0.04f
                    assertEquals(width * 0.7f, bounds.width, 0.001f)
                    assertEquals(height * 0.25f, bounds.height, 0.001f)
                    assertTrue(bounds.left - halfStroke >= 0f)
                    assertTrue(bounds.right + halfStroke <= width)
                    assertTrue(bounds.top - halfStroke >= 0f)
                    assertTrue(bounds.bottom + halfStroke <= height)
                }
            }
        }
    }

    @Test
    fun captionsKeepElectronBaseSizeAndRespectAccessibilityScaling() {
        assertEquals(24.sp, CommunicationCaptionStyle.fontSize)
        assertEquals(FontWeight.Bold, CommunicationCaptionStyle.fontWeight)
        assertEquals(28.8.sp, CommunicationCaptionStyle.lineHeight)
        assertEquals(androidx.compose.ui.text.style.Hyphens.None, CommunicationCaptionStyle.hyphens)
        for (scale in listOf(0.5f, 1f, 2f, 6f)) {
            with(Density(2f, fontScale = scale)) {
                assertEquals(48f * scale, CommunicationCaptionStyle.fontSize.toPx())
            }
        }
    }
}
