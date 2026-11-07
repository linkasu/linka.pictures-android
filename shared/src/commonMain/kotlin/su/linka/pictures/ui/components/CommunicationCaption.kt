package su.linka.pictures.ui.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

internal val CommunicationCaptionStyle = TextStyle(
    fontSize = 24.sp, lineHeight = 28.8.sp, fontWeight = FontWeight.Bold,
    letterSpacing = 0.sp, lineBreak = LineBreak.Simple, hyphens = Hyphens.None,
    textAlign = TextAlign.Center,
)

@Composable
internal fun CommunicationCaption(
    label: String,
    availableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val renderedStyle = MaterialTheme.typography.body1.merge(CommunicationCaptionStyle)
    Text(label, modifier = modifier.widthIn(max = availableWidth), style = renderedStyle, softWrap = true)
}
