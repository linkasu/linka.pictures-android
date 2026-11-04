package su.linka.pictures.application.ports

// Owned sandbox files only, never provider URLs. Handles cannot be serialized into library data.
open class LocalFile internal constructor(val path: String, val displayName: String? = null, val mediaTypeHint: String? = null) {
    init { require(path.isNotBlank()) }
}

class TemporaryFile internal constructor(path: String, displayName: String? = null, mediaTypeHint: String? = null) :
    LocalFile(path, displayName, mediaTypeHint)
