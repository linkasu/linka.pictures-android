package su.linka.pictures.shared

data class GridDimensions(val rows: Int, val columns: Int) {
    init {
        require(rows in 1..BoardLimits.MAX_DIMENSION && columns in 1..BoardLimits.MAX_DIMENSION) {
            "Rows and columns must be between 1 and ${BoardLimits.MAX_DIMENSION}"
        }
        require(rows * columns <= BoardLimits.MAX_CELLS) { "Too many grid cells" }
    }

    val cellCount: Int get() = rows * columns
}
