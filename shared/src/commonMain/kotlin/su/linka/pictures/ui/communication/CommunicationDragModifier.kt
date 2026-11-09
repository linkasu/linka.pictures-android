package su.linka.pictures.ui.communication

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

@Composable
internal fun communicationDragModifier(
    gesture: CommunicationDragGesture?,
    position: Int,
    label: String,
    onPreview: (String?, Offset) -> Unit,
    onDrop: () -> Unit,
): Modifier {
    if (gesture == null) return Modifier
    var origin by remember(gesture, position) { mutableStateOf(Offset.Zero) }
    val preview by rememberUpdatedState(onPreview)
    val drop by rememberUpdatedState(onDrop)
    return Modifier.onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(gesture, position) {
            var pointer = Offset.Zero
            try {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        pointer = origin + it
                        gesture.start(position, pointer)
                        preview(label, pointer)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        pointer += amount
                        gesture.move(pointer)
                        preview(label, pointer)
                    },
                    onDragEnd = { drop(); preview(null, Offset.Zero) },
                    onDragCancel = { gesture.cancel(); preview(null, Offset.Zero) },
                )
            } finally {
                gesture.cancel()
                preview(null, Offset.Zero)
            }
        }
}
