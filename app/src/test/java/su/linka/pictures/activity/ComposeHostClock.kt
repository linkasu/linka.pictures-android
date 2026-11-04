package su.linka.pictures.activity

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.robolectric.Shadows.shadowOf

/** Keep real lifecycle-aware recomposers, but never reuse a dispatcher from a reset Robolectric looper. */
internal class ComposeHostClock : AutoCloseable {
    private val clock = BroadcastFrameClock()
    private val recomposers = mutableListOf<Recomposer>()
    private val dispatcher = Handler(Looper.getMainLooper()).asCoroutineDispatcher()
    private val attached = mutableSetOf<ComposeView>()
    private var frameNanos = 0L

    init { active = this }

    fun attach(view: ComposeView) {
        if (attached.add(view)) {
            val recomposer = view.createLifecycleAwareWindowRecomposer(dispatcher + clock)
            recomposers.add(recomposer)
            view.setParentCompositionContext(recomposer)
        }
    }

    fun advance() {
        frameNanos += 16_666_667L
        clock.sendFrame(frameNanos)
    }

    override fun close() {
        recomposers.forEach { it.cancel() }
        shadowOf(Looper.getMainLooper()).idle()
        active = null
    }

    companion object {
        var active: ComposeHostClock? = null
            private set
    }
}
