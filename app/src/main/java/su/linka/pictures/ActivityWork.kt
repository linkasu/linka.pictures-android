package su.linka.pictures

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/** One operation per host. Started IO finishes; destroyed hosts never receive its result. */
class ActivityWork(private val activity: ComponentActivity) {
    var busy by mutableStateOf(false)
        private set

    fun <T> run(work: () -> T, success: (T) -> Unit, failure: (Exception) -> Unit,
                abandoned: (T) -> Unit = {}) {
        if (busy || activity.isDestroyed) return
        busy = true
        activity.lifecycleScope.launch {
            var result: T? = null
            var completed = false
            var delivered = false
            try {
                // Cancellation must not interrupt an atomic commit or leak a newly opened workspace.
                withContext(Dispatchers.IO + NonCancellable) {
                    result = work()
                    completed = true
                }
                ensureActive()
                busy = false
                @Suppress("UNCHECKED_CAST")
                success(result as T)
                delivered = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!activity.isDestroyed && isActive) {
                    busy = false
                    failure(error)
                }
            } finally {
                if (completed && !delivered) {
                    @Suppress("UNCHECKED_CAST")
                    withContext(Dispatchers.IO + NonCancellable) { abandoned(result as T) }
                }
            }
        }
    }
}
