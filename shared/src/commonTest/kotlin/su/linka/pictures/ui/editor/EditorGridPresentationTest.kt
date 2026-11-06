package su.linka.pictures.ui.editor

import kotlin.test.*
import su.linka.pictures.domain.*
import androidx.compose.ui.graphics.vector.PathParser

class EditorGridPresentationTest {
    @Test fun bundledToolbarPathsParseWithoutAnIconPackDependency() {
        EditorSymbol.entries.forEach { assertTrue(PathParser().parsePathString(it.path).toNodes().isNotEmpty()) }
    }

    @Test fun ordinaryNineCellsFitWithoutForcedNinetySixDpMinimum() {
        val width = editorCellExtent(248f, 3, 64f)
        val height = editorCellExtent(480f, 3, 80f)
        assertEquals(248f, width * 3 + 8f)
        assertEquals(480f, height * 3 + 8f)
        assertTrue(width < 96f)
    }

    @Test fun largeTextAndDenseGridsScrollInsteadOfShrinkingTargets() {
        assertEquals(128f, editorCellExtent(248f, 3, 128f))
        assertEquals(80f, editorCellExtent(480f, 64, 80f))
    }

    @Test fun contentWithEmptyLabelIsNotAnEmptyCell() {
        val cell = BoardCell(0, CellKind.CONTENT, CardDocument(CardId("card")))
        assertEquals("Карточка без подписи", editorCellLabel(cell))
        assertEquals("Создать карточку", editorCellLabel(BoardCell(1, CellKind.EMPTY, extensionsJson = "{\"opaque\":true}")))
        assertEquals("Пробел", editorCellLabel(BoardCell(2, CellKind.SPACE)))
        assertEquals("Создать карточку", editorCellLabel(null))
    }

    @Test fun resumedFormKeepsItsOwnGridVisibleEvenAfterPageReordering() {
        val a = BoardPage(PageId("a"), 3, 3, emptyList())
        val b = BoardPage(PageId("b"), 2, 2, emptyList(), extensionsJson = "{\"opaque\":true}")
        val draft = EditorDraft(DraftId("draft"), null, null, BoardDocument(BoardId("board"), "Доска", listOf(a, b)))
        assertEquals(b, editorPage(draft.copy(form = EditorForm.Card(b.id, 0, CardId("card"))), "a"))
        assertEquals(b, editorPage(draft.copy(form = EditorForm.Grid(b.id, "2", "2")), "a"))
        assertEquals(b, editorPage(draft, "b"))
        assertEquals(a, editorPage(draft, "removed"))
        assertNull(editorPage(null, "a"))
        assertEquals("{\"opaque\":true}", b.extensionsJson)
    }
}
