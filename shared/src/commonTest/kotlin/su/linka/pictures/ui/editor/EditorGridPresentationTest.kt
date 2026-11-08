package su.linka.pictures.ui.editor

import su.linka.pictures.resources.*
import kotlin.test.*
import su.linka.pictures.domain.*
import androidx.compose.ui.graphics.vector.PathParser

class EditorGridPresentationTest {
    @Test fun settingsRequireExplicitGridSaveOrCancelAndSettledInputsBeforeDismissal() {
        assertTrue(editorSettingsCanDismiss(null, false, false))
        assertFalse(editorSettingsCanDismiss(null, true, false))
        assertFalse(editorSettingsCanDismiss(null, false, true))
        assertFalse(editorSettingsCanDismiss(EditorForm.Grid(PageId("page"), "3", "4"), false, false))
        assertFalse(editorSettingsCanDismiss(EditorForm.Card(PageId("page"), 0, CardId("card")), false, false))
    }

    @Test fun withoutSpaceCheckboxUsesExistingInputStyleWithoutChangingDirectMode() {
        InteractionMode.entries.forEach { mode ->
            val document = BoardDocument(BoardId("board"), "Название", listOf(BoardPage(PageId("page"), 3, 3, emptyList())),
                behavior = BoardBehavior(mode, InputStyle.CARDS), description = "Описание")
            val checked = withoutSpaceDetails(document, true)
            assertEquals(InputStyle.LITERAL, checked.behavior.inputStyle)
            assertEquals(mode, checked.behavior.defaultMode)
            assertEquals(document.name, checked.name)
            assertEquals(document.description, checked.description)
            val unchecked = withoutSpaceDetails(document.copy(behavior = checked.behavior), false)
            assertEquals(document.behavior, unchecked.behavior)
        }
    }

    @Test fun bundledToolbarPathsParseWithoutAnIconPackDependency() {
        EditorSymbol.entries.forEach { assertTrue(PathParser().parsePathString(it.path).toNodes().isNotEmpty()) }
    }

    @Test fun ordinaryNineCellsFitWithoutForcedNinetySixDpMinimum() {
        val width = editorCellExtent(248f, 3, 64f)
        val height = editorCellExtent(480f, 3, 80f)
        assertEquals(248f, width * 3 + 16f)
        assertEquals(480f, height * 3 + 16f)
        assertTrue(width < 96f)
    }

    @Test fun largeTextAndDenseGridsScrollInsteadOfShrinkingTargets() {
        assertEquals(128f, editorCellExtent(248f, 3, 128f))
        assertEquals(80f, editorCellExtent(480f, 64, 80f))
    }

    @Test fun contentWithEmptyLabelIsNotAnEmptyCell() {
        val cell = BoardCell(0, CellKind.CONTENT, CardDocument(CardId("card")))
        assertEquals(Res.string.unlabelled_card, editorCellLabel(cell))
        assertEquals(Res.string.create_card, editorCellLabel(BoardCell(1, CellKind.EMPTY, extensionsJson = "{\"opaque\":true}")))
        assertEquals(Res.string.space, editorCellLabel(BoardCell(2, CellKind.SPACE)))
        assertEquals(Res.string.create_card, editorCellLabel(null))
        assertNull(editorCellLabel(cell.copy(card = cell.card!!.copy(label = "User's own label"))))
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
