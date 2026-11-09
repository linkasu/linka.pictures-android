package su.linka.pictures.ui.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import su.linka.pictures.ui.components.ToolbarTitle
import su.linka.pictures.ui.theme.LocalLinkaPalette

/** Shared visual chrome only; navigation and command admission remain with callers. */
@Composable
internal fun ProductTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    minimumRowHeight: Dp = 56.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalLinkaPalette.current
    Surface(modifier, color = palette.surface, contentColor = palette.text) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 600.dp && LocalDensity.current.fontScale > 1f
            ProvideTextStyle(MaterialTheme.typography.h6) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = minimumRowHeight), verticalAlignment = Alignment.CenterVertically) {
                        navigationIcon?.invoke()
                        if (stacked) Spacer(Modifier.weight(1f))
                        else Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) { ToolbarTitle(title) }
                        actions()
                    }
                    if (stacked) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) { ToolbarTitle(title) }
                }
            }
        }
    }
}
