package su.linka.pictures.bootstrap

/** Native Main-thread lifecycle ownership, including windows not yet attached to the product. */
internal class StartupVisibility {
    private val started = mutableSetOf<Any>()
    fun start(owner: Any) { started.add(owner) }
    fun stop(owner: Any, changingConfiguration: Boolean): Boolean =
        started.remove(owner) && started.isEmpty() && !changingConfiguration
}
