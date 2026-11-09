package su.linka.pictures.bootstrap

internal data class RootWindowSnapshot(val active: Boolean, val foreground: Boolean)

/** The fallback is supplied by this window's weak identity, never by a global key-window search. */
// sameWindow compares native identity: UIKit can return another Kotlin wrapper for the same object.
internal fun <T : Any> rootWindowSnapshot(
    open: Boolean, current: T?, cached: T?, applicationActive: Boolean, applicationBackground: Boolean,
    hidden: (T) -> Boolean, sceneActive: (T) -> Boolean, sceneBackground: (T) -> Boolean,
    sameWindow: (T, T) -> Boolean,
): RootWindowSnapshot {
    if (current != null && cached != null && !sameWindow(current, cached)) return RootWindowSnapshot(false, false)
    val native = if (open) current ?: cached else null
    if (native == null || hidden(native)) return RootWindowSnapshot(false, false)
    return RootWindowSnapshot(applicationActive && sceneActive(native), !applicationBackground && !sceneBackground(native))
}
