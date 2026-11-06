package su.linka.pictures.ui.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Material's three core paths, kept local because material-icons-core is not installed.
internal object LibraryIcons {
    val Add = ImageVector.Builder("Add", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 13f); horizontalLineTo(13f); verticalLineTo(19f); horizontalLineTo(11f)
            verticalLineTo(13f); horizontalLineTo(5f); verticalLineTo(11f); horizontalLineTo(11f)
            verticalLineTo(5f); horizontalLineTo(13f); verticalLineTo(11f); horizontalLineTo(19f); close()
        }
    }.build()
    val More = ImageVector.Builder("More", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            for (y in listOf(5f, 12f, 19f)) {
                moveTo(12f, y - 2f)
                arcToRelative(2f, 2f, 0f, true, true, 0f, 4f)
                arcToRelative(2f, 2f, 0f, true, true, 0f, -4f)
                close()
            }
        }
    }.build()
    val Back = ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f, autoMirror = true).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(20f, 11f); horizontalLineTo(7.83f); lineTo(13.42f, 5.41f); lineTo(12f, 4f)
            lineTo(4f, 12f); lineTo(12f, 20f); lineTo(13.42f, 18.59f); lineTo(7.83f, 13f)
            horizontalLineTo(20f); close()
        }
    }.build()
}
