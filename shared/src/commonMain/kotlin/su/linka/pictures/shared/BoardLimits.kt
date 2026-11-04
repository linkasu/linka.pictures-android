package su.linka.pictures.shared

object BoardLimits {
    const val MAX_DIMENSION = 32
    const val MAX_CELLS = 1024
    const val MAX_CARDS = 10_000

    fun validateCardCount(count: Int) {
        require(count in 0..MAX_CARDS) { "Too many cards (maximum $MAX_CARDS)" }
    }

    fun validateCardType(type: Int) {
        require(type in 0..3) { "Invalid card type: $type" }
    }

    fun validateMediaPath(path: String) {
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path &&
            path.none { it.code < 32 } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Invalid relative path: $path"
        }
    }
}
