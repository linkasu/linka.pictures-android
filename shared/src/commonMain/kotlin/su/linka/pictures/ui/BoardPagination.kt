package su.linka.pictures.ui

/** Pure presentation pagination; the document itself must be validated by its owner. */
internal data class BoardPagination(val pageSize: Int, val pageCount: Int, val page: Int) {
    val firstIndex: Int get() = page * pageSize

    companion object {
        fun of(rows: Int, columns: Int, cardCount: Int, page: Int): BoardPagination {
            val size = (rows.coerceAtLeast(1).toLong() * columns.coerceAtLeast(1))
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val count = ((cardCount.coerceAtLeast(1).toLong() + size - 1) / size).toInt()
            return BoardPagination(size, count, page.coerceIn(0, count - 1))
        }
    }
}
