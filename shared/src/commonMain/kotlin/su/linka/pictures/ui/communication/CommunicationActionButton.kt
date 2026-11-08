package su.linka.pictures.ui.communication

import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.resources.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import su.linka.pictures.ui.theme.LinkaMetrics
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
internal fun CommunicationActionButton(
    icon: CommunicationIcon, label: String, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLinkaPalette.current
    OutlinedButton(
        onClick, modifier.sizeIn(minWidth = LinkaMetrics.touchTarget, minHeight = LinkaMetrics.touchTarget)
            .semantics { contentDescription = label },
        enabled = enabled, shape = RoundedCornerShape(LinkaMetrics.cardRadius),
        border = BorderStroke(1.dp, palette.border), contentPadding = PaddingValues(4.dp),
        colors = ButtonDefaults.outlinedButtonColors(backgroundColor = palette.navigationSurface,
            contentColor = palette.text, disabledContentColor = palette.disabledText),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon.vector, null, Modifier.size(24.dp))
            Text(when (icon) {
                CommunicationIcon.Backspace -> stringResource(Res.string.remove)
                CommunicationIcon.Clear -> stringResource(Res.string.clear)
                CommunicationIcon.Undo -> stringResource(Res.string.undo)
                CommunicationIcon.Stop -> stringResource(Res.string.stop)
                else -> label
            }, modifier = Modifier.clearAndSetSemantics {}, fontSize = 12.sp, lineHeight = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
