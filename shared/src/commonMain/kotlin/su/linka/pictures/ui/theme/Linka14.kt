package su.linka.pictures.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** v1.4 resources and verified baseline drawable colours, not a new palette. */
object Linka14 {
    val primary = Color(0xFF197377)
    val primaryDark = Color(0xFFBED64F)
    val accent = Color(0xFFFBCC30)
    val cardTop = Color(0xFFE8E8E8)
    val cardBottom = Color(0xFFD1D1D1)
    val cardBorderTop = Color(0xFFD7D7D7)
    val cardBorderBottom = Color(0xFF777777)
    val cardMargin = 0.2.dp
    // btn_default_normal xhdpi nine-patch content markers, divided by density 2.
    val cardPadding = PaddingValues(start = 10.5.dp, top = 6.5.dp, end = 11.5.dp, bottom = 11.5.dp)
    val cardCorner = 2.dp
}
