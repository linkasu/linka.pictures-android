package su.linka.pictures.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import su.linka.pictures.shared.generated.resources.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DimensionControl(label: String, value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value")
        FlowRow {
            TextButton(onClick = { onChange(value - 1) }, enabled = enabled && value > 1) {
                Text(stringResource(Res.string.decrease_dimension, label))
            }
            TextButton(onClick = { onChange(value + 1) }, enabled = enabled && value < Int.MAX_VALUE) {
                Text(stringResource(Res.string.increase_dimension, label))
            }
        }
    }
}
