package su.linka.pictures.ui.app

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import su.linka.pictures.ui.components.ToolbarTitle
import su.linka.pictures.ui.theme.LocalLinkaPalette

/** Shared visual chrome only; navigation and command admission remain with callers. */
@Composable
internal fun ProductTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = LocalLinkaPalette.current
    TopAppBar(
        title = { ToolbarTitle(title) }, modifier = modifier,
        navigationIcon = navigationIcon, actions = actions,
        backgroundColor = palette.surface, contentColor = palette.text, elevation = 0.dp,
    )
}
