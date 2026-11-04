package su.linka.pictures.text

internal expect fun normalizeNfc(text: String): String

// Match the peer's NFC-then-lowercase order; path validation is a separate trust-boundary check.
internal fun archivePathCollisionKey(path: String): String = normalizeNfc(path.removeSuffix("/")).lowercase()
