package su.linka.pictures.ui.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import su.linka.pictures.ui.theme.LocalLinkaPalette

@Composable
internal fun ProductSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalLinkaPalette.current
    Surface(
        Modifier.fillMaxWidth(), color = palette.surface, contentColor = palette.text,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, palette.border.copy(alpha = 0.3f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}
