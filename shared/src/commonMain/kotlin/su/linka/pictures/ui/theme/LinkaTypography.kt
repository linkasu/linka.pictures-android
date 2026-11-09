package su.linka.pictures.ui.theme

import androidx.compose.material.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val LinkaTypography = Typography(
    defaultFontFamily = FontFamily.SansSerif,
    h6 = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    body1 = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    body2 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    button = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    caption = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
)
