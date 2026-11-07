package su.linka.pictures.ui.components

import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier

/** The toolbar grows with its title; it is never a partially scrolled text viewport. */
@Composable
internal fun ToolbarTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() })
}
