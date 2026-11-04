package su.linka.pictures.application.ports

// Random IDs only; content hashes are AssetIds and do not pass through this port.
fun interface IdSource {
    fun next(): String
}
