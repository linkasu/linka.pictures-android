package su.linka.pictures.ui.communication

import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Material paths, including the same backspace/record_voice_over glyphs used by 1.4.
internal enum class CommunicationIcon(path: String) {
    Back("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20z"),
    Backspace("M22,3H7c-0.69,0 -1.23,0.35 -1.59,0.88L0,12l5.41,8.11C5.77,20.64 6.31,21 7,21h15c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM19,15.59L17.59,17 14,13.41 10.41,17 9,15.59 12.59,12 9,8.41 10.41,7 14,10.59 17.59,7 19,8.41 15.41,12z"),
    Speak("M9,9m-4,0a4,4 0,1 1,8 0a4,4 0,1 1,-8 0 M9,15c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4zM16.76,5.36l-1.68,1.69c0.84,1.18 0.84,2.71 0,3.89l1.68,1.69c2.02,-2.02 2.02,-5.07 0,-7.27zM20.07,2l-1.63,1.63c2.77,3.02 2.77,7.56 0,10.74L20.07,16c3.9,-3.89 3.91,-9.95 0,-14z"),
    Stop("M6,6h12v12H6z"),
    Clear("M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"),
    Menu("M12,4m-2,0a2,2 0,1 1,4 0a2,2 0,1 1,-4 0 M12,12m-2,0a2,2 0,1 1,4 0a2,2 0,1 1,-4 0 M12,20m-2,0a2,2 0,1 1,4 0a2,2 0,1 1,-4 0"),
    Previous("M6,6h2v12H6zM18,6l-8,6 8,6z"),
    Next("M16,6h2v12h-2zM6,6v12l8,-6z"),
    Settings("M19.43,12.98c0.04,-0.32 0.07,-0.65 0.07,-0.98s-0.03,-0.66 -0.08,-0.98l2.11,-1.65 -2,-3.46 -2.49,1c-0.51,-0.4 -1.07,-0.73 -1.69,-0.98L15,3h-4l-0.38,2.93c-0.62,0.25 -1.18,0.58 -1.69,0.98l-2.49,-1 -2,3.46 2.11,1.65c-0.05,0.32 -0.08,0.66 -0.08,0.98s0.03,0.66 0.08,0.98l-2.11,1.65 2,3.46 2.49,-1c0.51,0.4 1.07,0.73 1.69,0.98L11,21h4l0.38,-2.93c0.62,-0.25 1.18,-0.58 1.69,-0.98l2.49,1 2,-3.46zM13,15.5a3.5,3.5 0,1 1,0,-7 3.5,3.5 0,0 1,0,7z");

    val vector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
    }.build()
}

@Composable
internal fun CommunicationIconButton(icon: CommunicationIcon, label: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier, enabled) {
        Icon(icon.vector, label, modifier = Modifier.size(24.dp))
    }
}
