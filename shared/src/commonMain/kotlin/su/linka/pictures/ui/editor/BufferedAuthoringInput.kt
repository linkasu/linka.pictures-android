package su.linka.pictures.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable

/** Immediate UI input; handling acknowledges admission, not durable saving. */
@Stable
internal class BufferedAuthoringInput<T>(initial: T, editVersion: Long) {
    var value by mutableStateOf(initial)
        private set
    var pending by mutableStateOf(false)
        private set
    private var token: Any? = null
    private var minimumEditVersion = editVersion
    val admittedEditVersion get() = minimumEditVersion
    private var admission: ((Boolean) -> Unit)? = null

    fun afterAdmission(action: (Boolean) -> Unit) {
        if (!pending) action(true) else admission = action
    }

    fun edit(next: T): Any {
        value = next
        pending = true
        return Any().also { token = it }
    }

    fun handled(handledToken: Any, acceptedValue: T, editVersion: Long) {
        if (handledToken !== token) return
        val admitted = acceptedValue == value
        token = null
        pending = false
        value = acceptedValue
        minimumEditVersion = maxOf(minimumEditVersion, editVersion)
        val waiting = admission
        admission = null
        waiting?.invoke(admitted)
    }

    fun acceptExternal(model: T, editVersion: Long) {
        if (!pending && editVersion >= minimumEditVersion) {
            value = model
            minimumEditVersion = editVersion
        }
    }

    fun discardPending() {
        token = null
        pending = false
        val waiting = admission
        admission = null
        waiting?.invoke(false)
    }
}
