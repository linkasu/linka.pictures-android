package su.linka.pictures.ui.app

internal enum class ProductBackAction { CONSUME, NATIVE_EXIT, LIBRARY, COMMUNICATION, EDITOR }

internal fun productBackAction(started: Boolean, transitioning: Boolean, page: ProductPage?,
    boardSettings: Boolean): ProductBackAction = when {
    !started || transitioning -> ProductBackAction.CONSUME
    page == ProductPage.LIBRARY -> ProductBackAction.NATIVE_EXIT
    page == ProductPage.EDITOR -> ProductBackAction.EDITOR
    page == ProductPage.SETTINGS && boardSettings -> ProductBackAction.COMMUNICATION
    else -> ProductBackAction.LIBRARY
}

/** Each published callback belongs to one incarnation, never to its eventual replacement. */
internal class OwnedProductBack(private val onChanged: ((() -> Unit)?) -> Unit) {
    private var alive = true
    private var incarnation: Any = Any()

    fun update(action: (() -> Unit)?) {
        if (!alive) return
        val token = Any()
        incarnation = token
        onChanged(action?.let { { if (alive && incarnation === token) it() } })
    }

    fun close() {
        if (!alive) return
        alive = false
        onChanged(null)
    }
}
