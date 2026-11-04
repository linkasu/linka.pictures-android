package su.linka.pictures.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class BoardPaginationTest {
    @Test
    fun preservesAbsoluteIndicesAndClampsPageAfterResize() {
        val secondPage = BoardPagination.of(rows = 3, columns = 4, cardCount = 13, page = 1)
        assertEquals(12, secondPage.firstIndex)
        assertEquals(2, secondPage.pageCount)
        assertEquals(12, secondPage.pageSize)
        val resized = BoardPagination.of(rows = 4, columns = 4, cardCount = 13, page = 1)
        assertEquals(0, resized.page)
        assertEquals(1, resized.pageCount)
    }

    @Test
    fun emptyAndInvalidPresentationInputsStillHaveOnePage() {
        assertEquals(BoardPagination(12, 1, 0), BoardPagination.of(3, 4, 0, -1))
        assertEquals(BoardPagination(1, 1, 0), BoardPagination.of(0, -1, 0, 99))
        assertEquals(Int.MAX_VALUE, BoardPagination.of(Int.MAX_VALUE, Int.MAX_VALUE, 1, 0).pageSize)
    }
}
