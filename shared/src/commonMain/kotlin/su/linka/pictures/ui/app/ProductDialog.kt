package su.linka.pictures.ui.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import su.linka.pictures.ui.theme.LocalLinkaPalette

/** Keep actions pinned unless a short IME viewport would squeeze the editable body to zero. */
@Composable
internal fun ProductDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalLinkaPalette.current
    Dialog(onDismiss, properties = productDialogProperties()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val wholeScroll = productDialogUsesWholeScroll(maxHeight.value, LocalDensity.current.fontScale)
            val dialogScroll = rememberScrollState()
            val bodyScroll = rememberScrollState()
            Surface(
                Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight),
                color = palette.surface, contentColor = palette.text, shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(16.dp).then(if (wholeScroll) Modifier.verticalScroll(dialogScroll) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
                    Column(
                        Modifier.fillMaxWidth().then(if (wholeScroll) Modifier
                            else Modifier.weight(1f, fill = false).verticalScroll(bodyScroll)),
                        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
                    )
                    actions()
                }
            }
        }
    }
}

internal fun productDialogUsesWholeScroll(availableHeight: Float, fontScale: Float): Boolean =
    availableHeight < 240f * maxOf(1f, fontScale)
