package su.linka.pictures.ui.editor

import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal enum class EditorSymbol(val path: String) {
    SAVE("M17 3H5c-1.1 0-2 .9-2 2v14c0 1.1 .9 2 2 2h14c1.1 0 2-.9 2-2V7l-4-4zM12 19c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3zM15 9H5V5h10v4z"),
    BACK("M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.42-1.41L7.83 13H20v-2z"),
    PREVIOUS("M6 6h2v12H6zm3.5 6 8.5 6V6z"),
    NEXT("M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"),
    MORE("M12 8a2 2 0 1 0 0-4a2 2 0 0 0 0 4zM12 14a2 2 0 1 0 0-4a2 2 0 0 0 0 4zM12 20a2 2 0 1 0 0-4a2 2 0 0 0 0 4z"),
    ADD("M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"),
}

@Composable
internal fun EditorIcon(symbol: EditorSymbol, modifier: Modifier = Modifier) {
    val icon = remember(symbol) {
        ImageVector.Builder(symbol.name, 24.dp, 24.dp, 24f, 24f).addPath(
            PathParser().parsePathString(symbol.path).toNodes(),
            fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd,
        ).build()
    }
    Icon(icon, contentDescription = null, modifier = modifier)
}
