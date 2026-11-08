package su.linka.pictures.ui.editor

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Ephemeral gesture only; the actor rechecks authority/revision on the resulting MoveCell. */
@Stable
internal class EditorCellDrag {
    var source: Int? by mutableStateOf(null)
        private set
    var target: Int? by mutableStateOf(null)
        private set
    var suppressTap: Boolean = false
        private set

    fun resetTapSuppression() { suppressTap = false }

    fun start(point: Offset, bounds: Map<Int, Rect>) {
        source = hit(point, bounds)
        target = source
        suppressTap = source != null
    }

    fun move(point: Offset, bounds: Map<Int, Rect>) {
        if (source != null) target = hit(point, bounds)
    }

    fun finish(): Pair<Int, Int>? {
        val from = source
        val to = target
        cancel()
        return if (from != null && to != null && from != to) from to to else null
    }

    fun cancel() { source = null; target = null }

    private fun hit(point: Offset, bounds: Map<Int, Rect>): Int? =
        bounds.entries.firstOrNull { it.value.contains(point) }?.key
}
