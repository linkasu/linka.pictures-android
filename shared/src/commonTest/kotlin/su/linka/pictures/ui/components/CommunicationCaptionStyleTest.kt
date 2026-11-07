package su.linka.pictures.ui.components

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class CommunicationCaptionStyleTest {
    @Test
    fun captionsKeepElectronBaseSizeAndRespectAccessibilityScaling() {
        assertEquals(24.sp, CommunicationCaptionStyle.fontSize)
        assertEquals(FontWeight.Bold, CommunicationCaptionStyle.fontWeight)
        assertEquals(28.8.sp, CommunicationCaptionStyle.lineHeight)
        for (scale in listOf(0.5f, 1f, 2f, 6f)) {
            with(Density(2f, fontScale = scale)) {
                assertEquals(48f * scale, CommunicationCaptionStyle.fontSize.toPx())
            }
        }
    }
}
