package su.linka.pictures.bootstrap

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString

/** Keep the mounted editor/message visible, but expose no pointer, keyboard or accessibility actions. */
@Composable
internal fun ReadOnlyProduct(blocked: Boolean, phrase: String, content: @Composable () -> Unit) {
    val focus = LocalFocusManager.current
    LaunchedEffect(blocked) { if (blocked) focus.clearFocus(force = true) }
    val modifier = if (!blocked) Modifier else Modifier.onPreviewKeyEvent { true }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }.clearAndSetSemantics { disabled(); text = AnnotatedString(phrase) }
    Box(modifier) { content() }
}
