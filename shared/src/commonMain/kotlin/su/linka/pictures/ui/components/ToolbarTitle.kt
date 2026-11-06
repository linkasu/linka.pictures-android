package su.linka.pictures.ui.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier

/** Keep the full label reachable when large text needs more lines than the toolbar viewport. */
@Composable
internal fun ToolbarTitle(text: String, modifier: Modifier = Modifier) {
    val scroll = key(text) { rememberScrollState() }
    Text(text, modifier.verticalScroll(scroll))
}
