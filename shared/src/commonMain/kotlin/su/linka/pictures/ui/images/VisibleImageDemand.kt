package su.linka.pictures.ui.images

import androidx.compose.runtime.*
import su.linka.pictures.domain.AssetId
import su.linka.pictures.domain.BoardPage
import su.linka.pictures.domain.CellKind

/** Composition ownership only; decoding, leases and cache limits belong to the root loader. */
internal class VisibleImageRegistry {
    private val owners = mutableMapOf<Any, Set<AssetId>>()
    private var listener: ((Set<AssetId>) -> Unit)? = null
    private var published = emptySet<AssetId>()
    private var closed = false

    fun listen(callback: ((Set<AssetId>) -> Unit)?) {
        if (closed || listener === callback) return
        if (callback == null) listener?.invoke(emptySet())
        listener = callback
        published = owners.values.flatten().toSet()
        callback?.invoke(published)
    }

    fun register(owner: Any, images: Set<AssetId>) {
        if (closed) return
        owners[owner] = images
        publish()
    }

    fun remove(owner: Any) {
        owners.remove(owner)
        publish()
    }

    fun close() {
        if (closed) return
        closed = true
        owners.clear()
        published = emptySet()
        listener?.invoke(emptySet())
        listener = null
    }

    private fun publish() {
        if (closed) return
        val images = owners.values.flatten().toSet()
        if (images == published) return
        published = images
        listener?.invoke(images)
    }
}

private val LocalVisibleImages = staticCompositionLocalOf<VisibleImageRegistry?> { null }

@Composable
internal fun VisibleImageDemand(
    generation: Any?,
    onImagesNeeded: ((Set<AssetId>) -> Unit)?,
    content: @Composable () -> Unit,
) {
    val registry = remember(generation) { VisibleImageRegistry() }
    SideEffect { registry.listen(onImagesNeeded) }
    DisposableEffect(registry) { onDispose { registry.close() } }
    CompositionLocalProvider(LocalVisibleImages provides registry, content = content)
}

@Composable
internal fun DemandVisibleImages(source: Any?, images: Set<AssetId>) {
    val registry = LocalVisibleImages.current ?: return
    // Each effect incarnation owns its own token: disposal can never remove its replacement.
    DisposableEffect(registry, source, images) {
        val token = Any()
        registry.register(token, images)
        onDispose { registry.remove(token) }
    }
}

internal fun visibleRowImages(page: BoardPage, row: Int): Set<AssetId> = page.cells
    .filter { it.position / page.columns == row && it.kind == CellKind.CONTENT }
    .mapNotNull { it.card?.image }.toSet()
